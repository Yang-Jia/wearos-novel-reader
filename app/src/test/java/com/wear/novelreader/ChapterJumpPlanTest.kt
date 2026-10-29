package com.wear.novelreader

import com.wear.novelreader.reader.ChapterJumpPlan
import com.wear.novelreader.reader.ChapterRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterJumpPlanTest {

    @Test
    fun choosesLevelsFromCurrentChapterCount() {
        assertEquals(listOf(100, 10, 1), ChapterJumpPlan.levels(893))
        assertEquals(listOf(1000, 100, 10, 1), ChapterJumpPlan.levels(1000))
        assertEquals(listOf(10000, 1000, 100, 10, 1), ChapterJumpPlan.levels(40000))
        assertEquals(listOf(1), ChapterJumpPlan.levels(1))
    }

    @Test
    fun splitsRangesAndKeepsLastPartialRange() {
        val ranges = ChapterJumpPlan.ranges(1, 893, 100)
        assertEquals(9, ranges.size)
        assertEquals(ChapterRange(1, 100), ranges.first())
        assertEquals(ChapterRange(801, 893), ranges.last())
    }

    @Test
    fun buildsExpectedNestedRangesFor321() {
        val hundreds = ChapterJumpPlan.ranges(301, 400, 10)
        assertEquals(10, hundreds.size)
        assertEquals(ChapterRange(321, 330), hundreds[2])

        val leaves = ChapterJumpPlan.ranges(321, 330, 1)
        assertEquals(10, leaves.size)
        assertEquals(ChapterRange(321, 321), leaves.first())
        assertEquals(ChapterRange(330, 330), leaves.last())
    }

    @Test
    fun countsChildRanges() {
        assertEquals(10, ChapterJumpPlan.childCount(ChapterRange(301, 400), 10))
        assertEquals(10, ChapterJumpPlan.childCount(ChapterRange(801, 893), 10))
        assertEquals(10, ChapterJumpPlan.childCount(ChapterRange(321, 330), 1))
    }

    @Test
    fun emptyInputsAreSafe() {
        assertEquals(emptyList<ChapterRange>(), ChapterJumpPlan.ranges(1, 10, 0))
        assertEquals(emptyList<ChapterRange>(), ChapterJumpPlan.ranges(10, 1, 10))
        assertEquals(0, ChapterJumpPlan.childCount(ChapterRange(10, 1), 10))
    }
}
