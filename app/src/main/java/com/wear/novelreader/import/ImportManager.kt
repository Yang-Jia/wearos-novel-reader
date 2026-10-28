package com.wear.novelreader.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wear.novelreader.R
import com.wear.novelreader.data.BookMeta
import com.wear.novelreader.data.ImportedInfo
import com.wear.novelreader.data.LibraryRepository
import com.wear.novelreader.util.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class ImportCancelledException : Exception("导入已取消")

class ImportManager(
    private val context: Context,
    private val repository: LibraryRepository
) {

    data class Failure(val name: String, val reason: String)

    data class ImportSummary(
        val success: MutableList<BookMeta> = mutableListOf(),
        val failures: MutableList<Failure> = mutableListOf()
    )

    val inboxDir: File
        get() {
            val external = context.getExternalFilesDir(null) ?: context.filesDir
            return File(external, "inbox").also { it.mkdirs() }
        }

    suspend fun importUris(
        uris: List<Uri>,
        onProgress: (index: Int, total: Int, name: String) -> Unit
    ): ImportSummary = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val isCancelled = { !coroutine.isActive }
        val summary = ImportSummary()
        val cacheDir = File(context.cacheDir, "uri-import").also { it.mkdirs() }
        uris.forEachIndexed { index, uri ->
            coroutine.ensureActive()
            val name = queryDisplayName(uri) ?: "导入书籍-$index"
            onProgress(index + 1, uris.size, name)
            val temp = File(cacheDir, "src-${UUID.randomUUID()}.bin")
            try {
                copyUriToFile(uri, temp, isCancelled)
                val book = importFile(temp, name, isCancelled)
                summary.success.add(book)
            } catch (e: ImportCancelledException) {
                throw e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                summary.failures.add(Failure(name, failureReason(t)))
            } finally {
                temp.delete()
            }
        }
        summary
    }

    suspend fun importInbox(
        onProgress: (index: Int, total: Int, name: String) -> Unit
    ): ImportSummary = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val isCancelled = { !coroutine.isActive }
        val files = inboxDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.filter { FileUtil.extensionOf(it.name) in setOf("txt", "epub") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val summary = ImportSummary()
        files.forEachIndexed { index, file ->
            coroutine.ensureActive()
            onProgress(index + 1, files.size, file.name)
            try {
                val book = importFile(file, file.name, isCancelled)
                summary.success.add(book)
                moveToImported(file)
            } catch (e: ImportCancelledException) {
                throw e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                summary.failures.add(Failure(file.name, failureReason(t)))
                moveToFailed(file)
            }
        }
        summary
    }

    fun importFile(
        file: File,
        displayName: String,
        isCancelled: () -> Boolean = { false }
    ): BookMeta {
        if (isCancelled()) throw ImportCancelledException()
        if (file.length() == 0L) throw IOException("文件为空")
        if (file.length() > MAX_BYTES) throw IOException(context.getString(R.string.import_too_large))
        val safeName = FileUtil.sanitizeFileName(displayName)
        val fallbackTitle = FileUtil.displayNameFromFileName(safeName)
        val extension = FileUtil.extensionOf(safeName)
        if (extension.isNotEmpty() && extension !in setOf("txt", "epub")) {
            throw IOException(context.getString(R.string.import_unsupported))
        }
        val format = detectFormat(file, safeName)
        val workDir = repository.createWorkDir()
        val chaptersDir = File(workDir, "chapters").also { it.mkdirs() }
        var committed = false
        try {
            val info: ImportedInfo = when (format) {
                "epub" -> EpubImporter.import(
                    file,
                    chaptersDir,
                    fallbackTitle,
                    isCancelled = isCancelled
                )
                else -> TxtImporter.import(
                    file,
                    chaptersDir,
                    fallbackTitle,
                    isCancelled = isCancelled
                )
            }
            if (isCancelled()) throw ImportCancelledException()
            val book = repository.commitImport(workDir, info)
            committed = true
            return book
        } catch (e: ImportCancelledException) {
            throw e
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is Error) throw t
            throw if (t is IOException) t else IOException(t.message ?: context.getString(R.string.import_parse_failed), t)
        } finally {
            if (!committed) {
                FileUtil.deleteRecursively(workDir)
            }
        }
    }

    private fun detectFormat(file: File, name: String): String {
        val ext = FileUtil.extensionOf(name)
        if (ext == "epub") return "epub"
        if (ext == "txt") return "txt"
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        if (read >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            return "epub"
        }
        return "txt"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File, isCancelled: () -> Boolean = { false }) {
        val input = if (uri.scheme == "file") {
            uri.path?.let { java.io.FileInputStream(it) }
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: throw IOException(context.getString(R.string.import_unreadable))
        input.use { source ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (isCancelled()) throw ImportCancelledException()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw IOException(context.getString(R.string.import_too_large))
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun moveToImported(file: File) {
        val importedDir = File(inboxDir, "imported").also { it.mkdirs() }
        moveFile(file, importedDir)
    }

    private fun moveToFailed(file: File) {
        val failedDir = File(inboxDir, "failed").also { it.mkdirs() }
        moveFile(file, failedDir)
    }

    private fun moveFile(file: File, targetDir: File) {
        var target = File(targetDir, file.name)
        if (target.exists()) {
            target = File(
                targetDir,
                "${file.nameWithoutExtension}-${System.currentTimeMillis()}.${file.extension}"
            )
        }
        if (!file.renameTo(target)) {
            runCatching { file.copyTo(target, overwrite = true); file.delete() }
        }
    }

    private fun failureReason(t: Throwable): String {
        return if (t is OutOfMemoryError) context.getString(R.string.import_memory_low) else t.message ?: context.getString(R.string.import_parse_failed)
    }

    companion object {
        private const val MAX_BYTES = 80L * 1024L * 1024L
    }
}

