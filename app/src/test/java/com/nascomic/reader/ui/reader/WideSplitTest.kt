package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WideSplitTest {

    private val full = WideSplit.Box(10, 20, 210, 120)

    @Test
    fun `两半相接且合起来正好是整页`() {
        val left = WideSplit.halfAt(full, 0)
        val right = WideSplit.halfAt(full, 1)
        assertEquals(left.right, right.left)
        assertEquals(full.left, left.left)
        assertEquals(full.right, right.right)
        assertEquals(full.top, left.top)
        assertEquals(full.bottom, right.bottom)
        assertEquals(full.width, left.width + right.width)
    }

    @Test
    fun `带裁边偏移的矩形按绝对坐标切，不是按零起点`() {
        val left = WideSplit.halfAt(full, 0)
        assertEquals(10, left.left)
        assertEquals(110, left.right)
        assertEquals(20, left.top)
        assertEquals(120, left.bottom)
    }

    @Test
    fun `奇数宽时中点向下取整，右半多一列`() {
        val odd = WideSplit.Box(0, 0, 101, 50)
        assertEquals(50, WideSplit.halfAt(odd, 0).width)
        assertEquals(51, WideSplit.halfAt(odd, 1).width)
    }

    @Test
    fun `先看左半时左半排在前面`() {
        val halves = WideSplit.orderedHalves(full, firstHalf = 0)
        assertEquals(WideSplit.halfAt(full, 0), halves[0])
        assertEquals(WideSplit.halfAt(full, 1), halves[1])
    }

    @Test
    fun `先看右半时右半排在前面，矩形本身不变`() {
        val halves = WideSplit.orderedHalves(full, firstHalf = 1)
        assertEquals(WideSplit.halfAt(full, 1), halves[0])
        assertEquals(WideSplit.halfAt(full, 0), halves[1])
    }

    @Test
    fun `两档顺序输出互为倒序，内容不重不漏`() {
        val lr = WideSplit.orderedHalves(full, 0)
        val rl = WideSplit.orderedHalves(full, 1)
        assertEquals(lr, rl.reversed())
    }

    @Test
    fun `先看左半时前进从左到右，右半之后翻页`() {
        assertEquals(1, WideSplit.nextHalf(0, firstHalf = 0))
        assertNull(WideSplit.nextHalf(1, firstHalf = 0))
    }

    @Test
    fun `先看右半时前进从右到左，左半之后翻页`() {
        assertEquals(0, WideSplit.nextHalf(1, firstHalf = 1))
        assertNull(WideSplit.nextHalf(0, firstHalf = 1))
    }

    @Test
    fun `先看左半时后退从右回左，左半之前翻上一页`() {
        assertEquals(0, WideSplit.prevHalf(1, firstHalf = 0))
        assertNull(WideSplit.prevHalf(0, firstHalf = 0))
    }

    @Test
    fun `先看右半时后退从左回右，右半之前翻上一页`() {
        assertEquals(1, WideSplit.prevHalf(0, firstHalf = 1))
        assertNull(WideSplit.prevHalf(1, firstHalf = 1))
    }

    @Test
    fun `入页落点：正着进看第一半，从后往前进看最后读的那半`() {
        assertEquals(0, WideSplit.entryHalf(0, fromBehind = false))
        assertEquals(1, WideSplit.entryHalf(0, fromBehind = true))
        assertEquals(1, WideSplit.entryHalf(1, fromBehind = false))
        assertEquals(0, WideSplit.entryHalf(1, fromBehind = true))
    }

    @Test
    fun `两档顺序下前进后退互为逆操作，半页不重不漏`() {
        for (first in 0..1) {
            val entry = WideSplit.entryHalf(first, fromBehind = false)
            val second = WideSplit.nextHalf(entry, first)
            assertEquals(1 - first, second)
            assertEquals(entry, WideSplit.prevHalf(second!!, first))
            assertNull(WideSplit.nextHalf(second, first))
            // 从后往前进落在第二半，后退一步回第一半，再后退就翻上一页
            val behind = WideSplit.entryHalf(first, fromBehind = true)
            assertEquals(second, behind)
            assertEquals(entry, WideSplit.prevHalf(behind, first))
        }
    }
}