package com.wear.novelreader

import com.wear.novelreader.import.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleanerTest {

    @Test
    fun normalizesLeadingWhitespaceToTwoFullWidthSpaces() {
        val source = "   　　\u00A0\t第一段，内部  空格保留。  "
        assertEquals(
            "　　第一段，内部  空格保留。",
            TextCleaner.cleanParagraph(source)
        )
    }

    @Test
    fun removesBlankLinesAndIndentsEveryParagraph() {
        val source = "　　第一段。\n\n\n第二段。\n \n　第三段。"
        assertEquals(
            "　　第一段。\n　　第二段。\n　　第三段。\n",
            TextCleaner.cleanChapterText(source, null)
        )
    }

    @Test
    fun keepsChapterHeadingUnindented() {
        val source = "第39章 标题\n\n　　正文第一段。\n\n正文第二段。"
        val cleaned = TextCleaner.cleanChapterText(source, "第39章 标题")
        assertEquals(
            "第39章 标题\n　　正文第一段。\n　　正文第二段。\n",
            cleaned
        )
        assertFalse(cleaned.contains("\n\n"))
    }

    @Test
    fun treatsChapterTitleAsHeadingEvenWithoutPattern() {
        val source = "简介\n\n书名：测试小说\n\n作者：测试作者"
        assertEquals(
            "简介\n　　书名：测试小说\n　　作者：测试作者\n",
            TextCleaner.cleanChapterText(source, "简介")
        )
    }

    @Test
    fun joinsWrappedChineseLinesAndKeepsEnglishSpacing() {
        val source = "这是被硬换行\n的中文段落。\n\nThis is a wrapped\nEnglish paragraph."
        assertEquals(
            "　　这是被硬换行的中文段落。\n　　This is a wrapped English paragraph.\n",
            TextCleaner.cleanChapterText(source, null)
        )
    }

    @Test
    fun ignoresBlankParagraph() {
        assertNull(TextCleaner.cleanParagraph(" \t\u3000\u00A0 "))
        assertEquals("", TextCleaner.cleanChapterText("\n\n \n", null))
    }

    @Test
    fun keepsHeadingWithoutIndentInTxtStyle() {
        assertEquals("第1章 标题", TextCleaner.cleanHeading("　第1章 标题　"))
    }
}
