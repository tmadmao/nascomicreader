package com.nascomic.reader.format.comic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Node

/**
 * 读压缩包里的 `ComicInfo.xml`。
 *
 * 用标准 DOM 解析而不是自己扫字符串：注释里的伪标签、CDATA、实体转义、属性都由解析器处理，
 * 不会把 `<!-- <Writer>假的</Writer> -->` 当成真字段。文件限 256 KB，超了直接当没有——
 * 正常的 ComicInfo 只有几 KB，几百 KB 已经是异常输入，不值得为它冒内存风险。
 *
 * 定位规则：只认文件名正好是 `ComicInfo.xml` 的条目（大小写不敏感），`MyComicInfo.xml`
 * 这类名字相近的文件不算。根目录优先，其次任意一层；`__MACOSX/` 里的不看。
 */
object ComicInfoReader {

    private const val FILE_NAME = "comicinfo.xml"

    /** 元数据文件的读取上限；超过就当作没有这个文件 */
    const val MAX_BYTES = 256 * 1024

    /** 在条目名里挑出要读的那一个；没有就返回 null */
    fun pick(names: List<String>): String? {
        var nested: String? = null
        for (name in names) {
            val normalized = name.replace('\\', '/')
            if (normalized.startsWith("__macosx/", ignoreCase = true)) continue
            val base = normalized.substringAfterLast('/')
            if (!base.equals(FILE_NAME, ignoreCase = true)) continue
            if (!normalized.contains('/')) return name
            if (nested == null) nested = name
        }
        return nested
    }

    /** 从一条条目名与打开方式里读出元数据；找不着、读不动、超限都返回 null */
    fun read(names: List<String>, open: (String) -> InputStream?): ComicMeta? {
        val name = pick(names) ?: return null
        val bytes = runCatching { open(name)?.use { readCapped(it) } }.getOrNull() ?: return null
        return parse(bytes).takeUnless { it.isEmpty }
    }

    fun parse(xml: String): ComicMeta = parse(xml.toByteArray(Charsets.UTF_8))

    fun parse(bytes: ByteArray): ComicMeta {
        val doc = document(bytes) ?: return ComicMeta(null, null)
        val writer = text(doc, "Writer") ?: text(doc, "Penciller")
        val number = text(doc, "Number")?.let(::normalizeVolume)
        val volume = text(doc, "Volume")?.let(::normalizeVolume)?.takeUnless(::looksLikeYear)
        return ComicMeta(writer = writer, volume = number ?: volume)
    }

    /** 读到上限还读不完就放弃，返回 null 由调用方当作「没有这个文件」 */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun document(bytes: ByteArray): Document? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        // 带 DTD 的一律不认：不为一页元数据去处理外部实体
        if (String(bytes, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                // 这几条在部分实现上不支持，设不上就算了；上面已经挡掉了 DTD
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    /** 取第一个同名元素的文本；ComicInfo 的字段名是 PascalCase，少数包写成小写也认 */
    private fun text(doc: Document, tag: String): String? {
        val exact = doc.getElementsByTagName(tag)
        if (exact.length > 0) return exact.item(0).textContent?.trim()?.ifEmpty { null }
        val root = doc.documentElement ?: return null
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals(tag, ignoreCase = true)) {
                return node.textContent?.trim()?.ifEmpty { null }
            }
        }
        return null
    }

    /**
     * `Volume` 有时填的是出版年份而不是卷号。四位数且落在 1900–2100 的当成年份丢掉，
     * 免得把「2021 年那本」写成第 2021 卷。
     */
    private fun looksLikeYear(text: String): Boolean =
        text.length == 4 && text.all { it.isDigit() } && text.toInt() in 1900..2100

    /** 去前导零，让 `001` 与文件名解析出的 `1` 落在同一个值上；非数字原样留着 */
    private fun normalizeVolume(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (!t.all { it.isDigit() } || t.length > 6) return t
        return t.trimStart('0').ifEmpty { "0" }
    }
}
