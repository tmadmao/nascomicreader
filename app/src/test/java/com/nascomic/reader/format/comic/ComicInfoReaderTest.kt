package com.nascomic.reader.format.comic

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicInfoReaderTest {

    private fun xml(body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema">
        $body
        </ComicInfo>""".trimIndent()

    @Test
    fun `作者与卷号`() {
        val meta = ComicInfoReader.parse(xml(
            """
            <Series>示例系列</Series>
            <Number>3</Number>
            <Volume>2020</Volume>
            <Writer>示例作者</Writer>
            """.trimIndent()
        ))
        assertEquals("示例作者", meta.writer)
        // Number 是册号，优先于 Volume；2020 那种年份 Volume 本就不该当卷号
        assertEquals("3", meta.volume)
    }

    @Test
    fun `只有 Volume 时用 Volume`() {
        val meta = ComicInfoReader.parse(xml("<Volume>7</Volume>"))
        assertEquals("7", meta.volume)
    }

    @Test
    fun `Volume 填的是年份就当没有`() {
        val meta = ComicInfoReader.parse(xml("<Volume>2021</Volume>"))
        assertNull(meta.volume)
    }

    @Test
    fun `Writer 缺了退回 Penciller`() {
        val meta = ComicInfoReader.parse(xml("<Penciller>村田雄介</Penciller>"))
        assertEquals("村田雄介", meta.writer)
    }

    @Test
    fun `空标签当作没有`() {
        val meta = ComicInfoReader.parse(xml("<Writer>  </Writer><Number></Number>"))
        assertNull(meta.writer)
        assertNull(meta.volume)
        assertTrue(meta.isEmpty)
    }

    @Test
    fun `CDATA 里的文字照读`() {
        val meta = ComicInfoReader.parse(xml("<Writer><![CDATA[藤子·F·不二雄]]></Writer>"))
        assertEquals("藤子·F·不二雄", meta.writer)
    }

    @Test
    fun `实体被还原`() {
        val meta = ComicInfoReader.parse(xml("<Writer>Tom &amp; Jerry</Writer>"))
        assertEquals("Tom & Jerry", meta.writer)
    }

    @Test
    fun `标签带属性也认`() {
        val meta = ComicInfoReader.parse(xml("""<Writer lang="ja">手塚治虫</Writer>"""))
        assertEquals("手塚治虫", meta.writer)
    }

    @Test
    fun `前导零归一`() {
        val meta = ComicInfoReader.parse(xml("<Number>001</Number>"))
        assertEquals("1", meta.volume)
    }

    @Test
    fun `非数字卷号原样留着`() {
        val meta = ComicInfoReader.parse(xml("<Number>特别篇</Number>"))
        assertEquals("特别篇", meta.volume)
    }

    @Test
    fun `根目录优先于子目录`() {
        val picked = ComicInfoReader.pick(
            listOf("sub/ComicInfo.xml", "ComicInfo.xml", "p1.jpg")
        )
        assertEquals("ComicInfo.xml", picked)
    }

    @Test
    fun `跳过 macOS 资源目录`() {
        val picked = ComicInfoReader.pick(listOf("__MACOSX/ComicInfo.xml", "inner/ComicInfo.xml"))
        assertEquals("inner/ComicInfo.xml", picked)
    }

    @Test
    fun `文件名大小写不敏感`() {
        assertEquals("comicinfo.XML", ComicInfoReader.pick(listOf("comicinfo.XML")))
        assertEquals("ComicInfo.xml", ComicInfoReader.pick(listOf("a.jpg", "ComicInfo.xml")))
    }

    @Test
    fun `没有这个文件就返回空`() {
        assertNull(ComicInfoReader.pick(listOf("001.jpg", "002.jpg")))
        assertNull(ComicInfoReader.read(listOf("001.jpg")) { ByteArrayInputStream(ByteArray(0)) })
    }

    @Test
    fun `读不出来不抛异常`() {
        val result = ComicInfoReader.read(listOf("ComicInfo.xml")) { null }
        assertNull(result)
    }

    @Test
    fun `注释里的伪标签不算`() {
        val meta = ComicInfoReader.parse(xml(
            """
            <!-- <Writer>假的</Writer> -->
            <Writer>真的</Writer>
            """.trimIndent()
        ))
        assertEquals("真的", meta.writer)
    }

    @Test
    fun `名字相近的文件不算`() {
        assertNull(ComicInfoReader.pick(listOf("MyComicInfo.xml", "BackupComicInfo.xml")))
        assertEquals(
            "ComicInfo.xml",
            ComicInfoReader.pick(listOf("MyComicInfo.xml", "ComicInfo.xml")),
        )
    }

    @Test
    fun `反斜杠分隔的路径也认`() {
        assertEquals(
            "sub\\ComicInfo.xml",
            ComicInfoReader.pick(listOf("sub\\ComicInfo.xml")),
        )
    }

    @Test
    fun `超过大小上限就不读`() {
        val huge = "x".repeat(ComicInfoReader.MAX_BYTES + 1)
        val result = ComicInfoReader.read(listOf("ComicInfo.xml")) {
            ByteArrayInputStream(huge.toByteArray())
        }
        assertNull(result)
    }

    @Test
    fun `带 DTD 的不解析`() {
        val meta = ComicInfoReader.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE ComicInfo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <ComicInfo><Writer>&xxe;</Writer></ComicInfo>
            """.trimIndent()
        )
        assertNull(meta.writer)
    }

    @Test
    fun `完整链路：从条目名到元数据`() {
        val text = xml("<Writer>荒木飞吕彦</Writer><Number>12</Number>")
        val meta = ComicInfoReader.read(listOf("ComicInfo.xml")) {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        assertEquals("荒木飞吕彦", meta?.writer)
        assertEquals("12", meta?.volume)
    }
}
