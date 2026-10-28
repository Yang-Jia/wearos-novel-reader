package com.wear.novelreader.import

object TextCleaner {

    const val PARAGRAPH_INDENT = "\u3000\u3000"

    private val paragraphSeparator = Regex("\\n[ \\t\\u3000\\u00A0]*\\n")

    fun cleanParagraph(raw: String): String? {
        val cleaned = cleanInline(raw)
        if (cleaned.isEmpty()) return null
        return PARAGRAPH_INDENT + cleaned
    }

    fun cleanHeading(raw: String): String? {
        val cleaned = cleanInline(raw)
        return cleaned.ifEmpty { null }
    }

    fun cleanChapterText(raw: String, chapterTitle: String?): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val builder = StringBuilder()
        normalized.split(paragraphSeparator).forEach { block ->
            val paragraph = joinWrappedLines(block)
            if (paragraph.isEmpty()) return@forEach
            val line = if (isHeadingParagraph(paragraph, chapterTitle)) {
                cleanHeading(paragraph)
            } else {
                cleanParagraph(paragraph)
            }
            if (line != null) builder.append(line).append('\n')
        }
        return builder.toString()
    }

    fun cleanInline(raw: String): String {
        return raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim { char ->
                char == ' ' || char == '\t' || char == '\n' ||
                    char == '\u3000' || char == '\u00A0' || char == '\uFEFF'
            }
    }

    private fun isHeadingParagraph(paragraph: String, chapterTitle: String?): Boolean {
        val value = paragraph.trim()
        if (headingMatches(value)) return true
        return chapterTitle != null && value == chapterTitle.trim()
    }

    private fun headingMatches(value: String): Boolean {
        return ChapterSplitter.isHeading(value)
    }

    private fun joinWrappedLines(block: String): String {
        val lines = block.split('\n')
            .map { cleanInline(it) }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val builder = StringBuilder(lines[0])
        for (index in 1 until lines.size) {
            val previous = builder.last()
            val next = lines[index].first()
            if (isAsciiWord(previous) && isAsciiWord(next)) {
                builder.append(' ')
            }
            builder.append(lines[index])
        }
        return cleanInline(builder.toString())
    }

    private fun isAsciiWord(char: Char): Boolean {
        return char.code < 128 && char.isLetterOrDigit()
    }
}
