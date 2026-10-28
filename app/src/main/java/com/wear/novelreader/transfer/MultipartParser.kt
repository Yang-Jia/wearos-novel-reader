package com.wear.novelreader.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.UUID

object MultipartParser {

    data class Part(
        val fieldName: String,
        val fileName: String?,
        val tempFile: File?
    )

    fun boundaryOf(contentType: String?): String? {
        if (contentType == null) return null
        val match = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
            .find(contentType) ?: return null
        return (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
    }

    fun parse(
        input: InputStream,
        boundary: String,
        tempDir: File,
        maxBytes: Long = 100L * 1024L * 1024L
    ): List<Part> {
        tempDir.mkdirs()
        val parts = mutableListOf<Part>()
        val marker = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val created = mutableListOf<File>()
        try {
            var line = readLine(input)
            while (line != null && line.isEmpty()) line = readLine(input)
            if (line == null) throw IllegalArgumentException("multipart 格式错误")
            if (!line.startsWith("--$boundary")) throw IllegalArgumentException("multipart boundary 不匹配")
            if (line.endsWith("--")) return emptyList()

            var total = 0L
            while (true) {
                val headers = readHeaders(input)
                val disposition = headers["content-disposition"] ?: ""
                val fieldName = param(disposition, "name") ?: ""
                val fileName = param(disposition, "filename")
                val hasFile = !fileName.isNullOrBlank()
                val file = if (hasFile) {
                    File(tempDir, "part-${UUID.randomUUID()}.upload").also { created.add(it) }
                } else {
                    null
                }

                val sink = file?.let { java.io.BufferedOutputStream(it.outputStream(), 32 * 1024) }
                val counter = CountingOutputStream(sink, maxBytes)
                val found = try {
                    BoundaryReader(input, marker).transferTo(if (hasFile) counter else null)
                } finally {
                    sink?.flush()
                    sink?.close()
                }
                if (!found) throw IllegalArgumentException("multipart 数据不完整")
                total += counter.count
                if (total > maxBytes) throw UploadTooLargeException()

                if (hasFile && file != null) {
                    parts.add(Part(fieldName, sanitize(fileName ?: ""), file))
                }

                val b1 = input.read()
                val b2 = input.read()
                if (b1 < 0) break
                if (b1 == '-'.code && b2 == '-'.code) {
                    readLine(input)
                    break
                }
                if (!(b1 == '\r'.code && b2 == '\n'.code)) {
                    break
                }
            }
            return parts
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    class UploadTooLargeException : RuntimeException("上传内容超过限制")

    private fun sanitize(name: String): String {
        return name.substringAfterLast('/').substringAfterLast('\\').trim().take(120)
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }
        return headers
    }

    private fun param(header: String, name: String): String? {
        val extended = Regex(
            "(?i)(?:^|;)\\s*" + Regex.escape(name) + "\\*\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))"
        ).find(header)
        if (extended != null) {
            val value = extended.groupValues[1].ifBlank { extended.groupValues[2] }.trim()
            return decodeExtended(value)
        }
        val plain = Regex(
            "(?i)(?:^|;)\\s*" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))"
        ).find(header)
        if (plain != null) {
            return plain.groupValues[1].ifBlank { plain.groupValues[2] }.trim()
        }
        return null
    }

    private fun decodeExtended(value: String): String {
        val split = value.split("''", limit = 2)
        return try {
            if (split.size == 2) {
                val charset = split[0].ifBlank { "UTF-8" }
                URLDecoder.decode(split[1].replace("+", "%2B"), charset)
            } else {
                URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            }
        } catch (_: Exception) {
            value
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var readAny = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (readAny) out.toString("UTF-8") else null
            }
            readAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16 * 1024) break
        }
        return out.toString("UTF-8")
    }

    private class CountingOutputStream(
        private val delegate: OutputStream?,
        private val maxBytes: Long
    ) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            count++
            if (count > maxBytes) throw UploadTooLargeException()
            delegate?.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
            if (count > maxBytes) throw UploadTooLargeException()
            delegate?.write(b, off, len)
        }

        override fun flush() {
            delegate?.flush()
        }

        override fun close() {
            delegate?.close()
        }
    }

    private class BoundaryReader(
        private val input: InputStream,
        private val marker: ByteArray
    ) {
        fun transferTo(output: OutputStream?): Boolean {
            if (marker.isEmpty()) return false
            val window = ByteArray(marker.size)
            var count = 0
            while (true) {
                val value = input.read()
                if (value < 0) return false
                val b = value.toByte()
                if (count < marker.size) {
                    window[count++] = b
                    if (count == marker.size && b == marker[marker.size - 1] &&
                        window.contentEquals(marker)
                    ) {
                        return true
                    }
                } else {
                    output?.write(window[0].toInt())
                    System.arraycopy(window, 1, window, 0, marker.size - 1)
                    window[marker.size - 1] = b
                    if (b == marker[marker.size - 1] && window.contentEquals(marker)) {
                        return true
                    }
                }
            }
        }
    }
}

