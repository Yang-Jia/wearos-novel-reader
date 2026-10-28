package com.wear.novelreader

import com.wear.novelreader.import.EpubImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImporterTest {

    @Test
    fun readsMetadataSpineAndText() {
        val dir = Files.createTempDirectory("epub-test").toFile()
        try {
            val epub = File(dir, "book.epub")
            createEpub(epub)
            val info = EpubImporter.import(epub, File(dir, "chapters"), "fallback")

            assertEquals("测试书", info.title)
            assertEquals("作者甲", info.author)
            assertEquals(2, info.chapters.size)
            assertEquals("第一章 起点", info.chapters[0].title)
            assertEquals("第二章 终点", info.chapters[1].title)

            val first = File(File(dir, "chapters"), info.chapters[0].file).readText()
            val second = File(File(dir, "chapters"), info.chapters[1].file).readText()
            assertTrue(first.startsWith("第一章 起点\n　　"))
            assertTrue(first.contains("　　你好 & 世界。"))
            assertTrue(first.contains("　　第二段换行"))
            assertFalse(first.contains("\n\n"))
            assertFalse(first.contains("<p>"))
            assertTrue(second.startsWith("第二章 终点\n　　"))
            assertTrue(second.contains("　　结束。"))
            assertFalse(second.contains("\n\n"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = Exception::class)
    fun rejectsInvalidZip() {
        val dir = Files.createTempDirectory("epub-invalid").toFile()
        try {
            val file = File(dir, "bad.epub")
            file.writeText("not a zip")
            EpubImporter.import(file, File(dir, "chapters"), "bad")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>测试书</dc:title>
                    <dc:creator>作者甲</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="toc" href="table-of-contents.html" media-type="application/xhtml+xml"/>
                    <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/ch%202.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="nav"/>
                    <itemref idref="toc"/>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>目录</title></head>
                  <body><nav><ol><li>第一章</li><li>第二章</li></ol></nav></body>
                </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/table-of-contents.html"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>目录</title></head>
                  <body><p>第一章</p><p>第二章</p></body>
                </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/Text/ch1.xhtml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第一章 起点</title></head>
                  <body>
                    <h1>第一章 起点</h1>
                    <p>你好 &amp; 世界。</p>
                    <p>第二段<br/>换行</p>
                  </body>
                </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/Text/ch 2.xhtml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第二章 终点</title></head>
                  <body>
                    <h2>第二章 终点</h2>
                    <p>结束。</p>
                  </body>
                </html>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
    }
}


