package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnTest {

    @Test
    fun `单页步进继续往后翻`() {
        assertEquals(3, PageTurn.nextPage(page = 2, step = 1, total = 10))
    }

    @Test
    fun `最后一页之后没有内容`() {
        assertNull(PageTurn.nextPage(page = 9, step = 1, total = 10))
    }

    @Test
    fun `只有一页的书也算看完`() {
        assertNull(PageTurn.nextPage(page = 0, step = 1, total = 1))
    }

    @Test
    fun `双页并排时一屏两页，倒数第二屏就该收尾`() {
        // 10 页：0-1、2-3、4-5、6-7、8-9。第 8 页那屏已经是最后一屏
        assertNull(PageTurn.nextPage(page = 8, step = 2, total = 10))
        assertEquals(8, PageTurn.nextPage(page = 6, step = 2, total = 10))
    }

    @Test
    fun `奇数页的书，最后一页单独成屏`() {
        // 9 页：0-1、2-3、4-5、6-7、8。第 6 页之后还有第 8 页
        assertEquals(8, PageTurn.nextPage(page = 6, step = 2, total = 9))
        assertNull(PageTurn.nextPage(page = 8, step = 2, total = 9))
    }

    @Test
    fun `页数为零或步进为零都不越界`() {
        assertNull(PageTurn.nextPage(page = 0, step = 1, total = 0))
        assertEquals(1, PageTurn.nextPage(page = 0, step = 0, total = 10))
    }
}
