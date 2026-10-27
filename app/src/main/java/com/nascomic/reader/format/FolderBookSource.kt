package com.nascomic.reader.format

import com.nascomic.reader.core.text.NaturalSort
import java.io.InputStream

/**
 * 「一页怎么打开」的抽象。目录书源不关心平台 API，只按引用取字节；
 * 真机上引用是系统文档 id，测试里就是一个内存表。
 */
fun interface PageOpener {
    fun open(ref: String): InputStream
}

/**
 * 目录书源：一本书就是一个文件夹（散图）。
 *
 * 页序：章之间按目录名自然序，章内按文件名自然序。
 * 章：一级子目录即一章；如果图片直接躺在根目录，则整本一章。
 */
class FolderBookSource private constructor(
    private val pageRefs: List<String>,
    private val chapterOfPage: IntArray,
    private val chapterStarts: IntArray,
    override val chapters: List<ChapterInfo>,
    private val opener: PageOpener,
) : BookSource {

    override val pageCount: Int get() = pageRefs.size

    override fun pageName(page: Int): String = pageRefs[page].substringAfterLast('/')

    override fun openPage(page: Int): InputStream = opener.open(pageRefs[page])

    override fun chapterOf(page: Int): Int = chapterOfPage[page]

    override fun firstPageOfChapter(chapter: Int): Int = chapterStarts[chapter]

    override fun close() = Unit

    companion object {
        /**
         * @param entries 目录下所有条目（名字 + 引用）；非图片、目录条目会被忽略
         * @param fallbackTitle 根目录散图那一章的标题，通常用书名
         */
        fun from(
            entries: List<Pair<String, String>>,
            fallbackTitle: String,
            opener: PageOpener,
        ): FolderBookSource {
            val images = entries.filter { (name, _) -> ImageNames.isImage(name) }

            val grouped = images.groupBy { (name, _) -> chapterKeyOf(name) }
            val order = NaturalSort.sortedByName(grouped.keys.toList()) { it }

            val flatRefs = ArrayList<String>(images.size)
            val starts = ArrayList<Int>(order.size)
            val infos = ArrayList<ChapterInfo>(order.size)

            for ((ordinal, key) in order.withIndex()) {
                val list = grouped.getValue(key).sortedWith { a, b ->
                    NaturalSort.nameKey(a.first).compareTo(NaturalSort.nameKey(b.first))
                }
                starts += flatRefs.size
                infos += ChapterInfo(
                    ordinal = ordinal,
                    title = if (key.isEmpty()) fallbackTitle else key,
                    pageCount = list.size,
                )
                list.forEach { flatRefs += it.second }
            }

            val map = IntArray(flatRefs.size)
            for (ordinal in infos.indices) {
                val from = starts[ordinal]
                val to = if (ordinal + 1 < starts.size) starts[ordinal + 1] else flatRefs.size
                for (p in from until to) map[p] = ordinal
            }

            return FolderBookSource(flatRefs, map, starts.toIntArray(), infos, opener)
        }

        private fun chapterKeyOf(name: String): String {
            val slash = name.indexOf('/')
            return if (slash > 0) name.substring(0, slash) else ""
        }
    }
}
