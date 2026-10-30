package com.nascomic.reader.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MultiChapterSourceTest {

    /** 假的书源：页码按顺序返回，方便断言 */
    private class FakeSource(private val name: String, override val pageCount: Int) : BookSource {
        override val chapters: List<ChapterInfo>
            get() = listOf(ChapterInfo(0, name, pageCount))
        override fun pageName(page: Int) = "$name-$page"
        override fun openPage(page: Int): InputStream = ByteArrayInputStream("$name:$page".toByteArray())
        override fun chapterOf(page: Int) = 0
        override fun firstPageOfChapter(chapter: Int) = 0
        override fun close() = Unit
    }

    @Test
    fun `多章拼成一本，页码连续`() {
        val book = MultiChapterSource.of(
            "第1话" to FakeSource("c1", 3),
            "第2话" to FakeSource("c2", 2),
        )
        assertEquals(5, book.pageCount)
        assertEquals(2, book.chapters.size)
        assertEquals(listOf(3, 2), book.chapters.map { it.pageCount })

        assertEquals(0, book.firstPageOfChapter(0))
        assertEquals(3, book.firstPageOfChapter(1))
    }

    @Test
    fun `页到章的映射跨过章边界`() {
        val book = MultiChapterSource.of(
            "a" to FakeSource("a", 2),
            "b" to FakeSource("b", 3),
            "c" to FakeSource("c", 1),
        )
        assertEquals(0, book.chapterOf(0))
        assertEquals(0, book.chapterOf(1))
        assertEquals(1, book.chapterOf(2))
        assertEquals(1, book.chapterOf(4))
        assertEquals(2, book.chapterOf(5))
    }

    @Test
    fun `打开页会落到正确的章`() {
        val book = MultiChapterSource.of(
            "a" to FakeSource("a", 2),
            "b" to FakeSource("b", 2),
        )
        // 第 3 页（0 基 2）应该是第二章第 0 页
        val text = book.openPage(2).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("b:0", text)
    }

    @Test
    fun `空章不影响页码`() {
        val book = MultiChapterSource.of(
            "a" to FakeSource("a", 2),
            "empty" to FakeSource("e", 0),
            "c" to FakeSource("c", 1),
        )
        assertEquals(3, book.pageCount)
        assertEquals(3, book.chapters.size)
        assertEquals(2, book.firstPageOfChapter(2))
    }

    @Test
    fun `库里有页数时不去打开每一章`() {
        var opened = 0
        val book = MultiChapterSource(
            listOf(
                MultiChapterSource.Part("a", 3) { opened++; throw IllegalStateException("不该打开") },
                MultiChapterSource.Part("b", 2) { opened++; throw IllegalStateException("不该打开") },
            )
        )
        // 页数、章数、章首页都只用声明值就能算出来
        assertEquals(5, book.pageCount)
        assertEquals(listOf(3, 2), book.chapters.map { it.pageCount })
        assertEquals(3, book.firstPageOfChapter(1))
        assertEquals(0, opened)
    }

    @Test
    fun `没声明页数才逐章打开`() {
        var opened = 0
        val book = MultiChapterSource(
            listOf(
                MultiChapterSource.Part("a", -1) { opened++; FakeSource("a", 2) },
                MultiChapterSource.Part("b", -1) { opened++; FakeSource("b", 4) },
            )
        )
        assertEquals(6, book.pageCount)
        assertEquals(2, opened)
    }
}
