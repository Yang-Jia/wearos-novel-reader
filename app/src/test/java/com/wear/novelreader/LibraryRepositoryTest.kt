package com.wear.novelreader

import com.wear.novelreader.data.BookProgress
import com.wear.novelreader.data.Chapter
import com.wear.novelreader.data.ImportedInfo
import com.wear.novelreader.data.LibraryRepository
import com.wear.novelreader.import.TxtImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class LibraryRepositoryTest {

    @Test
    fun importsTxtThenReadsItBack() {
        val root = Files.createTempDirectory("library-import").toFile()
        try {
            val repository = LibraryRepository(root)
            val source = java.io.File(root, "小说.txt").apply {
                writeText("第一章 开始\n正文内容\n", Charsets.UTF_8)
            }
            val work = repository.createWorkDir()
            val chaptersDir = java.io.File(work, "chapters").also { it.mkdirs() }
            val info = TxtImporter.import(source, chaptersDir, "小说")
            val meta = repository.commitImport(work, info)

            assertEquals("小说", meta.title)
            assertEquals("第一章 开始", meta.chapters[0].title)
            assertEquals(
                "第一章 开始\n　　正文内容\n",
                repository.readChapter(meta, 0)
            )
            assertEquals(1, repository.listBooks().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emitsChangesWhenBooksAreImportedProgressSavedAndDeleted() = runBlocking {
        val root = Files.createTempDirectory("library-events").toFile()
        try {
            val repository = LibraryRepository(root)
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                repository.changes.take(3).toList()
            }
            val work = repository.createWorkDir()
            val chaptersDir = java.io.File(work, "chapters").also { it.mkdirs() }
            java.io.File(chaptersDir, "0001.txt").writeText("正文\n")
            val meta = repository.commitImport(
                work,
                ImportedInfo(
                    title = "事件测试",
                    author = "",
                    format = "txt",
                    chapters = listOf(Chapter("第一章", "0001.txt", 3))
                )
            )
            repository.saveProgress(meta.id, BookProgress(chapterIndex = 0, charOffset = 1))
            repository.deleteBook(meta.id)
            assertEquals(3, events.await().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commitsListsLoadsAndDeletesBook() {
        val root = Files.createTempDirectory("library").toFile()
        try {
            val repository = LibraryRepository(root)
            val work = repository.createWorkDir()
            val chaptersDir = java.io.File(work, "chapters").also { it.mkdirs() }
            java.io.File(chaptersDir, "0001.txt").writeText("第一章内容\n")
            java.io.File(chaptersDir, "0002.txt").writeText("第二章内容\n")

            val meta = repository.commitImport(
                work,
                ImportedInfo(
                    title = "测试书",
                    author = "作者",
                    format = "txt",
                    chapters = listOf(
                        Chapter("第一章", "0001.txt", 6),
                        Chapter("第二章", "0002.txt", 6)
                    )
                )
            )

            val books = repository.listBooks()
            assertEquals(1, books.size)
            assertEquals("测试书", books[0].meta.title)
            assertEquals(2, books[0].meta.chapters.size)
            assertEquals("第一章内容\n", repository.readChapter(meta, 0))
            assertEquals(0, books[0].percent)

            val versionBeforeSave = repository.snapshot().version
            repository.saveProgress(meta.id, BookProgress(chapterIndex = 1, charOffset = 3))
            val progress = repository.loadProgress(meta.id)
            assertEquals(1, progress.chapterIndex)
            assertEquals(3, progress.charOffset)
            assertTrue(progress.updatedAt > 0)
            assertEquals(75, repository.listBooks()[0].percent)
            assertTrue(repository.snapshot().version > versionBeforeSave)

            repository.deleteBook(meta.id)
            assertTrue(repository.listBooks().isEmpty())
            assertNull(repository.getBook(meta.id))
            assertNotNull(meta.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteCanBeRestoredBeforeCleanup() {
        val root = Files.createTempDirectory("library-undo").toFile()
        try {
            val repository = LibraryRepository(root)
            val work = repository.createWorkDir()
            val chaptersDir = java.io.File(work, "chapters").also { it.mkdirs() }
            java.io.File(chaptersDir, "0001.txt").writeText("正文\n")
            val meta = repository.commitImport(
                work,
                ImportedInfo(
                    title = "撤销测试",
                    author = "",
                    format = "txt",
                    chapters = listOf(Chapter("第一章", "0001.txt", 3))
                )
            )
            val trash = repository.deleteBook(meta.id)
            assertTrue(repository.listBooks().isEmpty())
            repository.restoreDeleted(meta.id, trash)
            assertEquals(1, repository.listBooks().size)
            assertEquals("正文\n", repository.readChapter(meta, 0))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cachesSortedSnapshotAndRefreshesExternalChanges() {
        val root = Files.createTempDirectory("library-cache").toFile()
        try {
            val repository = LibraryRepository(root)
            val work = repository.createWorkDir()
            val chaptersDir = java.io.File(work, "chapters").also { it.mkdirs() }
            java.io.File(chaptersDir, "0001.txt").writeText("正文\n")
            val meta = repository.commitImport(
                work,
                ImportedInfo(
                    title = "缓存测试",
                    author = "",
                    format = "txt",
                    chapters = listOf(Chapter("第一章", "0001.txt", 3))
                )
            )

            val first = repository.snapshot()
            val second = repository.snapshot()
            assertSame(first.books, second.books)
            assertEquals(1, second.books.size)
            assertEquals(first.version, second.version)

            repository.bookDir(meta.id).deleteRecursively()
            val refreshed = repository.snapshot()
            assertTrue(refreshed.books.isEmpty())
            assertTrue(refreshed.version > second.version)
        } finally {
            root.deleteRecursively()
        }
    }
}
