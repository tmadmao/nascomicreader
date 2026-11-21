package com.nascomic.reader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBitmapLoaderTest {

    @Test
    fun `普通页不预览`() {
        // 1080×1620 对 1600×4800 的屏：只解一百多万像素，本来就快
        assertFalse(PageBitmapLoader.needsPreview(1080, 1620, 1600, 4800))
    }

    @Test
    fun `千万像素的扫描页要预览`() {
        // 3000×4500 ≈ 1350 万像素，实测解到屏宽要 200 ms 上下
        assertTrue(PageBitmapLoader.needsPreview(3000, 4500, 1600, 4800))
    }

    @Test
    fun `按降采样之后的像素算`() {
        // 小图放大到满屏：源就那么多像素，解出来也快
        assertFalse(PageBitmapLoader.needsPreview(800, 1200, 1600, 4000))
        // 九千万像素的扫描件降到 4500×6000 仍有两千七百万，照样该先给一眼
        assertTrue(PageBitmapLoader.needsPreview(9000, 12000, 1600, 4800))
    }

    @Test
    fun `卡在门槛上`() {
        // 2000×3000 正好六百万
        assertTrue(PageBitmapLoader.needsPreview(2000, 3000, 1600, 4000))
        // 少一个像素就不够
        assertFalse(PageBitmapLoader.needsPreview(2000, 2999, 1600, 4000))
    }

    @Test
    fun `尺寸非法时安全返回`() {
        assertFalse(PageBitmapLoader.needsPreview(0, 0, 1080, 1440))
        assertFalse(PageBitmapLoader.needsPreview(-1, 100, 1080, 1440))
        assertFalse(PageBitmapLoader.needsPreview(3000, 4000, 0, 0))
    }
}
