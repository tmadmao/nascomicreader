package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageWindowTest {

    @Test
    fun `窗口从当前页往回留预载页数、往前留两倍`() {
        // ahead = 3：往回 3 页，往前 2 × 3 + 1 页
        assertEquals(0..7, PageWindow.keep(current = 0, ahead = 3, total = 12))
        assertEquals(2..9, PageWindow.keep(current = 5, ahead = 3, total = 10))
    }

    @Test
    fun `靠近书尾时窗口被总页数截断`() {
        // 最后一页：from = 6，to = min(9 + 6 + 1, 9) = 9
        assertEquals(6..9, PageWindow.keep(current = 9, ahead = 3, total = 10))
    }

    @Test
    fun `只有一页的书窗口就只有那一页`() {
        assertEquals(0..0, PageWindow.keep(current = 0, ahead = 3, total = 1))
    }

    @Test
    fun `没有页数时窗口为空`() {
        assertTrue(PageWindow.keep(current = 0, ahead = 3, total = 0).isEmpty())
    }

    @Test
    fun `预取是当前页之后到预载页数`() {
        assertEquals(6..8, PageWindow.prefetch(current = 5, ahead = 3, total = 10))
    }

    @Test
    fun `预取越界只到最后一页`() {
        assertEquals(9..9, PageWindow.prefetch(current = 8, ahead = 3, total = 10))
    }

    @Test
    fun `最后一页之后没有可预取的`() {
        assertTrue(PageWindow.prefetch(current = 9, ahead = 3, total = 10).isEmpty())
        assertTrue(PageWindow.prefetch(current = 0, ahead = 0, total = 10).isEmpty())
    }

    @Test
    fun `双向预载往前留一半`() {
        // ahead = 3 → 往前 1 页（3 / 2），往后 3 页
        assertEquals(4..8, PageWindow.prefetchBoth(current = 5, ahead = 3, total = 10))
    }

    @Test
    fun `双向预载在书头书尾被截断`() {
        assertEquals(0..3, PageWindow.prefetchBoth(current = 0, ahead = 3, total = 10))
        assertEquals(8..9, PageWindow.prefetchBoth(current = 9, ahead = 3, total = 10))
    }

    @Test
    fun `保留窗口一定盖住预取范围`() {
        // 这条是给「预取完立刻被自己丢掉」防回归的
        val keep = PageWindow.keep(current = 5, ahead = 3, total = 10)
        val ahead = PageWindow.prefetch(current = 5, ahead = 3, total = 10)
        assertTrue(keep.contains(ahead.first))
        assertTrue(keep.contains(ahead.last))
    }

    @Test
    fun `前后各自定页数时按各自的范围取`() {
        val range = PageWindow.prefetchAround(current = 10, prev = 2, next = 4, total = 100)
        assertEquals(8..14, range)
    }

    @Test
    fun `前后都设 0 就什么都不预载`() {
        assertEquals(IntRange.EMPTY, PageWindow.prefetchAround(5, 0, 0, 100))
    }

    @Test
    fun `预载范围不越过书的两端`() {
        assertEquals(0..3, PageWindow.prefetchAround(0, 5, 3, 100))
        assertEquals(97..99, PageWindow.prefetchAround(99, 2, 5, 100))
    }

    @Test
    fun `保留范围按前后各自给的页数走`() {
        // 身后留 2 页，前面留两倍预载再加一页缓冲
        assertEquals(8..(10 + 8 + 1), PageWindow.keep(10, prev = 2, next = 4, total = 100))
    }
}
