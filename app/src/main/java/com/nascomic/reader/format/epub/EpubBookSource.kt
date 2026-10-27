package com.nascomic.reader.format.epub

import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.ChapterInfo
import com.nascomic.reader.format.zip.ZipCatalog
import java.io.InputStream

/**
 * EPUB 书源。EPUB 本质是个 zip，所以复用 [ZipCatalog] 读条目，只是页序要按书脊来。
 *
 * 章：v1 整本一章。漫画 EPUB 的目录通常是「卷」而不是「话」，
 * 按章拆要先解析 nav/ncx，等有真实需求再做。
 */
class EpubBookSource(private val catalog: ZipCatalog) : BookSource {

    private val pages: List<ZipCatalog.Entry>

    override val chapters: List<ChapterInfo>

    init {
        val byName = catalog.entries.filter { !it.isDirectory }.associateBy { it.name }

        val container = byName["META-INF/container.xml"]
            ?: throw IllegalStateException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val containerXml = readText(container)
            ?: throw IllegalStateException("EPUB 的 container.xml 读不出来")

        val opfPath = Opf.rootPath(containerXml)
            ?: throw IllegalStateException("container.xml 里没有 rootfile")
        val opfEntry = byName[opfPath]
            ?: throw IllegalStateException("EPUB 里找不到 $opfPath")

        val pkg = Opf.parse(readText(opfEntry) ?: throw IllegalStateException("OPF 读不出来"))
        val paths = EpubPages.resolve(pkg, opfPath) { path ->
            byName[path]?.let { readText(it) }
        }

        pages = paths.mapNotNull { byName[it] }
        chapters = listOf(ChapterInfo(ordinal = 0, title = "", pageCount = pages.size))
    }

    override val pageCount: Int get() = pages.size

    override fun pageName(page: Int): String = pages[page].name.substringAfterLast('/')

    override fun openPage(page: Int): InputStream = catalog.open(pages[page])

    override fun chapterOf(page: Int): Int = 0

    override fun firstPageOfChapter(chapter: Int): Int = 0

    override fun close() = catalog.close()

    /** XHTML/OPF 都是小文件，直接读成字符串；超长的不解析（不正常） */
    private fun readText(entry: ZipCatalog.Entry): String? = runCatching {
        catalog.open(entry).use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_TEXT_BYTES) return null
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    }
}
