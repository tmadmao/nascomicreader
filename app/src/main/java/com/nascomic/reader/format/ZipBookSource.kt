package com.nascomic.reader.format

import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.format.zip.ZipCatalog
import java.io.InputStream

/**
 * 压缩包书源。
 *
 * 页序：章之间按目录名自然序，章内按文件名自然序。
 * 章：包内一级子目录即一章；散在根目录的图片归到「无章名」的一章，排在最前。
 */
class ZipBookSource internal constructor(
    private val catalog: ZipCatalog,
    fallbackTitle: String = "",
) : BookSource {

    private val pages: List<ZipCatalog.Entry>
    private val chapterOfPage: IntArray
    private val chapterStarts: IntArray

    override val chapters: List<ChapterInfo>

    init {
        val images = catalog.entries.filter { !it.isDirectory && ImageNames.isImage(it.name) }

        val grouped: Map<String, List<ZipCatalog.Entry>> = images.groupBy { chapterKeyOf(it.name) }
        val order = NaturalSort.sortedByName(grouped.keys.toList()) { it }

        val flat = ArrayList<ZipCatalog.Entry>(images.size)
        val starts = ArrayList<Int>(order.size)
        val infos = ArrayList<ChapterInfo>(order.size)
        for ((ordinal, key) in order.withIndex()) {
            val list = grouped.getValue(key).sortedWith { a, b ->
                NaturalSort.nameKey(a.name).compareTo(NaturalSort.nameKey(b.name))
            }

            starts += flat.size
            infos += ChapterInfo(
                ordinal = ordinal,
                title = if (key.isEmpty()) fallbackTitle else key,
                pageCount = list.size,
            )
            flat += list
        }

        pages = flat
        chapterStarts = starts.toIntArray()
        chapters = infos

        val map = IntArray(flat.size) { 0 }
        for (ordinal in infos.indices) {
            val from = starts[ordinal]
            val to = if (ordinal + 1 < starts.size) starts[ordinal + 1] else flat.size
            for (p in from until to) map[p] = ordinal
        }
        chapterOfPage = map
    }

    override val pageCount: Int get() = pages.size

    override fun pageName(page: Int): String = pages[page].name

    override fun openPage(page: Int): InputStream = catalog.open(pages[page])

    override fun chapterOf(page: Int): Int = chapterOfPage[page]

    override fun firstPageOfChapter(chapter: Int): Int = chapterStarts[chapter]

    override fun close() = catalog.close()

    private fun chapterKeyOf(name: String): String {
        val slash = name.indexOf('/')
        return if (slash > 0) name.substring(0, slash) else ""
    }

    companion object {
        fun open(data: com.nascomic.reader.format.zip.RandomAccessData, fallbackTitle: String = "") =
            ZipBookSource(ZipCatalog.open(data), fallbackTitle)
    }
}
