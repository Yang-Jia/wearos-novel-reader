package com.wear.novelreader.import

import com.wear.novelreader.data.Chapter
import java.io.File
import java.util.Locale

class ChapterWriter(
    private val chaptersDir: File,
    private val chunkLimit: Int = 8000
) {
    private val buffer = StringBuilder()
    private val chapters = mutableListOf<Chapter>()
    private var activeTitle: String? = null
    private var chunkInChapter = 0
    private var index = 0

    init {
        chaptersDir.mkdirs()
    }

    fun setChapter(title: String) {
        flushRemainder()
        activeTitle = title.trim().take(80)
        chunkInChapter = 0
    }

    fun appendLine(line: String) {
        buffer.append(line.removePrefix("\uFEFF")).append('\n')
        drain()
    }

    fun appendText(text: String) {
        buffer.append(text)
        drain()
    }

    fun finish(): List<Chapter> {
        flushRemainder()
        activeTitle = null
        chunkInChapter = 0
        return chapters.toList()
    }

    private fun drain() {
        while (buffer.length >= chunkLimit) {
            val cut = findCut()
            val text = buffer.substring(0, cut)
            buffer.delete(0, cut)
            writeChunk(text)
        }
    }

    private fun findCut(): Int {
        val paragraph = buffer.lastIndexOf("\n\n", chunkLimit)
        if (paragraph > chunkLimit / 2) return paragraph + 1
        val line = buffer.lastIndexOf('\n', chunkLimit)
        if (line > chunkLimit / 2) return line + 1
        return chunkLimit
    }

    private fun flushRemainder() {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        writeChunk(text)
    }

    private fun writeChunk(text: String) {
        if (text.isEmpty()) return
        val fallbackTitle = "第${index + 1}节"
        val base = activeTitle
        val title = if (base.isNullOrBlank()) {
            fallbackTitle
        } else {
            if (chunkInChapter == 0) base else "$base（${chunkInChapter + 1}）"
        }
        val fileName = String.format(Locale.US, "%04d.txt", index + 1)
        val file = File(chaptersDir, fileName)
        file.outputStream().buffered(32 * 1024).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        }
        chapters.add(Chapter(title = title, file = fileName, chars = text.length))
        index++
        chunkInChapter++
    }
}

