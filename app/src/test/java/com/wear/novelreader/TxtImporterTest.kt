package com.wear.novelreader

import com.wear.novelreader.import.ImportCancelledException
import com.wear.novelreader.import.TxtImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

class TxtImporterTest {

    @Test
    fun splitsChineseChaptersAndKeepsAllText() {
        val source = buildString {
            append("书名：测试小说\r\n")
            append("作者：测试作者\r\n")
            append("\r\n")
            append("第一章 起点\r\n")
            append("这是第一段内容。\r\n")
            append("\r\n")
            append("第二章 继续\r\n")
            append("这是第二段内容。\r\n")
        }
        val dir = Files.createTempDirectory("txt-import").toFile()
        try {
            val file = File(dir, "book.txt")
            file.writeText(source, Charsets.UTF_8)
            val info = TxtImporter.import(file, File(dir, "chapters"), "fallback")

            assertEquals("测试小说", info.title)
            assertEquals("测试作者", info.author)
            assertEquals(2, info.chapters.size)
            assertEquals("第一章 起点", info.chapters[0].title)
            assertEquals("第二章 继续", info.chapters[1].title)

            val all = info.chapters.joinToString("") {
                File(File(dir, "chapters"), it.file).readText()
            }
            assertEquals(
                "第一章 起点\n　　这是第一段内容。\n第二章 继续\n　　这是第二段内容。\n",
                all
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun splitsLongChapterWithoutLosingCharacters() {
        val builder = StringBuilder("第一章 很长\r\n")
        repeat(200) { builder.append("第").append(it).append("段内容，用于测试分块。\r\n") }
        val dir = Files.createTempDirectory("txt-chunk").toFile()
        try {
            val file = File(dir, "long.txt")
            file.writeText(builder.toString(), Charsets.UTF_8)
            val info = TxtImporter.import(file, File(dir, "chapters"), "long", chunkLimit = 100)
            assertTrue(info.chapters.size > 3)
            val all = info.chapters.joinToString("") {
                File(File(dir, "chapters"), it.file).readText()
            }
            val expected = buildString {
                append("第一章 很长\n")
                repeat(200) { append("　　第").append(it).append("段内容，用于测试分块。\n") }
            }
            assertEquals(expected, all)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun handlesBookWithoutHeadingsAndGbk() {
        val text = "这是一本没有章节标题的小说。\n第二段。\n第三段。\n"
        val dir = Files.createTempDirectory("txt-noheading").toFile()
        try {
            val file = File(dir, "无名.txt")
            file.writeText(text, Charset.forName("GBK"))
            val info = TxtImporter.import(file, File(dir, "chapters"), "无名", chunkLimit = 12)
            assertEquals("无名", info.title)
            assertTrue(info.chapters.size >= 2)
            val all = info.chapters.joinToString("") {
                File(File(dir, "chapters"), it.file).readText()
            }
            assertEquals(
                "　　这是一本没有章节标题的小说。\n　　第二段。\n　　第三段。\n",
                all
            )
            assertEquals("第1节", info.chapters.first().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsLongPrefaceBeforeFirstHeading() {
        val source = buildString {
            repeat(100) { append("这是很长的前言内容。\n") }
            append("第一章 标题\n")
            append("正文内容。\n")
        }
        val dir = Files.createTempDirectory("txt-preface").toFile()
        try {
            val file = File(dir, "preface.txt")
            file.writeText(source, Charsets.UTF_8)
            val info = TxtImporter.import(file, File(dir, "chapters"), "preface")
            assertEquals("简介", info.chapters[0].title)
            assertEquals("第一章 标题", info.chapters[1].title)
            val intro = File(File(dir, "chapters"), info.chapters[0].file).readText()
            assertTrue(intro.contains("这是很长的前言内容。"))
            val first = File(File(dir, "chapters"), info.chapters[1].file).readText()
            assertTrue(first.startsWith("第一章 标题\n"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun handlesUtf16WithBom() {
        val text = "第一章 标题\n内容。\n"
        val dir = Files.createTempDirectory("txt-utf16").toFile()
        try {
            val file = File(dir, "utf16.txt")
            file.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            )
            val info = TxtImporter.import(file, File(dir, "chapters"), "utf16")
            assertEquals(1, info.chapters.size)
            assertEquals(
                "第一章 标题\n　　内容。\n",
                File(File(dir, "chapters"), info.chapters[0].file).readText()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stopsParsingWhenCancelled() {
        val source = buildString {
            append("第一章 取消测试\n")
            repeat(100) { append("第").append(it).append("行内容。\n") }
        }
        val dir = Files.createTempDirectory("txt-cancel").toFile()
        try {
            val file = File(dir, "cancel.txt")
            file.writeText(source, Charsets.UTF_8)
            val chaptersDir = File(dir, "chapters")
            var checks = 0
            assertThrows(ImportCancelledException::class.java) {
                TxtImporter.import(file, chaptersDir, "cancel") {
                    checks++
                    checks > 5
                }
            }
            assertTrue(chaptersDir.listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
