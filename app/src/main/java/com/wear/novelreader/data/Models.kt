package com.wear.novelreader.data

data class Chapter(
    val title: String,
    val file: String,
    val chars: Int
)

data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val addedAt: Long,
    val chapters: List<Chapter>
) {
    val totalChars: Int get() = chapters.sumOf { it.chars }

    fun charsBefore(index: Int): Int {
        var sum = 0
        for (i in 0 until index.coerceAtMost(chapters.size)) {
            sum += chapters[i].chars
        }
        return sum
    }

    fun percentAt(chapterIndex: Int, charOffset: Int): Int {
        if (totalChars <= 0) return 0
        val before = charsBefore(chapterIndex)
        val value = (before + charOffset).toFloat() / totalChars
        return (value * 100).toInt().coerceIn(0, 100)
    }
}

data class BookProgress(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val updatedAt: Long = 0L
) {
    val hasProgress: Boolean get() = updatedAt > 0L
}

data class BookItem(
    val meta: BookMeta,
    val progress: BookProgress
) {
    val percent: Int get() = meta.percentAt(progress.chapterIndex, progress.charOffset)
}

data class ImportedInfo(
    val title: String,
    val author: String,
    val format: String,
    val chapters: List<Chapter>
)

data class ImportedBook(
    val book: BookMeta,
    val chapterCount: Int
)

data class PublicBook(
    val title: String,
    val author: String,
    val chapters: Int,
    val percent: Int
)
