package com.nascomic.reader.format.epub

/**
 * EPUB 的两个「目录」文件：`META-INF/container.xml` 指向 OPF，OPF 里是清单与书脊。
 *
 * 这里只做文本解析，不碰文件系统——解析器能在 JVM 上用字符串单测，
 * 取字节的事留给 [EpubBookSource]。
 */
object Opf {

    data class Item(val id: String, val href: String, val mediaType: String)

    data class Package(val items: List<Item>, val spine: List<String>)

    private val ROOTFILE = Regex("""<(?:[A-Za-z0-9]+:)?rootfile\b[^>]*>""")
    private val FULL_PATH = Regex("""full-path\s*=\s*["']([^"']+)["']""")

    private val ITEM = Regex("""<(?:[A-Za-z0-9]+:)?item\b[^>]*>""")
    private val ITEMREF = Regex("""<(?:[A-Za-z0-9]+:)?itemref\b[^>]*>""")
    private val ATTR = Regex("""([A-Za-z0-9:_-]+)\s*=\s*["']([^"']*)["']""")

    /** 从 container.xml 取 OPF 的路径 */
    fun rootPath(containerXml: String): String? {
        val tag = ROOTFILE.find(containerXml)?.value ?: return null
        return FULL_PATH.find(tag)?.groupValues?.get(1)
    }

    fun parse(opfXml: String): Package {
        val items = ITEM.findAll(opfXml).mapNotNull { match ->
            val attrs = attributes(match.value)
            val id = attrs["id"] ?: return@mapNotNull null
            val href = attrs["href"] ?: return@mapNotNull null
            Item(id = id, href = href, mediaType = attrs["media-type"].orEmpty())
        }.toList()

        val spine = ITEMREF.findAll(opfXml).mapNotNull { match ->
            attributes(match.value)["idref"]
        }.toList()

        return Package(items = items, spine = spine)
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
}
