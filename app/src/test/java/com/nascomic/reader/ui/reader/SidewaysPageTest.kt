package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 横置页判定：竖版不参与、横着扫进来判 SIDEWAYS、两页并排判 SPREAD、证据不足判 UNKNOWN。
 * 用画出来的页面做样本——字行方向就是判据本身，所以样本里的「字」画成一条条横带。
 */
class SidewaysPageTest {

    private fun blank(w: Int, h: Int) = IntArray(w * h) { 255 }

    private fun fill(a: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        for (y in y0 until y1) {
            if (y < 0) continue
            for (x in x0 until x1) {
                if (x < 0) continue
                a[y * w + x] = value
            }
        }
    }

    /** 一张竖版页：若干条横着排的字行，行与行之间留白 */
    private fun portraitPage(w: Int = 600, h: Int = 800): IntArray {
        val a = blank(w, h)
        var y = 90
        var row = 0
        while (y < h - 90) {
            val len = w - 120 - (row % 4) * 70
            fill(a, w, 60, y, 60 + len, y + 10, 70)
            y += 26
            row++
        }
        // 版心边框：上下封口，模拟分镜
        fill(a, w, 50, 60, w - 50, 64, 40)
        fill(a, w, 50, h - 66, w - 50, h - 62, 40)
        return a
    }

    /** 顺时针转 90°：宽高互换 */
    private fun rotateCw(a: IntArray, w: Int, h: Int): Pair<IntArray, Int> {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x * h + (h - 1 - y)] = a[y * w + x]
            }
        }
        return out to h
    }

    @Test
    fun `竖版页不参与判定`() {
        val page = portraitPage()
        assertEquals(SidewaysPage.Verdict.NONE, SidewaysPage.judge(page, 600, 800))
    }

    @Test
    fun `单页横着扫进来判为横置`() {
        val (page, width) = rotateCw(portraitPage(), 600, 800)
        assertEquals(SidewaysPage.Verdict.SIDEWAYS, SidewaysPage.judge(page, width, 600))
    }

    @Test
    fun `两张竖版页并排判为跨页`() {
        val w = 1200
        val h = 800
        val a = blank(w, h)
        val left = portraitPage(600, 800)
        val right = portraitPage(600, 800)
        for (y in 0 until h) {
            for (x in 0 until 600) {
                a[y * w + x] = left[y * 600 + x]
                a[y * w + 600 + x] = right[y * 600 + x]
            }
        }
        assertEquals(SidewaysPage.Verdict.SPREAD, SidewaysPage.judge(a, w, h))
    }

    @Test
    fun `中间有装订缝就按跨页处理`() {
        val w = 1200
        val h = 800
        val a = blank(w, h)
        // 两侧各画一片横字，中间压一条窄的深色缝
        var y = 120
        while (y < h - 120) {
            fill(a, w, 80, y, w - 80, y + 10, 70)
            y += 26
        }
        fill(a, w, w / 2 - 4, 0, w / 2 + 4, h, 30)
        assertEquals(SidewaysPage.Verdict.SPREAD, SidewaysPage.judge(a, w, h))
    }

    @Test
    fun `空白横图判不出来`() {
        assertEquals(SidewaysPage.Verdict.UNKNOWN, SidewaysPage.judge(blank(800, 600), 800, 600))
    }

    @Test
    fun lowContrastBandsDoNotTriggerRotation() {
        val w = 320
        val h = 236
        val a = IntArray(w * h) { 246 }
        for (x in 23 until w - 24 step 9) {
            fill(a, w, x, 20, x + 2, h - 24, 224)
        }
        assertEquals(SidewaysPage.Verdict.UNKNOWN, SidewaysPage.judge(a, w, h))
    }

    @Test
    fun `接近正方的不算横向页`() {
        val w = 600
        val h = 560
        val a = blank(w, h)
        var y = 40
        while (y < h - 40) {
            fill(a, w, 40, y, w - 40, y + 9, 70)
            y += 24
        }
        assertEquals(SidewaysPage.Verdict.NONE, SidewaysPage.judge(a, w, h))
    }

    @Test
    fun `过长的一条不算横置页`() {
        val w = 2000
        val h = 600
        val a = blank(w, h)
        for (x in 60 until w - 60 step 40) {
            fill(a, w, x, 60, x + 10, h - 60, 70)
        }
        assertEquals(SidewaysPage.Verdict.UNKNOWN, SidewaysPage.judge(a, w, h))
    }

    @Test
    fun `像素不足时不崩`() {
        assertEquals(SidewaysPage.Verdict.UNKNOWN, SidewaysPage.judge(IntArray(4), 800, 600))
        assertEquals(SidewaysPage.Verdict.UNKNOWN, SidewaysPage.judge(IntArray(0), 0, 0))
    }
}
