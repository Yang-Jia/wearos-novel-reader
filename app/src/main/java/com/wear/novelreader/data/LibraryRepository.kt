package com.wear.novelreader.data

import com.wear.novelreader.util.FileUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibraryRepository(val root: File) {

    data class Snapshot(val books: List<BookItem>, val version: Long)

    private val lock = Any()
    private var cachedBooks: MutableList<BookItem>? = null
    private var cachedSorted: List<BookItem>? = null
    private var rootStamp = 0L

    @Volatile
    private var cacheVersion = 0L
    private val processStartedAt = System.currentTimeMillis()

    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = mutableChanges.asSharedFlow()

    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "library-write").apply { isDaemon = true }
    }

    init {
        root.mkdirs()
    }

    fun createWorkDir(): File {
        val dir = File(root, ".tmp-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        dir.mkdirs()
        return dir
    }

    fun commitImport(workDir: File, info: ImportedInfo): BookMeta {
        val meta = synchronized(lock) {
            require(info.chapters.isNotEmpty()) { "没有解析到任何章节" }
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val meta = BookMeta(
                id = id,
                title = info.title.ifBlank { "未命名" },
                author = info.author,
                format = info.format,
                addedAt = System.currentTimeMillis(),
                chapters = info.chapters
            )
            FileUtil.writeText(File(workDir, "meta.json"), Json.metaToJson(meta).toString())
            val dest = File(root, id)
            if (dest.exists()) FileUtil.deleteRecursively(dest)
            if (!workDir.renameTo(dest)) {
                workDir.copyRecursively(dest, overwrite = true)
                FileUtil.deleteRecursively(workDir)
            }
            cachedBooks?.add(BookItem(meta, BookProgress()))
            cachedSorted = null
            cacheVersion++
            rootStamp = root.lastModified()
            meta
        }
        mutableChanges.tryEmit(Unit)
        return meta
    }

    fun listBooks(): List<BookItem> = snapshot().books

    fun snapshot(): Snapshot = synchronized(lock) {
        ensureCacheLocked()
        val books = cachedSorted ?: sortBooks(cachedBooks.orEmpty()).also { cachedSorted = it }
        Snapshot(books, cacheVersion)
    }

    fun getBook(id: String): BookMeta? = synchronized(lock) {
        cachedBooks?.firstOrNull { it.meta.id == id }?.meta?.let { return it }
        val dir = bookDir(id)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null
        try {
            Json.metaFromJson(metaFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun readChapter(book: BookMeta, index: Int): String {
        val chapter = book.chapters.getOrNull(index) ?: return ""
        return FileUtil.readText(File(File(bookDir(book.id), "chapters"), chapter.file))
    }

    fun loadProgress(id: String): BookProgress = synchronized(lock) {
        cachedBooks?.firstOrNull { it.meta.id == id }?.progress ?: loadProgressInternal(id)
    }

    private fun loadProgressInternal(id: String): BookProgress {
        val file = File(bookDir(id), "progress.json")
        if (!file.isFile) return BookProgress()
        return try {
            Json.progressFromJson(file.readText())
        } catch (_: Exception) {
            BookProgress()
        }
    }

    fun saveProgress(id: String, progress: BookProgress) {
        val saved = synchronized(lock) {
            val dir = bookDir(id)
            if (!dir.isDirectory) return
            val value = progress.copy(updatedAt = System.currentTimeMillis())
            cachedBooks?.let { books ->
                val index = books.indexOfFirst { it.meta.id == id }
                if (index >= 0) {
                    val old = books[index]
                    books[index] = old.copy(progress = value)
                }
            }
            cachedSorted = null
            cacheVersion++
            value
        }
        writeExecutor.execute {
            try {
                synchronized(lock) {
                    val dir = bookDir(id)
                    if (!dir.isDirectory) return@synchronized
                    FileUtil.writeText(
                        File(dir, "progress.json"),
                        Json.progressToJson(saved).toString()
                    )
                }
            } catch (_: Exception) {
            }
        }
        mutableChanges.tryEmit(Unit)
    }

    fun flushWrites() {
        val barrier = writeExecutor.submit {}
        runCatching { barrier.get(2, TimeUnit.SECONDS) }
    }

    fun deleteBook(id: String): File? {
        val trash = synchronized(lock) {
            val dir = bookDir(id)
            var staged: File? = null
            if (dir.exists()) {
                val candidate = File(root, ".trash-$id-${System.nanoTime()}")
                if (dir.renameTo(candidate)) {
                    staged = candidate
                } else {
                    FileUtil.deleteRecursively(dir)
                }
            }
            cachedBooks?.removeAll { it.meta.id == id }
            cachedSorted = null
            cacheVersion++
            rootStamp = root.lastModified()
            staged
        }
        mutableChanges.tryEmit(Unit)
        return trash
    }

    fun restoreDeleted(id: String, trash: File?) {
        if (trash == null || !trash.exists()) return
        synchronized(lock) {
            val destination = bookDir(id)
            if (destination.exists()) {
                FileUtil.deleteRecursively(trash)
            } else if (!trash.renameTo(destination)) {
                trash.copyRecursively(destination, overwrite = true)
                FileUtil.deleteRecursively(trash)
            }
            cachedBooks = null
            cachedSorted = null
            cacheVersion++
            rootStamp = root.lastModified()
        }
        mutableChanges.tryEmit(Unit)
    }

    fun cleanupTrash(isCancelled: () -> Boolean = { false }) {
        val staleBefore = processStartedAt - 60L * 60L * 1000L
        val trash = synchronized(lock) {
            root.listFiles()
                ?.filter {
                    if (!it.isDirectory) return@filter false
                    when {
                        it.name.startsWith(".trash-") -> true
                        it.name.startsWith(".tmp-") -> it.lastModified() < staleBefore
                        else -> false
                    }
                }
                ?: emptyList()
        }
        for (dir in trash) {
            if (isCancelled()) return
            FileUtil.deleteRecursively(dir, isCancelled)
        }
    }

    fun invalidateCache() {
        synchronized(lock) {
            cachedBooks = null
            cachedSorted = null
            rootStamp = 0L
            cacheVersion++
        }
        mutableChanges.tryEmit(Unit)
    }

    fun bookDir(id: String): File = File(root, id)

    private fun ensureCacheLocked() {
        val stamp = root.lastModified()
        if (cachedBooks != null && stamp != rootStamp) {
            cachedBooks = null
            cachedSorted = null
            cacheVersion++
        }
        if (cachedBooks == null) {
            cachedBooks = scanBooks()
            cachedSorted = null
            rootStamp = stamp
        }
    }

    private fun scanBooks(): MutableList<BookItem> {
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?: return mutableListOf()
        return dirs.mapNotNull { dir ->
            try {
                val metaFile = File(dir, "meta.json")
                if (!metaFile.isFile) return@mapNotNull null
                val meta = Json.metaFromJson(metaFile.readText())
                BookItem(meta, loadProgressInternal(meta.id))
            } catch (_: Exception) {
                null
            }
        }.toMutableList()
    }

    private fun sortBooks(books: List<BookItem>): List<BookItem> {
        return books.sortedWith(
            compareByDescending<BookItem> { maxOf(it.progress.updatedAt, it.meta.addedAt) }
                .thenByDescending { it.meta.addedAt }
        )
    }
}
