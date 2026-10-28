package com.wear.novelreader.util

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object FileUtil {

    fun readText(file: File, charset: Charset = Charsets.UTF_8): String {
        return file.readText(charset)
    }

    fun writeText(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        writeBytes(file, text.toByteArray(charset))
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temp.delete()
        }
    }

    fun deleteRecursively(file: File, isCancelled: () -> Boolean = { false }) {
        if (isCancelled()) return
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it, isCancelled) }
        }
        file.delete()
    }

    fun sanitizeFileName(name: String, fallback: String = "novel.txt"): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim('.', ' ')
        return if (cleaned.isBlank()) fallback else cleaned.take(120)
    }

    fun displayNameFromFileName(name: String): String {
        return name.substringBeforeLast('.').trim().ifBlank { name }
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
