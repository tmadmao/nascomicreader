package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 双页组屏的边界：首张单页、末张单页、每章首张单页，以及不跨章 */
class SpreadPlanTest {

    private fun range(page: Int, first: Int, last: Int, order: Int) =
        SpreadPlan.range(page, first, last, order)

    @Test
    fun `关掉时一页一屏`() {
        assertEquals(0..0, range(0, 0, 9, SpreadPlan.ORDER_NONE))
        assertEquals(3..3, range(3, 0, 9, SpreadPlan.ORDER_NONE))
    }

    @Test
    fun `首张单页只让第 1 页单独`() {
        // 第 1 页单独；之后 (1,2) (3,4)……
        assertEquals(0..0, range(0, 0, 9, SpreadPlan.ORDER_FIRST_SINGLE))
        assertEquals(1..2, range(1, 0, 9, SpreadPlan.ORDER_FIRST_SINGLE))
        assertEquals(1..2, range(2, 0, 9, SpreadPlan.ORDER_FIRST_SINGLE))
        assertEquals(3..4, range(3, 0, 9, SpreadPlan.ORDER_FIRST_SINGLE))
    }

    @Test
    fun `末张单页让最后一页单独`() {
        assertEquals(0..1, range(0, 0, 9, SpreadPlan.ORDER_LAST_SINGLE))
        assertEquals(8..8, range(8, 0, 9, SpreadPlan.ORDER_LAST_SINGLE))
        // 9 是最后一页：单独
        assertEquals(9..9, range(9, 0, 9, SpreadPlan.ORDER_LAST_SINGLE))
    }

    @Test
    fun `每章首张单页在每章都生效`() {
        // 第二章 10..19，第一页 10 单独，之后 (11,12)
        assertEquals(10..10, range(10, 10, 19, SpreadPlan.ORDER_CHAPTER_FIRST_SINGLE))
        assertEquals(11..12, range(11, 10, 19, SpreadPlan.ORDER_CHAPTER_FIRST_SINGLE))
        assertEquals(11..12, range(12, 10, 19, SpreadPlan.ORDER_CHAPTER_FIRST_SINGLE))
    }

    @Test
    fun `不跨章：章末不会和下一章拼一起`() {
        // 章 0..4，第 4 页是本章最后一页，即使两页一组也只能取到 4
        assertEquals(4..4, range(4, 0, 4, SpreadPlan.ORDER_NONE))
        val r = range(3, 0, 4, SpreadPlan.ORDER_FIRST_SINGLE)
        assertEquals(3..4, r)
    }

    @Test
    fun `只有一页的章不出界`() {
        assertEquals(0..0, range(0, 0, 0, SpreadPlan.ORDER_FIRST_SINGLE))
        assertEquals(0..0, range(0, 0, 0, SpreadPlan.ORDER_LAST_SINGLE))
    }

    @Test
    fun `页码越界时夹回本章`() {
        // 未知档位按「一页一屏」处理，页码夹回本章
        assertEquals(0..0, range(-5, 0, 9, 99))
        // 越界夹到最后一页；首张单页之后从 1 起两页一组，9 正好是组首
        assertEquals(9..9, range(99, 0, 9, SpreadPlan.ORDER_FIRST_SINGLE))
    }
}
