package com.wear.novelreader.import

import com.wear.novelreader.data.ImportedInfo
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset

object TxtImporter {

    private const val PREFIX_BYTES = 512 * 1024
    private const val PREFACE_LIMIT = 800

    fun import(
        file: File,
        chaptersDir: File,
        fallbackTitle: String,
        chunkLimit: Int = 8000,
        isCancelled: () -> Boolean = { false }
    ): ImportedInfo {
        if (isCancelled()) throw ImportCancelledException()
        if (file.length() == 0L) throw IOException("文件为空")
        val prefix = readPrefix(file)
        val detected = EncodingDetector.detect(prefix)
        val writer = ChapterWriter(chaptersDir, chunkLimit)

        var title = fallbackTitle
        var author = ""
        var headingSeen = false
        var started = false
        var prefaceStarted = false
        var lineNo = 0
        val preface = StringBuilder()

        FileInputStream(file).use { raw ->
            skipFully(raw, detected.bomLength.toLong())
            BufferedReader(InputStreamReader(raw, detected.charset)).use { reader ->
                while (true) {
                    if (isCancelled()) throw ImportCancelledException()
                    val rawLine = reader.readLine() ?: break
                    lineNo++
                    val line = if (lineNo == 1) rawLine.removePrefix("\uFEFF") else rawLine
                    val trimmed = line.trim()

                    if (!headingSeen && !started && lineNo <= 160) {
                        val metadata = ChapterSplitter.parseMetadata(trimmed)
                        if (metadata != null) {
                            when (metadata.first) {
                                "title" -> if (metadata.second.isNotBlank()) title = cleanTitle(metadata.second)
                                "author" -> if (metadata.second.isNotBlank()) author = metadata.second
                            }
                            continue
                        }
                        val bracket = Regex("^《(.{1,60}?)》$").find(trimmed)
                        if (bracket != null) {
                            val candidate = bracket.groupValues[1].trim()
                            if (candidate.isNotBlank() && title == fallbackTitle) {
                                title = candidate
                                continue
                            }
                        }
                    }

                    if (ChapterSplitter.isHeading(line)) {
                        val heading = ChapterSplitter.headingTitle(line)
                        val cleanedHeading = TextCleaner.cleanHeading(line)
                        if (!headingSeen && (prefaceStarted || preface.isNotEmpty())) {
                            if (!prefaceStarted) {
                                writer.setChapter("简介")
                                writer.appendText(preface.toString())
                                preface.setLength(0)
                                prefaceStarted = true
                            }
                            writer.setChapter(heading)
                            cleanedHeading?.let { writer.appendLine(it) }
                        } else {
                            writer.setChapter(heading)
                            cleanedHeading?.let { writer.appendLine(it) }
                        }
                        headingSeen = true
                        started = true
                    } else {
                        if (trimmed.isEmpty()) continue
                        val cleaned = TextCleaner.cleanParagraph(line)
                        if (!headingSeen) {
                            if (prefaceStarted) {
                                cleaned?.let { writer.appendLine(it) }
                            } else {
                                cleaned?.let {
                                    preface.append(it).append('\n')
                                    if (preface.length >= PREFACE_LIMIT) {
                                        writer.setChapter("简介")
                                        writer.appendText(preface.toString())
                                        preface.setLength(0)
                                        prefaceStarted = true
                                    }
                                }
                            }
                            started = true
                        } else {
                            cleaned?.let { writer.appendLine(it) }
                        }
                    }
                }
            }
        }

        if (!headingSeen && !prefaceStarted && preface.isNotEmpty()) {
            writer.appendText(preface.toString())
        }
        val chapters = writer.finish()
        if (chapters.isEmpty()) throw IOException("没有解析到任何文字内容")
        return ImportedInfo(
            title = title.ifBlank { fallbackTitle },
            author = author.trim(),
            format = "txt",
            chapters = chapters
        )
    }

    private fun cleanTitle(value: String): String {
        return value.trim().removeSurrounding("《", "》").removeSurrounding("\"", "").trim()
    }

    private fun readPrefix(file: File): ByteArray {
        val size = minOf(file.length(), PREFIX_BYTES.toLong()).toInt()
        val buffer = ByteArray(size)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == size) buffer else buffer.copyOf(offset)
        }
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    fun charsetOf(file: File): Charset {
        return EncodingDetector.detect(readPrefix(file)).charset
    }
}
