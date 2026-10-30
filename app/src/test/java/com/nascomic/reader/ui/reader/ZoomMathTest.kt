package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    private val cx = 540f
    private val cy = 1200f

    @Test
    fun `点击点放大后停在原处`() {
        val tapX = 300f
        val tapY = 800f
        val pan = ZoomMath.panTo(tapX, tapY, tapX, tapY, cx, cy, 1.5f)
        // 变换后的屏幕坐标 = (基础 - 中心) * 倍率 + 中心 + 平移
        assertEquals(tapX, (tapX - cx) * 1.5f + cx + pan.x, 0.01f)
        assertEquals(tapY, (tapY - cy) * 1.5f + cy + pan.y, 0.01f)
    }

    @Test
    fun `点击点可以移到屏幕中心`() {
        val tapX = 300f
        val tapY = 800f
        val pan = ZoomMath.panTo(tapX, tapY, cx, cy, cx, cy, 1.5f)
        assertEquals(cx, (tapX - cx) * 1.5f + cx + pan.x, 0.01f)
        assertEquals(cy, (tapY - cy) * 1.5f + cy + pan.y, 0.01f)
    }

    @Test
    fun `页面中心移到屏幕中心`() {
        // 内容中心 (700, 1000) 是个特殊点，不是视图中心
        val pan = ZoomMath.panTo(700f, 1000f, cx, cy, cx, cy, 2f)
        assertEquals(cx, (700f - cx) * 2f + cx + pan.x, 0.01f)
        assertEquals(cy, (1000f - cy) * 2f + cy + pan.y, 0.01f)
    }

    @Test
    fun `内容比视图小时居中`() {
        val pan = ZoomMath.clampPan(
            panX = 0f,
            panY = 0f,
            contentLeft = 200f,
            contentTop = 300f,
            contentRight = 400f,
            contentBottom = 500f,
            viewW = 1000f,
            viewH = 2000f,
            centerX = 500f,
            centerY = 1000f,
            scale = 1f,
        )
        // 宽 200 居中：左边落在 (1000 - 200) / 2 = 400
        assertEquals(400f, 200f + pan.x, 0.01f)
        // 高 200 居中：顶边落在 (2000 - 200) / 2 = 900
        assertEquals(900f, 300f + pan.y, 0.01f)
    }

    @Test
    fun `内容比视图大时边缘不留空`() {
        val args = { panX: Float ->
            ZoomMath.clampPan(
                panX = panX,
                panY = 0f,
                contentLeft = 0f,
                contentTop = 0f,
                contentRight = 2000f,
                contentBottom = 3000f,
                viewW = 1000f,
                viewH = 2000f,
                centerX = 500f,
                centerY = 1000f,
                scale = 1f,
            )
        }
        // 往右拖过头：夹到内容左边贴屏幕左边
        assertEquals(0f, args(500f).x, 0.01f)
        // 往左拖过头：夹到内容右边贴屏幕右边
        assertEquals(-1000f, args(-1500f).x, 0.01f)
        // 中间位置不动
        assertEquals(-400f, args(-400f).x, 0.01f)
    }

    @Test
    fun `放大后内容宽度按倍率算`() {
        // 内容宽 600，放大 2 倍后 1200 > 1000，属于「比视图大」
        fun clamp(panX: Float) = ZoomMath.clampPan(
            panX = panX,
            panY = 0f,
            contentLeft = 200f,
            contentTop = 0f,
            contentRight = 800f,
            contentBottom = 100f,
            viewW = 1000f,
            viewH = 2000f,
            centerX = 500f,
            centerY = 1000f,
            scale = 2f,
        )
        // 左边缘在 (200-500)*2+500 = -100，右边缘 1100；合法区间 [-100, 100]
        assertEquals(0f, clamp(0f).x, 0.01f)
        // 往右拖过头 → 夹到上限
        assertEquals(100f, clamp(500f).x, 0.01f)
        // 往左拖过头 → 夹到下限
        assertEquals(-100f, clamp(-500f).x, 0.01f)
    }
}
