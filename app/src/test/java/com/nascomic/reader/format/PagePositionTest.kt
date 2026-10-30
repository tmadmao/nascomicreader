package com.nascomic.reader.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PagePositionTest {

    private class FakeSource(private val name: String, override val pageCount: Int) : BookSource {
        override val chapters: List<ChapterInfo>
            get() = listOf(ChapterInfo(0, name, pageCount))
        override fun pageName(page: Int) = "$name-$page"
        override fun openPage(page: Int): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun chapterOf(page: Int) = 0
        override fun firstPageOfChapter(chapter: Int) = 0
        override fun close() = Unit
    }

    private fun chapteredBook() = MultiChapterSource.of(
        "第1话" to FakeSource("c1", 2),
        "第2话" to FakeSource("c2", 3),
    )

    @Test
    fun `章回体按章内页报位置`() {
        val book = chapteredBook()

        // 第 1 话：全局 0..1 → 章内 1..2
        book.pagePosition(0, chaptered = true).also {
            assertEquals(0, it.chapterOrdinal)
            assertEquals("第1话", it.chapterTitle)
            assertEquals(1, it.page)
            assertEquals(2, it.total)
            assertTrue(it.chaptered)
        }
        book.pagePosition(1, chaptered = true).also {
            assertEquals(2, it.page)
            assertEquals(2, it.total)
        }

        // 第 2 话起点是全局第 3 页（0 基 2），章内应当是第 1 页，不是第 3 页
        book.pagePosition(2, chaptered = true).also {
            assertEquals(1, it.chapterOrdinal)
            assertEquals("第2话", it.chapterTitle)
            assertEquals(1, it.page)
            assertEquals(3, it.total)
        }
        book.pagePosition(4, chaptered = true).also {
            assertEquals(3, it.page)
            assertEquals(3, it.total)
        }
    }

    @Test
    fun `单行本按全书页报位置`() {
        val book = FakeSource("单行本", 5)
        book.pagePosition(3, chaptered = false).also {
            assertEquals(4, it.page)
            assertEquals(5, it.total)
            assertFalse(it.chaptered)
        }
    }

    @Test
    fun `章回体按单行本口径算时不给章名`() {
        // 形态判断错时不能把占位章的名字漏到界面上
        val book = chapteredBook()
        val pos = book.pagePosition(3, chaptered = false)
        assertEquals("", pos.chapterTitle)
        assertEquals(4, pos.page)
        assertEquals(5, pos.total)
    }
}
