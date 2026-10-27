package com.nascomic.reader.format.epub

import com.nascomic.reader.core.text.NaturalSort

/**
 * 把 OPF 的书脊顺序翻译成「一页对应包内哪个图片文件」。
 *
 * 漫画 EPUB 的书脊项通常是每页一张 XHTML，里面套一个 `<img>`；也有直接把图片列进书脊的。
 * 两种都照顾：书脊项是图片就用它，是文档就从中找第一张图。
 */
object EpubPages {

    private val IMG_SRC = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SVG_IMAGE = Regex("""<image\b[^>]*\b(?:xlink:)?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * @param readText 按包内路径读文本；读不到返回 null
     * @return 按阅读顺序排列的图片路径
     */
    fun resolve(pkg: Opf.Package, opfPath: String, readText: (String) -> String?): List<String> {
        val base = dirOf(opfPath)
        val byId = pkg.items.associateBy { it.id }
        val pages = ArrayList<String>()

        for (idref in pkg.spine) {
            val item = byId[idref] ?: continue
            val path = join(base, item.href)
            if (item.mediaType.startsWith("image/")) {
                pages += path
                continue
            }
            val html = readText(path) ?: continue
            val src = firstImageSrc(html) ?: continue
            pages += join(dirOf(path), src)
        }

        // 书脊里找不到图（有的 EPUB 书脊指向纯文本页），退回按文件名排清单里的图片
        if (pages.isEmpty()) {
            return pkg.items
                .filter { it.mediaType.startsWith("image/") }
                .map { join(base, it.href) }
                .sortedWith { a, b -> NaturalSort.nameKey(a).compareTo(NaturalSort.nameKey(b)) }
        }
        return pages
    }

    fun firstImageSrc(html: String): String? =
        IMG_SRC.find(html)?.groupValues?.get(1)
            ?: SVG_IMAGE.find(html)?.groupValues?.get(1)

    /** 取所在目录，没有目录时返回空串 */
    fun dirOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "" else path.substring(0, slash)
    }

    /** 把相对路径拼到基准目录上，并消掉 `.` 与 `..` */
    fun join(base: String, href: String): String {
        val raw = when {
            href.isEmpty() -> return base
            href.startsWith("/") -> href.removePrefix("/")
            base.isEmpty() -> href
            else -> "$base/$href"
        }
        val out = ArrayList<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        return out.joinToString("/")
    }
}
