package com.wear.novelreader

import com.wear.novelreader.data.BookMeta
import com.wear.novelreader.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class BookMetaTest {

    @Test
    fun cachesChapterOffsetsForPercentLookups() {
        val meta = BookMeta(
            id = "book",
            title = "测试",
            author = "",
            format = "txt",
            addedAt = 1L,
            chapters = listOf(
                Chapter("第一章", "0001.txt", 10),
                Chapter("第二章", "0002.txt", 20),
                Chapter("第三章", "0003.txt", 30)
            )
        )

        assertEquals(60, meta.totalChars)
        assertEquals(0, meta.charsBefore(0))
        assertEquals(10, meta.charsBefore(1))
        assertEquals(30, meta.charsBefore(2))
        assertEquals(60, meta.charsBefore(3))
        assertEquals(60, meta.charsBefore(99))
        assertEquals(0, meta.charsBefore(-1))
        assertEquals(50, meta.percentAt(2, 0))
        assertEquals(91, meta.percentAt(2, 25))
        assertEquals(100, meta.percentAt(2, 30))
    }

    @Test
    fun emptyBookHasNoChars() {
        val meta = BookMeta("empty", "空书", "", "txt", 1L, emptyList())
        assertEquals(0, meta.totalChars)
        assertEquals(0, meta.charsBefore(10))
        assertEquals(0, meta.percentAt(0, 100))
    }
}
