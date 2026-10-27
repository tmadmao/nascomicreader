package com.nascomic.reader.format

import java.io.InputStream

/**
 * 章回体书源：把多个「章」拼成一本。
 *
 * 一章可以是目录（散图），也可以是一个压缩包。这里只做展平与页码换算，
 * 具体怎么读由每个章自己的 [BookSource] 决定。
 */
class MultiChapterSource(private val parts: List<Part>) : BookSource {

    data class Part(val title: String, val source: BookSource)

    private val starts: IntArray
    override val chapters: List<ChapterInfo>

    init {
        starts = IntArray(parts.size)
        var acc = 0
        val infos = ArrayList<ChapterInfo>(parts.size)
        for ((i, part) in parts.withIndex()) {
            starts[i] = acc
            infos += ChapterInfo(ordinal = i, title = part.title, pageCount = part.source.pageCount)
            acc += part.source.pageCount
        }
        chapters = infos
    }

    override val pageCount: Int get() = parts.sumOf { it.source.pageCount }

    override fun pageName(page: Int): String {
        val (index, local) = locate(page)
        return parts[index].source.pageName(local)
    }

    override fun openPage(page: Int): InputStream {
        val (index, local) = locate(page)
        return parts[index].source.openPage(local)
    }

    override fun chapterOf(page: Int): Int = locate(page).first

    override fun firstPageOfChapter(chapter: Int): Int = starts[chapter]

    override fun close() {
        parts.forEach { runCatching { it.source.close() } }
    }

    /** 返回（章序号, 章内页号） */
    private fun locate(page: Int): Pair<Int, Int> {
        var index = 0
        // 章数一般不多，线性找够用；真到几百章再换二分
        for (i in starts.indices) {
            if (page >= starts[i]) index = i else break
        }
        return index to (page - starts[index])
    }

    companion object {
        fun of(vararg parts: Pair<String, BookSource>): MultiChapterSource =
            MultiChapterSource(parts.map { Part(it.first, it.second) })
    }
}
