package com.wear.novelreader.reader

import android.text.Layout

data class PageLines(val startLine: Int, val endLine: Int)

object Pagination {

    fun compute(layout: Layout, pageHeight: Int): List<PageLines> {
        if (layout.lineCount <= 0 || pageHeight <= 0) return emptyList()
        val tops = IntArray(layout.lineCount) { layout.getLineTop(it) }
        val bottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
        return computeRanges(tops, bottoms, pageHeight)
    }

    fun computeRanges(lineTops: IntArray, lineBottoms: IntArray, pageHeight: Int): List<PageLines> {
        if (lineTops.isEmpty() || lineTops.size != lineBottoms.size || pageHeight <= 0) {
            return emptyList()
        }
        val pages = mutableListOf<PageLines>()
        var start = 0
        while (start < lineTops.size) {
            var end = start
            while (end < lineTops.size &&
                lineBottoms[end] - lineTops[start] <= pageHeight
            ) {
                end++
            }
            if (end == start) end = start + 1
            pages.add(PageLines(start, end))
            start = end
        }
        return pages
    }
}
