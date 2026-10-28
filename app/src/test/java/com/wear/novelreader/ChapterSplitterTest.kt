package com.wear.novelreader

import com.wear.novelreader.import.ChapterSplitter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    @Test
    fun recognizesHeadingsWithoutSeparators() {
        assertTrue(ChapterSplitter.isHeading("第一章风起"))
        assertTrue(ChapterSplitter.isHeading("第一章 风起"))
        assertTrue(ChapterSplitter.isHeading("序"))
        assertTrue(ChapterSplitter.isHeading("番外一"))
println("REGEX=" + Regex("(?i:chapter)\\\\s+one").matches("Chapter One"))
assertTrue(ChapterSplitter.isHeading("Chapter One"))
        assertFalse(ChapterSplitter.isHeading("这是一句普通正文。"))
    }
}
