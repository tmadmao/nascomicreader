package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageSlotsTest {

    /** 页 5 拆上下占两段，其余各占一段：槽位 0..4 → 页 4,5,5,6,7 */
    private fun mixed(): PageSlots = PageSlots().apply {
        rebuild(listOf(4, 5, 5, 6, 7))
    }

    @Test
    fun `槽位数不等于页数，页码按表换算`() {
        val slots = mixed()
        assertEquals(5, slots.size)
        assertEquals(4, slots.pageAt(0))
        assertEquals(5, slots.pageAt(1))
        assertEquals(5, slots.pageAt(2))
        assertEquals(6, slots.pageAt(3))
        assertNull(slots.pageAt(5))
    }

    @Test
    fun `定位到某页落在它最早的槽位`() {
        val slots = mixed()
        assertEquals(1, slots.firstSlotOf(5))
        assertEquals(3, slots.firstSlotOf(6))
        assertEquals(-1, slots.firstSlotOf(9))
    }

    @Test
    fun `接页与回插的边界页按表算`() {
        val slots = mixed()
        assertEquals(7, slots.lastPage())
        assertEquals(4, slots.firstPage())
        slots.append(8, 2)
        assertEquals(8, slots.lastPage())
        assertEquals(7, slots.size)
        slots.prepend(3, 1)
        assertEquals(3, slots.firstPage())
        assertEquals(3, slots.pageAt(0))
    }

    @Test
    fun `头部插两段时先插的先往后挪，页号仍指向同一页`() {
        val slots = PageSlots()
        slots.rebuild(listOf(6))
        slots.prepend(5, 1)
        slots.prepend(5, 1)
        assertEquals(listOf(5, 5, 6), (0 until slots.size).map { slots.pageAt(it) })
        assertEquals(0, slots.firstSlotOf(5))
    }

    @Test
    fun `丢头部槽位可以只丢一页的上半段`() {
        val slots = mixed()
        assertEquals(1, slots.dropFirst(1))
        assertEquals(5, slots.pageAt(0))
        assertEquals(5, slots.firstPage())
        assertEquals(4, slots.size)
    }

    @Test
    fun `丢弃与画面同口径：至少留一段，越界钳位`() {
        val slots = mixed()
        assertEquals(4, slots.dropFirst(99))
        assertEquals(1, slots.size)
        assertEquals(0, slots.dropFirst(1))
        assertEquals(0, slots.dropLast(1))
        assertEquals(0, slots.dropFirst(-3))
        assertEquals(1, slots.size)
    }

    @Test
    fun `丢尾部后边界页跟着回退`() {
        val slots = mixed()
        assertEquals(1, slots.dropLast(1))
        assertEquals(6, slots.lastPage())
        assertEquals(1, slots.dropLast(1))
        assertEquals(5, slots.lastPage())
    }

    @Test
    fun `重建与清空`() {
        val slots = mixed()
        slots.rebuild(listOf(0, 0, 1))
        assertEquals(3, slots.size)
        assertEquals(0, slots.firstSlotOf(0))
        slots.clear()
        assertEquals(0, slots.size)
        assertNull(slots.firstPage())
        assertNull(slots.lastPage())
    }

    @Test
    fun `页数按整页算，首末页占的槽位数按段算`() {
        val slots = mixed()
        assertEquals(4, slots.pageCount())
        assertEquals(1, slots.slotsOfFirstPage())
        assertEquals(1, slots.slotsOfLastPage())
        slots.rebuild(listOf(5, 5, 6))
        assertEquals(2, slots.pageCount())
        assertEquals(2, slots.slotsOfFirstPage())
        assertEquals(1, slots.slotsOfLastPage())
    }

    @Test
    fun `当前槽位之前有几整页`() {
        val slots = mixed()
        assertEquals(0, slots.pagesBefore(0))
        assertEquals(1, slots.pagesBefore(1))
        assertEquals(1, slots.pagesBefore(2))
        assertEquals(2, slots.pagesBefore(3))
        assertEquals(3, slots.pagesBefore(4))
    }

    @Test
    fun `头部按整页丢：拆开的上下两段一起走`() {
        val slots = mixed()
        assertEquals(1, slots.dropFirstPages(1))
        assertEquals(5, slots.firstPage())
        // 再丢一页是占两段的宽图：两段一起丢，不留半页
        assertEquals(2, slots.dropFirstPages(1))
        assertEquals(6, slots.firstPage())
        assertEquals(-1, slots.firstSlotOf(5))
        assertEquals(2, slots.size)
    }

    @Test
    fun `末尾按整页丢，边界页跟着回退`() {
        val slots = mixed()
        assertEquals(1, slots.dropLastPages(1))
        assertEquals(6, slots.lastPage())
        assertEquals(1, slots.dropLastPages(1))
        assertEquals(5, slots.lastPage())
        assertEquals(2, slots.dropLastPages(1))
        assertEquals(4, slots.lastPage())
        assertEquals(1, slots.size)
    }

    @Test
    fun `整页丢至少留一页，越界钳位`() {
        val slots = PageSlots()
        slots.rebuild(listOf(5, 5))
        assertEquals(0, slots.dropFirstPages(9))
        assertEquals(0, slots.dropLastPages(9))
        assertEquals(2, slots.size)
        assertEquals(0, slots.dropFirstPages(-3))
    }

    @Test
    fun `连丢多页按整页计，不会停在半页上`() {
        val slots = mixed()
        // 丢两整页 = 页4(1段) + 页5(2段) = 3 段
        assertEquals(3, slots.dropFirstPages(2))
        assertEquals(6, slots.firstPage())
        assertEquals(2, slots.size)
    }

    @Test
    fun `短页跨屏重建窗须含屏顶可见页，负偏移才落得回`() {
        // 审计例：页高600、视口2440、位置300 → 锚点在第3页(idx2)、屏顶还在第1页(idx0)
        val r = PageSlots.windowRange(anchor = 2, top = 0, bottom = 3, back = 1, ahead = 4, total = 10)
        assertEquals(0, r.first)
        assertEquals(6, r.last)
        // 常规非恢复场景：顶底=锚点，仍前1后4
        val n = PageSlots.windowRange(anchor = 2, top = 2, bottom = 2, back = 1, ahead = 4, total = 10)
        assertEquals(1, n.first)
        assertEquals(6, n.last)
    }

    @Test
    fun `短页跨屏重建窗须含屏底可见页，恢复位置不被底边界钳`() {
        // 屏底已超锚点+4：窗口向后扩到屏底页；首尾越界钳位
        val r = PageSlots.windowRange(anchor = 2, top = 1, bottom = 8, back = 1, ahead = 4, total = 10)
        assertEquals(1, r.first)
        assertEquals(8, r.last)
        val e = PageSlots.windowRange(anchor = 0, top = 0, bottom = 0, back = 1, ahead = 4, total = 1)
        assertEquals(0, e.first)
        assertEquals(0, e.last)
    }

    @Test
    fun `短页返回后屏顶还在第一页下半段，收窗应裁末尾`() {
        val slots = PageSlots().apply { rebuild(List(14) { it / 2 }) }
        // 恢复到 700 px，再短拖一点：第 1 页第二段仍在屏顶，锚点已到第 3 页。
        assertEquals(PageSlots.TrimSide.END, slots.trimSide(top = 1, anchor = 4, bottom = 7, back = 1, minSlots = 3))
    }

    @Test
    fun `末页上半段进屏底且首尾都可见，收窗等待`() {
        val slots = PageSlots().apply { rebuild(List(14) { it / 2 }) }
        assertEquals(null, slots.trimSide(top = 1, anchor = 6, bottom = 12, back = 1, minSlots = 3))
    }

    @Test
    fun `第一页整个离屏后可裁且优先裁头部`() {
        val slots = PageSlots().apply { rebuild(List(14) { it / 2 }) }
        assertEquals(PageSlots.TrimSide.START, slots.trimSide(top = 2, anchor = 4, bottom = 8, back = 1, minSlots = 3))
    }
}
