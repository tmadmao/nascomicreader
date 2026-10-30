package com.nascomic.reader.format

import java.io.InputStream

/**
 * 章回体书源：把多个「章」拼成一本。
 *
 * 一章可以是目录（散图），也可以是一个压缩包。这里只做展平与页码换算，
 * 具体怎么读由每个章自己的 [BookSource] 决定。
 *
 * 章的书源是**用到才建**。[Part.declaredPages] 给了页数（库里存着上次数的结果）时，
 * 连建都不建——远端三百章的书回来看第二遍，不必再发三百次列目录。
 */
class MultiChapterSource(private val parts: List<Part>) : BookSource {

    class Part(
        val title: String,
        /** 已知页数，-1 表示不知道（要打开才知道） */
        private val declaredPages: Int = -1,
        private val factory: () -> BookSource,
    ) {

        constructor(title: String, source: BookSource) : this(title, -1, { source })

        private var opened: BookSource? = null

        val source: BookSource
            @Synchronized get() = opened ?: factory().also { opened = it }

        /** 这一章的书源建过没有。关的时候只关建过的 */
        val isOpened: Boolean
            @Synchronized get() = opened != null

        val pages: Int get() = if (declaredPages >= 0) declaredPages else source.pageCount

        fun close() {
            synchronized(this) {
                val src = opened ?: return
                opened = null
                runCatching { src.close() }
            }
        }
    }

    private val starts: IntArray
    override val chapters: List<ChapterInfo>

    init {
        starts = IntArray(parts.size)
        var acc = 0
        val infos = ArrayList<ChapterInfo>(parts.size)
        for ((i, part) in parts.withIndex()) {
            starts[i] = acc
            val pages = part.pages
            infos += ChapterInfo(ordinal = i, title = part.title, pageCount = pages)
            acc += pages
        }
        chapters = infos
    }

    override val pageCount: Int get() = chapters.sumOf { it.pageCount }

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
        parts.forEach { it.close() }
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
