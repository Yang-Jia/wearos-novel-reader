package com.wear.novelreader.import

object ChapterSplitter {

    private val numberedHeadingPattern = Regex(
        "^(?:第[0-9０-９零〇一二三四五六七八九十百千万两]+[章节回卷部集篇]|" +
            "(?i:chapter)\\s+(?:[0-9]+|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve))" +
            ".*$"
    )

    private val englishChapterPattern = Regex(
        "^chapter\\s+(?:[0-9]+|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)" +
            "(?:\\s+.*)?$",
        RegexOption.IGNORE_CASE
    )

    private val keywordHeadingPattern = Regex(
        "^(?:序章|序言|序幕|序|楔子|引子|前言|正文|后记|尾声|终章|大结局|番外[一二三四五六七八九十]*)" +
            "(?:[\\s:：、.．\\-—].*)?$"
    )

    private val metadataPattern = Regex(
        "^\\s*(书名|作者|作\\s*者|title|author|Title|Author)\\s*[:：]\\s*(.+?)\\s*$"
    )

    fun isHeading(line: String): Boolean {
        val trimmed = line.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return false
        if (trimmed.contains('。') || trimmed.endsWith('.')) return false
        return numberedHeadingPattern.matches(trimmed) ||
            englishChapterPattern.matches(trimmed) ||
            keywordHeadingPattern.matches(trimmed)
    }

    fun headingTitle(line: String): String = line.trim().removePrefix("\uFEFF").trim()

    fun parseMetadata(line: String): Pair<String, String>? {
        val match = metadataPattern.find(line) ?: return null
        val key = match.groupValues[1]
        val value = match.groupValues[2].trim()
        if (value.isEmpty()) return null
        return when (key.replace("\\s".toRegex(), "")) {
            "书名", "title", "Title" -> "title" to value
            "作者", "author", "Author" -> "author" to value
            else -> null
        }
    }
}
