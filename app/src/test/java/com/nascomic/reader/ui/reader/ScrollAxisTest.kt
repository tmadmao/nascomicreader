package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAxisTest {

    private fun axis(total: Float = 1000f, viewport: Float = 100f) = ScrollAxis().apply {
        this.total = total
        this.viewport = viewport
    }

    @Test
    fun `亚像素小位移不报到头，逐帧累计进位置`() {
        val a = axis()
        // 每帧 0.3px：不足半像素也照样累计，绝不能报边界
        repeat(3) { assertTrue(a.scrollBy(0.3f)) }
        assertEquals(0.9f, a.position, 1e-4f)
        assertTrue(a.scrollBy(0.3f))
        assertEquals(1.2f, a.position, 1e-4f)
    }

    @Test
    fun `低速档一路滚到底，只有真到边界才报 false`() {
        val a = axis()
        var frames = 0
        while (a.scrollBy(0.4f)) {
            frames++
            assertTrue("亚像素帧数不该触发边界", frames < 10000)
        }
        // 边界容差 0.5px：滚到距底不足半像素即报边界，与画面「到底」同口径
        assertEquals(900f, a.position, 0.5f)
        // (900-0.5)/0.4 ≈ 2249 帧，远多于「一帧不足半像素就误判」的情形
        assertTrue(frames >= 2248)
    }

    @Test
    fun `到底后正向报 false，反向仍可滚`() {
        val a = axis()
        a.scrollTo(900f)
        assertFalse(a.scrollBy(5f))
        assertTrue(a.scrollBy(-5f))
        assertEquals(895f, a.position, 1e-4f)
    }

    @Test
    fun `到顶后负向报 false，正向仍可滚`() {
        val a = axis()
        a.scrollTo(0f)
        assertFalse(a.scrollBy(-5f))
        assertTrue(a.scrollBy(5f))
        assertEquals(5f, a.position, 1e-4f)
    }

    @Test
    fun `大位移钳到边界不越界，下一帧才报边界`() {
        val a = axis()
        a.scrollTo(895f)
        assertTrue(a.scrollBy(100f))
        assertEquals(900f, a.position, 1e-4f)
        assertFalse(a.scrollBy(1f))
    }

    @Test
    fun `负方向同样累计亚像素`() {
        val a = axis()
        a.scrollTo(100f)
        repeat(4) { assertTrue(a.scrollBy(-0.3f)) }
        assertEquals(98.8f, a.position, 1e-4f)
    }

    @Test
    fun `头部插丢内容按量补偿位置`() {
        val a = axis()
        a.scrollTo(500f)
        a.shift(200f)
        assertEquals(700f, a.position, 1e-4f)
        a.shift(-900f)
        assertEquals(0f, a.position, 1e-4f)
    }

    @Test
    fun `直接跳位立即生效，后续小位移接着累计`() {
        val a = axis()
        a.scrollBy(0.3f)
        a.scrollTo(10f)
        a.scrollBy(0.3f)
        assertEquals(10.3f, a.position, 1e-4f)
    }

    @Test
    fun `内容变短后位置钳回界内`() {
        val a = axis()
        a.scrollTo(800f)
        a.total = 500f
        a.clamp()
        assertEquals(400f, a.position, 1e-4f)
    }

    @Test
    fun `零位移不动也不报边界`() {
        val a = axis()
        a.scrollTo(50f)
        assertTrue(a.scrollBy(0f))
        assertEquals(50f, a.position, 1e-4f)
    }

    @Test
    fun `到顶到底判定与边界容差同口径`() {
        val a = axis()
        assertTrue(a.atTop)
        assertFalse(a.atBottom)
        a.scrollTo(900f)
        assertTrue(a.atBottom)
        assertFalse(a.atTop)
    }

    @Test
    fun `重置回零`() {
        val a = axis()
        a.scrollTo(500f)
        a.reset()
        assertEquals(0f, a.position, 1e-4f)
        assertTrue(a.atTop)
    }

    @Test
    fun `丢头部内容先补偿再按新边界钳位，画面不回跳`() {
        val a = axis(total = 1050f)
        a.scrollTo(950f)
        // 丢 200px：先补偿到 750，再按新总高钳位（新边界 800）仍 750
        a.shift(-200f)
        a.total = 900f
        a.clamp()
        assertEquals(750f, a.position, 1e-4f)
    }

    @Test
    fun `被边界截住的位移可按位置差算余量，接页后接着滚完`() {
        val a = axis()
        a.scrollTo(850f)
        a.scrollBy(200f)
        assertEquals(900f, a.position, 1e-4f)
        val rest = 200f - (a.position - 850f)
        assertEquals(150f, rest, 1e-4f)
        // 接上一页后总高变大，余量一次滚完
        a.total = 1400f
        assertTrue(a.scrollBy(rest))
        assertEquals(1050f, a.position, 1e-4f)
    }

    @Test
    fun `视口跨两页时偏移为负，重建窗口落回原处不前跳`() {
        // 审计例：视口 2440、下一页从 1400 起、位置 600 → 锚点已切下一页但屏幕顶还在上一页
        val a = axis(total = 6000f, viewport = 2440f)
        a.scrollTo(600f)
        val offset = a.offsetFrom(1400f)
        assertEquals(-800f, offset, 1e-4f)
        // 重建窗口后按 有符号偏移 落回：仍是 600，不是页头 1400
        val b = axis(total = 6000f, viewport = 2440f)
        b.scrollTo(1400f + offset)
        assertEquals(600f, b.position, 1e-4f)
    }

    @Test
    fun `负偏移落点越出窗口顶时钳到零，不出负位置`() {
        val a = axis()
        a.scrollTo(30f)
        val offset = a.offsetFrom(200f)
        assertEquals(-170f, offset, 1e-4f)
        a.scrollTo(100f + offset)
        assertEquals(0f, a.position, 1e-4f)
    }
}