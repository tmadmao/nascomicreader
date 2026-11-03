package com.nascomic.reader.library.scan

import com.nascomic.reader.library.scan.ChapterReorder.ChapterSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterReorderTest {

    private fun slots(vararg refs: String) = refs.mapIndexed { i, ref -> ChapterSlot(ref, (i + 1) * 10) }

    @Test
    fun `章序变了，进度跟到新章号上`() {
        // 旧库里的顺序是列目录给的；重扫之后按名称排成 vol_01、vol_02、vol_03
        val old = slots("vol_03", "vol_01", "vol_02")
        val newRefs = listOf("vol_01", "vol_02", "vol_03")

        assertEquals(2, ChapterReorder.newOrdinal(old, newRefs, 0))
        assertEquals(0, ChapterReorder.newOrdinal(old, newRefs, 1))
        assertEquals(1, ChapterReorder.newOrdinal(old, newRefs, 2))
    }

    @Test
    fun `全局页号按新序重算`() {
        // 页数：vol_03 = 10、vol_01 = 20、vol_02 = 30
        val old = listOf(
            ChapterSlot("vol_03", 10),
            ChapterSlot("vol_01", 20),
            ChapterSlot("vol_02", 30),
        )
        val newRefs = listOf("vol_01", "vol_02", "vol_03")

        // 原来读到 vol_01 的第 4 页（旧全局页号 10 + 4）：新序里 vol_01 在开头
        assertEquals(4, ChapterReorder.newPageGlobal(old, newRefs, 0, 4))

        // 原来读到 vol_02 的第 1 页：新序里前面是 vol_01 的 20 页
        assertEquals(21, ChapterReorder.newPageGlobal(old, newRefs, 1, 1))

        // 原来读到 vol_03 的第 9 页：新序里前面是 20 + 30 页
        assertEquals(59, ChapterReorder.newPageGlobal(old, newRefs, 2, 9))
    }

    @Test
    fun `页数没量全时不猜页号`() {
        val old = listOf(
            ChapterSlot("vol_03", 0),     // 还没打开过，页数不明
            ChapterSlot("vol_01", 20),
        )
        val newRefs = listOf("vol_01", "vol_03")
        assertNull(ChapterReorder.newPageGlobal(old, newRefs, 1, 3))
    }

    @Test
    fun `章被删掉时不动它的进度`() {
        val old = slots("vol_01", "vol_02")
        val newRefs = listOf("vol_01")
        assertNull(ChapterReorder.newOrdinal(old, newRefs, 1))
    }

    @Test
    fun `顺序没变时映射是恒等`() {
        val old = slots("a", "b", "c")
        val newRefs = listOf("a", "b", "c")
        for (i in 0..2) assertEquals(i, ChapterReorder.newOrdinal(old, newRefs, i))
        assertEquals(30 + 3, ChapterReorder.newPageGlobal(old, newRefs, 2, 3))
    }
}
