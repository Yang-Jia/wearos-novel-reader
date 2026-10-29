package com.wear.novelreader.reader

data class ChapterRange(val start: Int, val end: Int)

object ChapterJumpPlan {

    fun levels(totalChapters: Int): List<Int> {
        if (totalChapters <= 1) return listOf(1)
        var step = 1
        while (step <= totalChapters / 10) {
            step *= 10
        }
        val levels = mutableListOf<Int>()
        while (step >= 1) {
            levels.add(step)
            step /= 10
        }
        return levels
    }

    fun ranges(start: Int, end: Int, step: Int): List<ChapterRange> {
        if (step <= 0 || start > end) return emptyList()
        val result = mutableListOf<ChapterRange>()
        var current = start
        while (current <= end) {
            val rangeEnd = minOf(current + step - 1, end)
            result.add(ChapterRange(current, rangeEnd))
            current = rangeEnd + 1
        }
        return result
    }

    fun childCount(range: ChapterRange, nextStep: Int): Int {
        if (nextStep <= 0 || range.start > range.end) return 0
        val length = range.end - range.start + 1
        return (length + nextStep - 1) / nextStep
    }
}
