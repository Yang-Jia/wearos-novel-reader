package com.wear.novelreader

import com.wear.novelreader.import.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectorTest {

    @Test
    fun detectsUtf8WithBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "中文".toByteArray(Charsets.UTF_8)
        val detected = EncodingDetector.detect(bytes)
        assertEquals("UTF-8", detected.charset.name())
        assertEquals(3, detected.bomLength)
    }

    @Test
    fun detectsUtf8WithoutBom() {
        val detected = EncodingDetector.detect("第一章 中文内容".toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", detected.charset.name())
    }

    @Test
    fun detectsUtf16Bom() {
        val text = "第一章 测试"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals("UTF-16LE", EncodingDetector.detect(le).charset.name())
        assertEquals("UTF-16BE", EncodingDetector.detect(be).charset.name())
    }

    @Test
    fun detectsGbkAsGb18030() {
        val gbk = Charset.forName("GBK")
        val detected = EncodingDetector.detect("第一章 中文小说内容。".toByteArray(gbk))
        val name = detected.charset.name()
        assertTrue(name == "GB18030" || name == "GBK")
        val decoded = String("第一章 中文小说内容。".toByteArray(gbk), detected.charset)
        assertEquals("第一章 中文小说内容。", decoded)
    }

    @Test
    fun asciiIsAmbiguousAndFallsBackToGb() {
        val name = EncodingDetector.detect("Chapter 1".toByteArray()).charset.name()
        assertTrue(name == "GB18030" || name == "GBK")
    }

    @Test
    fun asciiPrefixDoesNotHideGbkBody() {
        val ascii = "Chapter 1\n".toByteArray(Charsets.US_ASCII)
        val gbkBody = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val detected = EncodingDetector.detect(ascii + gbkBody)
        val name = detected.charset.name()
        assertTrue(name == "GB18030" || name == "GBK")
        assertEquals("中文", String(gbkBody, detected.charset))
    }

    @Test
    fun detectsUtf16LeWithoutBom() {
        val bytes = "Chapter 1 中文".toByteArray(Charsets.UTF_16LE)
assertEquals("UTF-16LE", EncodingDetector.detect(bytes).charset.name())
    }
}
