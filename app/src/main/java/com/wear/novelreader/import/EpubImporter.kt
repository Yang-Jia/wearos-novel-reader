package com.wear.novelreader.import

import com.wear.novelreader.data.ImportedInfo
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object EpubImporter {

    private const val MAX_ENTRY_BYTES = 4L * 1024L * 1024L

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val path: String
    )

    private val blockTags = setOf(
        "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "section", "article", "blockquote", "pre", "td", "th", "ul", "ol",
        "table", "figcaption", "header", "footer", "aside", "main", "hr"
    )
    private val skipTags = setOf("script", "style", "head", "svg", "audio", "video")
    private val htmlTags = setOf("application/xhtml+xml", "text/html", "application/xml")

    fun import(
        file: File,
        chaptersDir: File,
        fallbackTitle: String,
        chunkLimit: Int = 8000,
        isCancelled: () -> Boolean = { false }
    ): ImportedInfo {
        if (isCancelled()) throw ImportCancelledException()
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val lookup = names.associateBy { it.lowercase() }
            fun findEntry(path: String): ZipEntry? {
                val normalized = normalizePath(path)
                return zip.getEntry(normalized)
                    ?: lookup[normalized.lowercase()]?.let { zip.getEntry(it) }
                    ?: lookup[normalized.lowercase().trimStart('/')]?.let { zip.getEntry(it) }
            }

            val containerEntry = findEntry("META-INF/container.xml")
                ?: throw IOException("不是有效的 EPUB：缺少 container.xml")
            val container = parseXml(decodeXmlBytes(readEntry(zip, containerEntry, isCancelled)))
                ?: throw IOException("无法解析 EPUB 容器")
            val rootFileNode = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
                ?: throw IOException("EPUB 中找不到 OPF 文件")
            val opfPath = rootFileNode.getAttribute("full-path")
            if (opfPath.isBlank()) throw IOException("EPUB OPF 路径为空")

            val opfEntry = findEntry(opfPath) ?: throw IOException("EPUB 中找不到 OPF：$opfPath")
            val opf = parseXml(decodeXmlBytes(readEntry(zip, opfEntry, isCancelled)))
                ?: throw IOException("无法解析 EPUB OPF")

            val bookTitle = textOfFirst(opf, "title").ifBlank { fallbackTitle }
            val author = textOfFirst(opf, "creator").trim()
            val opfDir = opfPath.replace('\\', '/').substringBeforeLast('/', "")

            val manifestNodes = opf.getElementsByTagNameNS("*", "item")
            val manifest = LinkedHashMap<String, ManifestItem>()
            for (i in 0 until manifestNodes.length) {
                if (isCancelled()) throw ImportCancelledException()
                val el = manifestNodes.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val href = el.getAttribute("href")
                val mediaType = el.getAttribute("media-type").lowercase()
                val properties = el.getAttribute("properties").lowercase()
                if (id.isBlank() || href.isBlank()) continue
                manifest[id] = ManifestItem(
                    id = id,
                    href = href,
                    mediaType = mediaType,
                    properties = properties,
                    path = resolvePath(opfDir, href)
                )
            }

            val spineIds = mutableListOf<String>()
            val spineNodes = opf.getElementsByTagNameNS("*", "itemref")
            for (i in 0 until spineNodes.length) {
                if (isCancelled()) throw ImportCancelledException()
                val el = spineNodes.item(i) as? Element ?: continue
                val idref = el.getAttribute("idref")
                if (idref.isNotBlank()) spineIds.add(idref)
            }

            var ordered = spineIds.mapNotNull { manifest[it] }
                .filter { isContentItem(it) && !isNavigationItem(it) }
            if (ordered.isEmpty()) {
                ordered = manifest.values.filter { isContentItem(it) && !isNavigationItem(it) }
            }
            if (ordered.isEmpty()) throw IOException("EPUB 中没有可读取的正文")

            val writer = ChapterWriter(chaptersDir, chunkLimit)
            var chapterNumber = 0
            var extractedAny = false

            for (item in ordered) {
                if (isCancelled()) throw ImportCancelledException()
                val entry = findEntry(item.path) ?: continue
                val bytes = readEntry(zip, entry, isCancelled)
                if (bytes.isEmpty()) continue
                val decoded = decodeXmlBytes(bytes)
                val document = parseXml(decoded)
                val title: String
                val text: String
                if (document != null) {
                    val heading = firstHeading(document)
                    title = heading.ifBlank { textOfFirst(document, "title").trim() }
                    text = extractText(document)
                } else {
                    title = regexTitle(decoded)
                    text = htmlToText(decoded)
                }
                val normalized = normalizeText(text)
                if (normalized.isBlank()) continue
                extractedAny = true
                chapterNumber++
                val chapterTitle = title.trim().take(80).ifBlank { "第${chapterNumber}章" }
                if (isTocTitle(chapterTitle)) continue
                val cleaned = TextCleaner.cleanChapterText(normalized, chapterTitle)
                if (cleaned.isBlank()) continue
                writer.setChapter(chapterTitle)
                writer.appendText(cleaned)
            }

            val chapters = writer.finish()
            if (!extractedAny || chapters.isEmpty()) {
                if (findEntry("META-INF/encryption.xml") != null) {
                    throw IOException("EPUB 可能已加密（DRM），无法导入")
                }
                throw IOException("EPUB 中没有可提取的文字内容")
            }

            return ImportedInfo(
                title = bookTitle.trim().ifBlank { fallbackTitle },
                author = author,
                format = "epub",
                chapters = chapters
            )
        }
    }

    private fun isContentItem(item: ManifestItem): Boolean {
        return item.mediaType in htmlTags ||
            item.href.endsWith(".xhtml", ignoreCase = true) ||
            item.href.endsWith(".html", ignoreCase = true)
    }

    private fun isNavigationItem(item: ManifestItem): Boolean {
        if (item.properties.split(' ').any { it == "nav" }) return true
        val file = item.href.substringAfterLast('/').lowercase()
        return file == "nav.xhtml" ||
            file.startsWith("toc.") ||
            file == "toc.xhtml" ||
            file.contains("table-of-contents")
    }

    private fun isTocTitle(title: String): Boolean {
        val value = title.trim().lowercase()
        return value == "目录" || value == "目錄" ||
            value == "contents" || value == "table of contents" ||
            value == "toc"
    }

    private fun readEntry(
        zip: ZipFile,
        entry: ZipEntry,
        isCancelled: () -> Boolean = { false }
    ): ByteArray {
        zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (isCancelled()) throw ImportCancelledException()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ENTRY_BYTES) throw IOException("章节文件过大（最大 4MB）")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun decodeXmlBytes(bytes: ByteArray): String {
        val detected = EncodingDetector.detect(bytes.copyOf(minOf(bytes.size, 256 * 1024)))
        var text = String(bytes, detected.charset)
        if (text.isNotEmpty() && text[0] == '\uFEFF') {
            text = text.substring(1)
        }
        return text
    }

    private fun parseXml(text: String): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = false
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.parse(InputSource(StringReader(text)))
        } catch (_: Exception) {
            null
        }
    }

    private fun trySetFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
        }
    }

    private fun textOfFirst(document: Document, tag: String): String {
        val nodes = document.getElementsByTagNameNS("*", tag)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun firstHeading(document: Document): String {
        val titles = document.getElementsByTagName("*")
        for (i in 0 until titles.length) {
            val el = titles.item(i) as? Element ?: continue
            val tag = el.tagName.substringAfter(':').lowercase()
            if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val value = el.textContent?.trim().orEmpty()
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun extractText(document: Document): String {
        val out = StringBuilder()
        walk(document.documentElement, out)
        return out.toString()
    }

    private fun walk(node: Node?, out: StringBuilder) {
        node ?: return
        when (node.nodeType) {
            Node.TEXT_NODE -> {
                val value = node.nodeValue
                if (value != null) out.append(value)
            }
            Node.ELEMENT_NODE -> {
                val tag = node.nodeName.substringAfter(':').lowercase()
                if (tag in skipTags) return
                var child = node.firstChild
                while (child != null) {
                    walk(child, out)
                    child = child.nextSibling
                }
                if (tag == "br") {
                    out.append('\n')
                } else if (tag in blockTags) {
                    out.append("\n\n")
                }
            }
        }
    }

    private fun normalizeText(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        text = text.replace(Regex("[ \\t\\u000B\\f]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    private fun htmlToText(html: String): String {
        var text = html
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        text = text.replace(Regex("(?is)<head\\b.*?</head>"), "")
        text = text.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), "")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|blockquote|pre)>"), "\n\n")
        text = text.replace(Regex("(?s)<[^>]+>"), "")
        text = decodeEntities(text)
        return text
    }

    private fun regexTitle(html: String): String {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        if (!title.isNullOrBlank()) return stripTags(title)
        val heading = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>").find(html)?.groupValues?.get(1)
        return if (heading.isNullOrBlank()) "" else stripTags(heading)
    }

    private fun stripTags(value: String): String = decodeEntities(
        value.replace(Regex("(?s)<[^>]+>"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    private val entityPattern = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™"
    )

    private fun decodeEntities(value: String): String {
        return entityPattern.replace(value) { match ->
            val body = match.groupValues[1]
            try {
                when {
                    body.startsWith("#x", ignoreCase = true) -> {
                        val code = body.substring(2).toInt(16)
                        String(Character.toChars(code))
                    }
                    body.startsWith("#") -> {
                        val code = body.substring(1).toInt()
                        String(Character.toChars(code))
                    }
                    else -> namedEntities[body] ?: match.value
                }
            } catch (_: Exception) {
                match.value
            }
        }
    }

    private fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> continue
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = try {
            URLDecoder.decode(clean.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: Exception) {
            clean
        }
        return if (baseDir.isBlank()) normalizePath(decoded) else normalizePath("$baseDir/$decoded")
    }
}



