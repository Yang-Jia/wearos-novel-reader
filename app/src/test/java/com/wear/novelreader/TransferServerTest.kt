package com.wear.novelreader

import com.wear.novelreader.data.PublicBook
import com.wear.novelreader.transfer.TransferServer
import com.wear.novelreader.transfer.UploadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TransferServerTest {

    @Test
    fun servesPageAndHandlesUpload() {
        val dir = Files.createTempDirectory("server-test").toFile()
        val received = AtomicReference<ByteArray>()
        val activeStates = Collections.synchronizedList(mutableListOf<Int>())
        val zeroActive = CountDownLatch(1)
        val server = TransferServer(
            portHint = 0,
            uploadDir = File(dir, "uploads"),
            booksProvider = {
                listOf(PublicBook("已有书", "作者", 12, 34))
            },
            uploadHandler = { file, name ->
                received.set(file.readBytes())
                UploadResult(true, name.substringBeforeLast('.'), "ok")
            },
            onActiveUploadsChanged = { active ->
                activeStates.add(active)
                if (active == 0) zeroActive.countDown()
            }
        )
        try {
            val port = server.start()
            assertTrue(port > 0)

            val page = httpGet(port, "/")
            assertTrue(page.contains("腕上书"))
            assertTrue(page.contains("已有书"))

            val booksJson = httpGet(port, "/api/books")
            assertTrue(booksJson.contains("\"title\":\"已有书\""))
            assertTrue(booksJson.contains("\"percent\":34"))

            val boundary = "----junit-boundary"
            val uploadBody = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"测试.txt\"\r\n")
                append("Content-Type: text/plain\r\n\r\n")
                append("第一章 测试\r\n正文内容\r\n")
                append("--$boundary--\r\n")
            }.toByteArray(Charsets.UTF_8)
            val response = httpPost(port, "/upload", boundary, uploadBody)
            assertTrue(response.contains("\"ok\":true"))
            assertNotNull(received.get())
            assertEquals(
                "第一章 测试\r\n正文内容",
                String(received.get()!!, Charsets.UTF_8)
            )
            assertEquals(1, activeStates.first())
            assertTrue(zeroActive.await(1, TimeUnit.SECONDS))
            assertEquals(0, activeStates.last())
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun acceptsChunkedUpload() {
        val dir = Files.createTempDirectory("server-chunked").toFile()
        val received = AtomicReference<String>()
        val server = TransferServer(
            portHint = 0,
            uploadDir = File(dir, "uploads"),
            booksProvider = { emptyList() },
            uploadHandler = { file, name ->
                received.set(file.readText() + "|" + name)
                UploadResult(true, "ok", "ok")
            }
        )
        try {
            val port = server.start()
            val boundary = "chunk-boundary"
            val multipart = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.txt\"\r\n\r\n")
                append("分块上传内容")
                append("\r\n--$boundary--\r\n")
            }.toByteArray(Charsets.UTF_8)

            val request = ByteArrayOutputStream()
            request.write(
                ("POST /upload HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            request.write((Integer.toHexString(multipart.size) + "\r\n").toByteArray())
            request.write(multipart)
            request.write("\r\n0\r\n\r\n".toByteArray())

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                val response = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                ).readText()
                assertTrue(response.contains("200 OK"))
                assertTrue(response.contains("\"ok\":true"))
            }
            assertEquals("分块上传内容|chunk.txt", received.get())
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    private fun httpGet(port: Int, path: String): String {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(port: Int, path: String, boundary: String, body: ByteArray): String {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return try {
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }
}


