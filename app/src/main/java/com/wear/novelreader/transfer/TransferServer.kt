package com.wear.novelreader.transfer

import com.wear.novelreader.data.PublicBook
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TransferServer(
    private val portHint: Int,
    private val uploadDir: File,
    private val booksProvider: () -> List<PublicBook>,
    private val uploadHandler: (File, String) -> UploadResult,
    private val onActiveUploadsChanged: (Int) -> Unit = {},
    private val maxUploadBytes: Long = 80L * 1024L * 1024L
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val activeUploads = AtomicInteger(0)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private var acceptThread: Thread? = null
    private val clientPool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "transfer-client").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    fun start(): Int {
        uploadDir.mkdirs()
        val socket = bind()
        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "transfer-accept").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        clientPool.shutdownNow()
    }

    private fun bind(): ServerSocket {
        if (portHint == 0) {
            return ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
        var last: IOException? = null
        for (port in portHint..(portHint + 10)) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                last = e
            }
        }
        throw last ?: IOException("无法绑定端口")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            clients.add(client)
            clientPool.execute {
                try {
                    handleClient(client)
                } catch (_: Exception) {
                } finally {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 60_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                respond(output, 400, "Bad Request", "text/plain; charset=utf-8", "bad request".toByteArray())
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = try {
                readHeaders(input)
            } catch (_: IOException) {
                respond(output, 400, "Bad Request", "text/plain; charset=utf-8", "headers too large".toByteArray())
                return
            }
            val tooLarge = method == "POST" && path == "/upload" &&
                (headers["content-length"]?.toLongOrNull() ?: 0L) > maxUploadBytes
            if (tooLarge) {
                respond(
                    output,
                    413,
                    "Payload Too Large",
                    "application/json; charset=utf-8",
                    errorJson("文件超过大小限制")
                )
                return
            }
            if (method == "POST" && headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = UploadPage.render(booksProvider())
                    respond(output, 200, "OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
                }
                method == "GET" && path == "/api/books" -> {
                    val json = booksJson(booksProvider())
                    respond(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
                method == "GET" && path == "/health" -> {
                    respond(output, 200, "OK", "text/plain; charset=utf-8", "ok".toByteArray())
                }
                method == "GET" && path == "/favicon.ico" -> {
                    respond(output, 204, "No Content", "text/plain", ByteArray(0))
                }
                method == "POST" && path == "/upload" -> handleUpload(input, output, headers)
                method != "GET" && method != "POST" -> {
                    respond(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "method not allowed".toByteArray())
                }
                else -> {
                    respond(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
                }
            }
        } finally {
            runCatching { output.flush() }
        }
    }

    private fun handleUpload(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val active = activeUploads.incrementAndGet()
        onActiveUploadsChanged(active)
        try {
            handleUploadInternal(input, output, headers)
        } finally {
            onActiveUploadsChanged(activeUploads.decrementAndGet())
        }
    }

    private fun handleUploadInternal(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val boundary = MultipartParser.boundaryOf(headers["content-type"])
        if (boundary == null) {
            respond(output, 400, "Bad Request", "application/json; charset=utf-8", errorJson("缺少 multipart boundary"))
            return
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length != null && length > maxUploadBytes) {
            respond(output, 413, "Payload Too Large", "application/json; charset=utf-8", errorJson("文件超过大小限制"))
            return
        }
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val bodyStream: InputStream = when {
            chunked -> LimitedInputStream(ChunkedInputStream(input), maxUploadBytes)
            length != null -> LimitedInputStream(input, length)
            else -> LimitedInputStream(input, maxUploadBytes)
        }

        val requestDir = File(uploadDir, "req-${UUID.randomUUID()}").also { it.mkdirs() }
        var files: List<MultipartParser.Part> = emptyList()
        try {
            files = MultipartParser.parse(bodyStream, boundary, requestDir, maxUploadBytes)
            if (files.isEmpty()) {
                respond(output, 400, "Bad Request", "application/json; charset=utf-8", errorJson("没有收到文件"))
                return
            }
            val results = files.map { part ->
                try {
                    uploadHandler(part.tempFile!!, part.fileName ?: "未命名.txt")
                } catch (e: Exception) {
                    UploadResult(false, null, e.message ?: "导入失败")
                } finally {
                    part.tempFile?.delete()
                }
            }
            val body = buildString {
                append("{\"ok\":true,\"results\":[")
                results.forEachIndexed { index, result ->
                    if (index > 0) append(',')
                    append('{')
                    append("\"ok\":").append(result.ok).append(',')
                    append("\"title\":").append(jsonString(result.title ?: "")).append(',')
                    append("\"message\":").append(jsonString(result.message))
                    append('}')
                }
                append("]}")
            }
            respond(output, 200, "OK", "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
        } catch (e: MultipartParser.UploadTooLargeException) {
            respond(output, 413, "Payload Too Large", "application/json; charset=utf-8", errorJson("文件超过大小限制"))
        } catch (e: Exception) {
            respond(output, 400, "Bad Request", "application/json; charset=utf-8", errorJson(e.message ?: "上传失败"))
        } finally {
            files.forEach { it.tempFile?.delete() }
            requestDir.deleteRecursively()
        }
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            extraHeaders.forEach { (key, value) ->
                append(key).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var totalBytes = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            totalBytes += line.length + 2
            if (totalBytes > MAX_HEADER_BYTES) throw IOException("请求头过大")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[key] = if (headers.containsKey(key)) headers[key] + ", " + value else value
            if (headers.size > 100) break
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (readAny) out.toString("UTF-8") else null
            readAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 32 * 1024) break
        }
        return out.toString("UTF-8")
    }

    private fun booksJson(books: List<PublicBook>): String {
        return buildString {
            append("{\"ok\":true,\"books\":[")
            books.forEachIndexed { index, book ->
                if (index > 0) append(',')
                append('{')
                append("\"title\":").append(jsonString(book.title)).append(',')
                append("\"author\":").append(jsonString(book.author)).append(',')
                append("\"chapters\":").append(book.chapters).append(',')
                append("\"percent\":").append(book.percent)
                append('}')
            }
            append("]}")
        }
    }

    private fun errorJson(message: String): ByteArray =
        "{\"ok\":false,\"message\":${jsonString(message)}}".toByteArray(Charsets.UTF_8)

    private fun jsonString(value: String): String {
        val out = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private val limit: Long
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            if (count >= limit) throw MultipartParser.UploadTooLargeException()
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (count >= limit) throw MultipartParser.UploadTooLargeException()
            val remaining = (limit - count).coerceAtMost(length.toLong()).toInt()
            val read = delegate.read(buffer, offset, remaining)
            if (read > 0) count += read
            return read
        }
    }

    private inner class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false
        private var needChunkHeader = true

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            if (needChunkHeader) {
                if (!readChunkHeader()) return -1
            }
            if (remaining == 0L) {
                consumeCrlf()
                needChunkHeader = true
                if (!readChunkHeader()) return -1
            }
            val toRead = minOf(length.toLong(), remaining).toInt()
            val read = delegate.read(buffer, offset, toRead)
            if (read < 0) return -1
            remaining -= read
            return read
        }

        private fun readChunkHeader(): Boolean {
            val line = readLine(delegate) ?: return false
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: return false
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(delegate) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            remaining = size
            needChunkHeader = false
            return true
        }

        private fun consumeCrlf() {
            val first = delegate.read()
            if (first == '\r'.code) delegate.read()
        }
    }

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}





