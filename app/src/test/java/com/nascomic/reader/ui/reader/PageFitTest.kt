package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFitTest {

    private fun assertRatio(width: Int, height: Int, box: PageFit.Box, tol: Float = 0.01f) {
        assertEquals(width.toFloat() / height, box.width / box.height, tol)
    }

    @Test
    fun `竖版单页按宽度铺满`() {
        val boxes = PageFit.fit(1080, 2400, margin = 0, gap = 0, sizes = listOf(1000 to 1500))
        assertEquals(1, boxes.size)
        val box = boxes[0]
        // 宽受容器限制：1080/1000 = 1.08 小于 2400/1500 = 1.6
        assertEquals(1080f, box.width, 0.5f)
        assertEquals(1620f, box.height, 0.5f)
        assertEquals(0f, box.left, 0.5f)
        // 竖直居中
        assertEquals(390f, box.top, 0.5f)
        assertRatio(1000, 1500, box)
    }

    @Test
    fun `横版单页按宽度铺满且竖直居中`() {
        val boxes = PageFit.fit(1080, 2400, margin = 0, gap = 0, sizes = listOf(3000 to 1000))
        val box = boxes[0]
        assertEquals(1080f, box.width, 0.5f)
        assertEquals(360f, box.height, 0.5f)
        assertEquals((2400f - 360f) / 2f, box.top, 0.5f)
        assertRatio(3000, 1000, box)
    }

    @Test
    fun `两页并排各占一半宽`() {
        val boxes = PageFit.fit(1080, 2400, margin = 0, gap = 10, sizes = listOf(1000 to 1500, 1000 to 1500))
        assertEquals(2, boxes.size)
        val slot = (1080 - 10) / 2f
        assertEquals(slot, boxes[0].width, 0.5f)
        assertEquals(slot, boxes[1].width, 0.5f)
        assertEquals(0f, boxes[0].left, 0.5f)
        assertEquals(slot + 10f, boxes[1].left, 0.5f)
        // 两页都竖直居中，顶边一致
        assertEquals(boxes[0].top, boxes[1].top, 0.5f)
    }

    @Test
    fun `边距同时作用于两侧`() {
        val boxes = PageFit.fit(1080, 2400, margin = 100, gap = 10, sizes = listOf(1000 to 1500, 1000 to 1500))
        assertEquals(100f, boxes[0].left, 0.5f)
        // 右侧最后一页的右边缘不超过容器宽减边距
        val last = boxes.last()
        assertTrue(last.right <= 1080 - 100 + 0.5f)
        assertTrue(last.right >= 1080 - 100 - 0.5f)
    }

    @Test
    fun `容器边界不会被越过`() {
        val boxes = PageFit.fit(720, 1280, margin = 0, gap = 8, sizes = listOf(1200 to 1600, 800 to 2400))
        boxes.forEach { box ->
            assertTrue(box.left >= -0.01f)
            assertTrue(box.top >= -0.01f)
            assertTrue(box.right <= 720.01f)
            assertTrue(box.bottom <= 1280.01f)
        }
    }

    @Test
    fun `空输入与非法尺寸不炸`() {
        assertTrue(PageFit.fit(0, 0, 0, 0, listOf(100 to 100)).isEmpty())
        assertTrue(PageFit.fit(100, 100, 0, 0, emptyList()).isEmpty())

        val boxes = PageFit.fit(100, 100, 0, 0, listOf(0 to 0))
        assertEquals(1, boxes.size)
        assertEquals(0f, boxes[0].width, 0.01f)
    }

    @Test
    fun `按宽度铺满时长图上下超出`() {
        val box = PageFit.fit(1080, 2400, 0, 0, listOf(1000 to 3000), fitWidth = true)[0]
        // 宽撑满 1080，高按比例 3240，比容器高 2400 多出 840，上下各露出 420
        assertEquals(1080f, box.width, 0.5f)
        assertEquals(3240f, box.height, 0.5f)
        assertEquals(-420f, box.top, 0.5f)
    }

    @Test
    fun `完整显示时整页可见`() {
        val box = PageFit.fit(1080, 2400, 0, 0, listOf(1000 to 3000), fitWidth = false)[0]
        assertEquals(2400f, box.height, 0.5f)
        assertEquals(800f, box.width, 0.5f)
        assertTrue(box.top >= -0.5f)
        assertTrue(box.bottom <= 2400.5f)
    }
}
