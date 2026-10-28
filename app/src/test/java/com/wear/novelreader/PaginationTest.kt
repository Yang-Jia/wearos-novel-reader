package com.wear.novelreader

import com.wear.novelreader.reader.Pagination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {

    @Test
    fun splitsLinesIntoPagesWithoutLosingAnyLine() {
        val tops = intArrayOf(0, 10, 21, 33, 46, 60, 75)
        val bottoms = intArrayOf(10, 21, 33, 46, 60, 75, 90)
        val pages = Pagination.computeRanges(tops, bottoms, 40)
        assertTrue(pages.isNotEmpty())
        var lastEnd = 0
        pages.forEach { page ->
            assertEquals(lastEnd, page.startLine)
            assertTrue(page.endLine > page.startLine)
            lastEnd = page.endLine
        }
        assertEquals(tops.size, lastEnd)
    }

    @Test
    fun alwaysFitsAtLeastOneLine() {
        val pages = Pagination.computeRanges(intArrayOf(0), intArrayOf(100), 10)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startLine)
        assertEquals(1, pages[0].endLine)
    }

    @Test
    fun emptyLayoutHasNoPages() {
        assertTrue(Pagination.computeRanges(intArrayOf(), intArrayOf(), 100).isEmpty())
        assertTrue(Pagination.computeRanges(intArrayOf(0), intArrayOf(10), 0).isEmpty())
    }
}
