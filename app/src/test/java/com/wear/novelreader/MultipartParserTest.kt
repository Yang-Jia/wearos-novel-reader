package com.wear.novelreader

import com.wear.novelreader.transfer.MultipartParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class MultipartParserTest {

    @Test
    fun parsesMultipleFilesAndFields() {
        val dir = Files.createTempDirectory("multipart").toFile()
        try {
            val boundary = "----test-boundary"
            val body = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"小说.txt\"\r\n")
                append("Content-Type: text/plain\r\n\r\n")
                append("第一章 内容\r\n")
                append("--not-a-boundary\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''%E4%B9%A6.epub\r\n")
                append("Content-Type: application/epub+zip\r\n\r\n")
                append("PK\u0003\u0004binary\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"note\"\r\n\r\n")
                append("hello\r\n")
                append("--$boundary--\r\n")
            }
            val parts = MultipartParser.parse(
                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
                boundary,
                dir
            )
            assertEquals(2, parts.size)
            assertEquals("小说.txt", parts[0].fileName)
            assertNotNull(parts[0].tempFile)
            assertEquals(
                "第一章 内容\r\n--not-a-boundary",
                parts[0].tempFile!!.readText()
            )
            assertEquals("书.epub", parts[1].fileName)
            assertEquals("PK\u0003\u0004binary", parts[1].tempFile!!.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresNonFileParts() {
        val dir = Files.createTempDirectory("multipart-field").toFile()
        try {
            val boundary = "xyz"
            val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"token\"\r\n\r\n" +
                "abc\r\n" +
                "--$boundary--\r\n"
            val parts = MultipartParser.parse(
                ByteArrayInputStream(body.toByteArray()),
                boundary,
                dir
            )
            assertEquals(0, parts.size)
            assertNull(dir.listFiles()?.firstOrNull())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedBody() {
        val dir = Files.createTempDirectory("multipart-bad").toFile()
        try {
            val boundary = "abc"
            val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n" +
                "content without final boundary"
            MultipartParser.parse(ByteArrayInputStream(body.toByteArray()), boundary, dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
