package com.nascomic.reader.library.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面页号折算。
 *
 * 封面存的是全书页号；只开一章去取时，页号必须先落到正确的那一章上，
 * 否则第二章的封面会变成第一章的末页。
 */
class CoverPageMapTest {

    @Test
    fun `落在第一章`() {
        assertEquals(0 to 0, CoverPageMap.pick(listOf(5, 4), 0))
        assertEquals(0 to 4, CoverPageMap.pick(listOf(5, 4), 4))
    }

    @Test
    fun `第二章的第一页不会被钳回第一章`() {
        // 第一章 5 页（0..4），全书第 5 页就是第二章第 0 页
        assertEquals(1 to 0, CoverPageMap.pick(listOf(5, 4), 5))
        assertEquals(1 to 3, CoverPageMap.pick(listOf(5, 4), 8))
    }

    @Test
    fun `三章连续折算`() {
        val totals = listOf(3, 3, 3)
        assertEquals(0 to 2, CoverPageMap.pick(totals, 2))
        assertEquals(1 to 0, CoverPageMap.pick(totals, 3))
        assertEquals(2 to 0, CoverPageMap.pick(totals, 6))
        assertEquals(2 to 2, CoverPageMap.pick(totals, 8))
    }

    @Test
    fun `量不出页数或越界就不猜`() {
        // 有章没量过页数：累计值不可靠
        assertNull(CoverPageMap.pick(listOf(5, 0), 5))
        // 页号比整本还大
        assertNull(CoverPageMap.pick(listOf(5, 4), 9))
        assertNull(CoverPageMap.pick(emptyList(), 0))
        assertNull(CoverPageMap.pick(listOf(5), -1))
    }
}
