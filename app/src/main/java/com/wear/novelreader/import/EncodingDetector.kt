package com.wear.novelreader.import

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object EncodingDetector {

    data class Detected(val charset: Charset, val bomLength: Int)

    fun detect(prefix: ByteArray): Detected {
        if (prefix.size >= 3 &&
            prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte()
        ) {
            return Detected(Charsets.UTF_8, 3)
        }
        if (prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte()) {
            return Detected(Charsets.UTF_16LE, 2)
        }
        if (prefix.size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte()) {
            return Detected(Charsets.UTF_16BE, 2)
        }

        val zeroOdd = countZeros(prefix, 1)
        val zeroEven = countZeros(prefix, 0)
        val pairs = (prefix.size / 2).coerceAtLeast(1)
        if (zeroOdd * 4 > pairs && zeroEven * 20 < pairs) {
            return Detected(Charsets.UTF_16LE, 0)
        }
        if (zeroEven * 4 > pairs && zeroOdd * 20 < pairs) {
            return Detected(Charsets.UTF_16BE, 0)
        }

        val sample = trimIncompleteUtf8(prefix)
        if (hasMultibyteUtf8(sample) && isValidUtf8(sample)) {
            return Detected(Charsets.UTF_8, 0)
        }

        if (prefix.size >= 4 && prefix.size % 2 == 0) {
            val text16Le = String(prefix, Charsets.UTF_16LE)
            val text16Be = String(prefix, Charsets.UTF_16BE)
            val gb = charsetOrNull("GB18030")?.let { String(prefix, it) } ?: ""
            val leScore = textQuality(text16Le)
            val beScore = textQuality(text16Be)
            val gbScore = textQuality(gb)
            val best = maxOf(leScore, beScore)
            val gbHasReplacement = gb.contains('\uFFFD')
            if (best >= 2 && (best > gbScore || (best >= gbScore && gbHasReplacement))) {
                return Detected(
                    if (leScore >= beScore) Charsets.UTF_16LE else Charsets.UTF_16BE,
                    0
                )
            }
        }

        val candidates = listOfNotNull(
            charsetOrNull("GB18030"),
            charsetOrNull("GBK")
        )
        val fallback = candidates.minByOrNull { replacementRatio(sample, it) }
        return Detected(fallback ?: Charsets.UTF_8, 0)
    }

    private fun textQuality(text: String): Int {
        var score = 0
        for (ch in text) {
            score += when {
                ch.code in 0x4E00..0x9FFF -> 2
                ch.code in 0x3400..0x4DBF -> 1
                ch == '\uFFFD' -> -4
                ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> -3
                ch.code in 0x20..0x7E -> 1
                else -> 0
            }
        }
        return score
    }

    private fun hasMultibyteUtf8(bytes: ByteArray): Boolean {
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b and 0xE0 == 0xC0 || b and 0xF0 == 0xE0 || b and 0xF8 == 0xF0) {
                return true
            }
        }
        return false
    }

    private fun replacementRatio(bytes: ByteArray, charset: Charset): Double {
        if (bytes.isEmpty()) return 0.0
        val text = try {
            String(bytes, charset)
        } catch (_: Exception) {
            return 1.0
        }
        var bad = 0
        for (ch in text) {
            if (ch == '\uFFFD') bad++
        }
        return bad.toDouble() / text.length.coerceAtLeast(1)
    }

    private fun trimIncompleteUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        var end = bytes.size
        var i = bytes.size - 1
        var back = 0
        while (i >= 0 && back < 4) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                back++
                i--
            } else {
                break
            }
        }
        if (i >= 0) {
            val b = bytes[i].toInt() and 0xFF
            val expected = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            if (bytes.size - i < expected) end = i
        }
        return bytes.copyOf(end)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun countZeros(bytes: ByteArray, offset: Int): Int {
        var count = 0
        var i = offset
        while (i < bytes.size) {
            if (bytes[i] == 0.toByte()) count++
            i += 2
        }
        return count
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }
}
