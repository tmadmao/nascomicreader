package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁盘缓存的两块纯逻辑：文件名口径与淘汰顺序。
 * 都按「字节 + 时刻」算，不碰文件系统，所以能在 JVM 上跑。
 */
class DiskPageCacheTest {

    // ---- 文件名口径 ----

    @Test
    fun `键里带页号 尺寸 裁边参数`() {
        val key = PageCacheKey.of(7, 1080, 1920, trim = true, level = 236)
        assertTrue(key.startsWith("p00007-1080x1920-t1236"))
    }

    @Test
    fun `换尺寸就是另一个键`() {
        assertNotEquals(
            PageCacheKey.of(1, 1080, 1920, true, 236),
            PageCacheKey.of(1, 540, 960, true, 236),
        )
    }

    @Test
    fun `裁边开关与阈值也算在键里`() {
        val base = PageCacheKey.of(1, 1080, 1920, true, 236)
        assertNotEquals(base, PageCacheKey.of(1, 1080, 1920, false, 236))
        assertNotEquals(base, PageCacheKey.of(1, 1080, 1920, true, 220))
    }

    @Test
    fun `页号补零 便于按名字排序`() {
        val keys = (1..12).map { PageCacheKey.of(it, 100, 200, true, 236) }
        assertEquals(keys.sorted(), keys)
    }

    // ---- 淘汰顺序 ----

    private fun item(path: String, mb: Long, at: Long) =
        CacheTrim.Item(path, mb * 1024 * 1024, at)

    @Test
    fun `没超上限一个都不删`() {
        val items = listOf(item("a", 10, 1), item("b", 10, 2))
        assertTrue(CacheTrim.victims(items, keepBytes = 64L * 1024 * 1024).isEmpty())
    }

    @Test
    fun `超了就按最久没用先删`() {
        val items = listOf(item("new", 40, 300), item("old", 40, 100), item("mid", 40, 200))
        val victims = CacheTrim.victims(items, keepBytes = 64L * 1024 * 1024)
        // 总 120MB 收到 80% × 64 = 51.2MB，删掉最老的两条才够
        assertEquals(listOf("old", "mid"), victims)
    }

    @Test
    fun `一次收到八成 不是刚好卡在上限`() {
        val items = listOf(item("a", 30, 1), item("b", 30, 2), item("c", 30, 3))
        val victims = CacheTrim.victims(items, keepBytes = 60L * 1024 * 1024)
        // 90MB 超 60；目标 48MB，删 a、b 后剩 30 才达标，不能只删 a
        assertEquals(listOf("a", "b"), victims)
    }

    @Test
    fun `空目录不炸`() {
        assertTrue(CacheTrim.victims(emptyList(), keepBytes = 1024).isEmpty())
    }

    @Test
    fun `压缩质量不同就是另一个键`() {
        val normal = PageCacheKey.of(1, 1080, 1920, true, 236, quality = 88)
        val original = PageCacheKey.of(1, 1080, 1920, true, 236, quality = 96)
        assertNotEquals(normal, original)
    }

    @Test
    fun `书换了内容就换一套键`() {
        val before = PageCacheKey.of(1, 1080, 1920, false, 236, 88, stamp = 100)
        val after = PageCacheKey.of(1, 1080, 1920, false, 236, 88, stamp = 200)
        assertNotEquals(before, after)
        assertTrue(after.startsWith("s200-"))
    }
}
