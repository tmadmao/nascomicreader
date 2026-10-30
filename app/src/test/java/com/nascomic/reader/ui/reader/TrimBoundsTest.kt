package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimBoundsTest {

    /** 用一个布尔表描述「哪些行/列是白边」，便于断言裁剪结果 */
    private fun detect(
        w: Int,
        h: Int,
        whiteTop: Int = 0,
        whiteBottom: Int = 0,
        whiteLeft: Int = 0,
        whiteRight: Int = 0,
        protect: Float = 0.25f,
    ): TrimBounds.Bounds = TrimBounds.detect(
        w = w,
        h = h,
        protect = protect,
        whiteRow = { y -> y < whiteTop || y >= h - whiteBottom },
        whiteCol = { x -> x < whiteLeft || x >= w - whiteRight },
    )

    @Test
    fun `四边都有白边时裁到内容边界`() {
        val b = detect(1000, 1500, whiteTop = 100, whiteBottom = 80, whiteLeft = 40, whiteRight = 60)
        assertEquals(40, b.left)
        assertEquals(100, b.top)
        assertEquals(940, b.right)
        assertEquals(1420, b.bottom)
        assertEquals(900, b.width)
        assertEquals(1320, b.height)
    }

    @Test
    fun `没有白边时保持整页`() {
        val b = detect(800, 1200)
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(800, b.right)
        assertEquals(1200, b.bottom)
    }

    @Test
    fun `整页全白时不会被裁成空`() {
        val b = TrimBounds.detect(
            w = 500,
            h = 700,
            protect = 0.25f,
            whiteRow = { true },
            whiteCol = { true },
        )
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(500, b.right)
        assertEquals(700, b.bottom)
    }

    @Test
    fun `单边裁剪不超过保护比例`() {
        // 上半部 90% 都是白，保护 25% 只能裁掉 1/4
        val b = TrimBounds.detect(
            w = 1000,
            h = 1000,
            protect = 0.25f,
            whiteRow = { y -> y < 900 },
            whiteCol = { false },
        )
        assertEquals(250, b.top)
        assertEquals(1000, b.bottom)
    }

    @Test
    fun `非法尺寸直接返回原页`() {
        val b = TrimBounds.detect(0, 0, 0.25f, { true }, { true })
        assertEquals(0, b.width)
        assertEquals(0, b.height)
    }

    @Test
    fun `单边最多裁掉一成 —— 规格约定的保护比例`() {
        // 整页几乎全白：四个方向都顶到保护上限，应当原页返回，不裁成一条
        val b = TrimBounds.detect(
            w = 1000,
            h = 1000,
            protect = TrimBounds.PROTECT,
            whiteRow = { true },
            whiteCol = { true },
        )
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(1000, b.right)
        assertEquals(1000, b.bottom)
    }

    @Test
    fun `保护比例是一成`() {
        assertEquals(0.10f, TrimBounds.PROTECT, 0.0001f)
    }
}
