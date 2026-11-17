package com.nascomic.reader.library.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动归组的判据单测。样本名与 `_local/sample-lib/系列样本/` 一一对应，
 * 工具改判据时这里必须先红再绿。
 */
class SeriesDetectorTest {

    private fun entry(id: Long, parent: String, title: String) =
        SeriesDetector.Entry(bookId = id, title = title, parent = "系列样本/$parent")

    private val sample = listOf(
        entry(1, "犬夜叉", "[高桥留美子] 犬夜叉 第01卷"),
        entry(2, "犬夜叉", "[高桥留美子] 犬夜叉 第02卷"),
        entry(3, "犬夜叉", "[高桥留美子] 犬夜叉 第03卷"),
        entry(4, "灌篮高手", "[井上雄彦] 灌篮高手 第01卷"),
        entry(5, "灌篮高手", "[井上雄彦] 灌籃高手 第02卷"),
        entry(6, "JOJO", "[荒木飞吕彦] JOJO的奇妙冒险 第01卷"),
        entry(7, "JOJO", "[荒木飞吕彦] JOJO 的奇妙冒险 第02卷"),
        entry(8, "手塚治虫作品", "[手塚治虫] 火之鸟 第01卷"),
        entry(9, "手塚治虫作品", "[手塚治虫] 怪医黑杰克 第01卷"),
        entry(10, "夏日", "[佐藤A] 夏日 第01卷"),
        entry(11, "夏日", "[佐藤B] 夏日 第02卷"),
        entry(12, "外传", "[井上雄彦] 灌篮高手 外传"),
        entry(13, "单本", "[荒木飞吕彦] 岸边露伴一动不动"),
        entry(14, "AKIRA", "[大友克洋] AKIRA 第01卷"),
        entry(15, "AKIRA", "[大友克洋] AKIRA 第02卷"),
    )

    @Test
    fun `同目录多卷判为高置信`() {
        val group = SeriesDetector().detect(sample)
            .first { it.members.map { m -> m.bookId }.contains(1L) }
        assertTrue(group.highConfidence)
        assertEquals(3, group.members.size)
        assertEquals("犬夜叉", group.name)
    }

    @Test
    fun `繁简与空格差异也能并起来且算高置信`() {
        val groups = SeriesDetector().detect(sample).filter { it.highConfidence }
        val kick = groups.first { it.name.contains("灌") }
        val jojo = groups.first { it.name.contains("JOJO") }
        assertEquals(2, kick.members.size)
        assertEquals(2, jojo.members.size)
        // 书号 4/5 与 6/7 各自成组，没有串到别的组里
        assertEquals(setOf(4L, 5L), kick.members.map { it.bookId }.toSet())
        assertEquals(setOf(6L, 7L), jojo.members.map { it.bookId }.toSet())
    }

    @Test
    fun `同作者不同作品不并`() {
        val group = SeriesDetector().detect(sample)
            .first { it.members.map { m -> m.bookId }.contains(8L) }
        assertFalse(group.highConfidence)
        // 只能靠「同一个文件夹」聚成候选，且理由要说人话
        assertEquals(2, group.members.size)
        assertTrue(group.reason.contains("文件夹"))
    }

    @Test
    fun `同名不同作者不自动并`() {
        val group = SeriesDetector().detect(sample)
            .first { it.members.map { m -> m.bookId }.contains(10L) }
        assertFalse(group.highConfidence)
        assertEquals(2, group.members.size)
    }

    @Test
    fun `外传与无卷号的单本都不成组`() {
        val groups = SeriesDetector().detect(sample)
        val ids = groups.flatMap { g -> g.members.map { it.bookId } }
        assertFalse(ids.contains(12L))
        assertFalse(ids.contains(13L))
    }

    @Test
    fun `英数卷号也认`() {
        val groups = SeriesDetector().detect(sample).filter { it.highConfidence }
        assertTrue(groups.any { it.name.contains("AKIRA") })
    }

    @Test
    fun `解析中文与阿拉伯卷号`() {
        assertEquals(3, VolumeNameParser.parse("[高桥留美子] 犬夜叉 第03卷").volume)
        assertEquals(3, VolumeNameParser.parse("犬夜叉 第三卷").volume)
        assertEquals(11, VolumeNameParser.parse("犬夜叉 第十一卷").volume)
        assertEquals(2, VolumeNameParser.parse("AKIRA vol.2").volume)
        assertNull(VolumeNameParser.parse("[井上雄彦] 灌篮高手 外传").volume)
    }

    @Test
    fun `方括号里的汉化组不当作者`() {
        val parsed = VolumeNameParser.parse("[某汉化组][高桥留美子] 犬夜叉 第01卷")
        assertEquals("高桥留美子", parsed.author)
        assertNull(VolumeNameParser.parse("[某汉化组] 犬夜叉 第01卷").author)
    }

    @Test
    fun `归一化去掉空白标点与末尾数字`() {
        assertEquals(
            VolumeNameParser.normalize("JOJO的奇妙冒险"),
            VolumeNameParser.normalize("JOJO 的奇妙冒险"),
        )
        assertEquals("灌篮高手", VolumeNameParser.normalize("灌籃高手"))
        assertEquals("akira", VolumeNameParser.normalize("AKIRA 02"))
    }
}
