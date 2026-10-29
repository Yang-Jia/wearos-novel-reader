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
    private val chapterStarts: IntArray by lazy {
        val starts = IntArray(chapters.size + 1)
        var total = 0
        for (i in chapters.indices) {
            starts[i] = total
            total += chapters[i].chars
        }
        starts[chapters.size] = total
        starts
    }

    val totalChars: Int get() = chapterStarts.last()

    fun charsBefore(index: Int): Int {
        return chapterStarts[index.coerceIn(0, chapters.size)]
    }

    fun percentAt(chapterIndex: Int, charOffset: Int): Int {
        val total = totalChars
        if (total <= 0) return 0
        val before = charsBefore(chapterIndex)
        val value = (before + charOffset).toFloat() / total
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
