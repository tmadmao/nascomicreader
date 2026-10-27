package com.nascomic.reader.format

import com.nascomic.reader.core.text.NaturalSort

/**
 * 压缩包类书源的共用排页规则（zip 与 tar 都一样）：
 *
 * - 章：包内一级子目录即一章；散在根目录的图片归到「无章名」的一章，排在最前
 * - 页：章之间按目录名自然序，章内按文件名自然序
 *
 * 抽出来是为了 zip / tar 只有「怎么读条目」不同，排页逻辑只有这一份。
 */
object ArchivePaging {

    class Layout<T>(
        val pages: List<T>,
        private val chapterOfPage: IntArray,
        private val chapterStarts: IntArray,
        val chapters: List<ChapterInfo>,
    ) {
        fun chapterOf(page: Int): Int = chapterOfPage[page]

        fun firstPageOfChapter(chapter: Int): Int = chapterStarts[chapter]
    }

    fun <T> layout(
        items: List<T>,
        nameOf: (T) -> String,
        isDirectory: (T) -> Boolean,
        fallbackTitle: String,
    ): Layout<T> {
        val images = items.filter { !isDirectory(it) && ImageNames.isImage(nameOf(it)) }
        val grouped = images.groupBy { chapterKeyOf(nameOf(it)) }
        val order = NaturalSort.sortedByName(grouped.keys.toList()) { it }

        val flat = ArrayList<T>(images.size)
        val starts = ArrayList<Int>(order.size)
        val infos = ArrayList<ChapterInfo>(order.size)

        for ((ordinal, key) in order.withIndex()) {
            val list = grouped.getValue(key).sortedWith { a, b ->
                NaturalSort.nameKey(nameOf(a)).compareTo(NaturalSort.nameKey(nameOf(b)))
            }
            starts += flat.size
            infos += ChapterInfo(
                ordinal = ordinal,
                title = if (key.isEmpty()) fallbackTitle else key,
                pageCount = list.size,
            )
            flat += list
        }

        val map = IntArray(flat.size)
        for (ordinal in infos.indices) {
            val from = starts[ordinal]
            val to = if (ordinal + 1 < starts.size) starts[ordinal + 1] else flat.size
            for (p in from until to) map[p] = ordinal
        }

        return Layout(flat, map, starts.toIntArray(), infos)
    }

    private fun chapterKeyOf(name: String): String {
        val slash = name.indexOf('/')
        return if (slash > 0) name.substring(0, slash) else ""
    }
}
