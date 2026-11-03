package com.nascomic.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortTest {

    private fun assertBefore(a: String, b: String) {
        assertTrue(
            "期望 «$a» 排在 «$b» 之前，实际键：${esc(NaturalSort.key(a))} vs ${esc(NaturalSort.key(b))}",
            NaturalSort.key(a) < NaturalSort.key(b)
        )
    }

    private fun esc(s: String) = s.replace("\u0002", "«NUM»")

    @Test
    fun `数字段按数值比较而不是按字符`() {
        assertBefore("第2话", "第10话")
        assertBefore("p1", "p2")
        assertBefore("p2", "p10")
        assertBefore("ch9", "ch10")
    }

    @Test
    fun `前导零不影响顺序`() {
        assertBefore("p007", "p8")
        assertEquals(NaturalSort.key("p7"), NaturalSort.key("p007"))
        assertEquals(NaturalSort.key("p0"), NaturalSort.key("p00"))
    }

    @Test
    fun `大小写不敏感`() {
        assertBefore("a-first", "p1")
        assertEquals(NaturalSort.key("Page5"), NaturalSort.key("page5"))
    }

    @Test
    fun `数字优先于标点`() {
        // "page5" 的第五个字符是数字段，"-" 是标点；数字段应更小
        assertBefore("page5", "page-4")
    }

    @Test
    fun `全角与半角等价`() {
        assertEquals(NaturalSort.key("ｐ１"), NaturalSort.key("p1"))
        assertEquals(NaturalSort.key("第　3　页"), NaturalSort.key("第 3 页"))
    }

    @Test
    fun `典型书页名序列`() {
        val names = listOf(
            "p10.jpg", "p2.jpg", "p1.jpg", "a-first.jpg",
            "第3页.jpg", "page-4.jpg", "Page5.jpg", "封面.jpg", "p07.jpg", "z-last.jpg"
        )
        val sorted = NaturalSort.sortedByName(names) { it }
        println(sorted.joinToString(" | "))

        assertEquals(
            listOf(
                "a-first.jpg",
                "p1.jpg", "p2.jpg", "p07.jpg", "p10.jpg",   // 数字段按值
                "Page5.jpg", "page-4.jpg",                   // 数字段排在标点之前
                "z-last.jpg",                                // 拉丁字母
                "封面.jpg", "第3页.jpg"                        // 中文按码位，排在拉丁之后
            ),
            sorted
        )
    }

    @Test
    fun `空串与纯数字不炸`() {
        assertEquals("", NaturalSort.key(""))
        assertBefore("1", "10")
        assertBefore("9", "10")
    }

    @Test
    fun `排序稳定且幂等`() {
        val names = listOf("b2", "a10", "a2")
        val once = NaturalSort.sortedByName(names) { it }
        val twice = NaturalSort.sortedByName(once) { it }
        assertEquals(once, twice)
        assertEquals(listOf("a2", "a10", "b2"), once)
    }

    @Test
    fun `中文数字当序号`() {
        val names = listOf("第十话", "第二话", "第一话", "第九话", "第三话", "第十二话", "第二十话")
        assertEquals(
            listOf("第一话", "第二话", "第三话", "第九话", "第十话", "第十二话", "第二十话"),
            names.sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `繁体与日文量词同样认`() {
        assertEquals(
            listOf("第一話", "第二話", "第十話"),
            listOf("第十話", "第一話", "第二話").sortedWith(NaturalSort.comparator())
        )
        assertEquals(
            listOf("其ノ一", "其ノ二", "其ノ十"),
            listOf("其ノ十", "其ノ一", "其ノ二").sortedWith(NaturalSort.comparator())
        )
        assertEquals(
            listOf("卷一", "卷二", "卷十"),
            listOf("卷十", "卷一", "卷二").sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `中文数字与阿拉伯数字混排`() {
        val names = listOf("第10话", "第二话", "第03话", "第四话", "第5话", "第六话")
        assertEquals(
            listOf("第二话", "第03话", "第四话", "第5话", "第六话", "第10话"),
            names.sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `百千与上千的话数`() {
        assertEquals(
            listOf("第99话", "第100话", "第999话", "第1000话"),
            listOf("第1000话", "第999话", "第100话", "第99话").sortedWith(NaturalSort.comparator())
        )
        assertEquals(
            listOf("第百话", "第两百话"),
            listOf("第两百话", "第百话").sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `罗马数字当卷号`() {
        val names = listOf("Vol.X", "Vol.IV", "Vol.II", "Vol.I", "Vol.IX", "Vol.V", "Vol.VIII")
        assertEquals(
            listOf("Vol.I", "Vol.II", "Vol.IV", "Vol.V", "Vol.VIII", "Vol.IX", "Vol.X"),
            names.sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `不是卷号的罗马数字不动它`() {
        // 整词不是罗马字符（CLAMP / DIVE），或没有量词语境（MIX），都不该被换成数字
        assertEquals("clamp", NaturalSort.key("CLAMP"))
        assertEquals("dive", NaturalSort.key("DIVE"))
        assertEquals("mix", NaturalSort.key("MIX"))
        // 单个 X 也留作字母
        assertEquals("x", NaturalSort.key("X"))
    }

    @Test
    fun `书名里的中文数字不当序号`() {
        // 没有量词语境，键里不该冒出数字段：书架按名称排序时，这类书名不该跑到数字开头那一堆
        assertEquals("一人之下", NaturalSort.key("一人之下"))
        assertEquals("三体", NaturalSort.key("三体"))
        assertEquals("二十世纪少年", NaturalSort.key("二十世纪少年"))
    }

    @Test
    fun `季与集分开排`() {
        val names = listOf("第一季 - 02", "第二季 - 01", "第一季 - 01", "第三季 - 01")
        assertEquals(
            listOf("第一季 - 01", "第一季 - 02", "第二季 - 01", "第三季 - 01"),
            names.sortedWith(NaturalSort.comparator())
        )
    }

    @Test
    fun `字幕组常见的一长串命名`() {
        val names = listOf(
            "[VCB-Studio] Title [03][Ma10p_1080p][x265_flac].mkv",
            "[VCB-Studio] Title [01][Ma10p_1080p][x265_flac].mkv",
            "[VCB-Studio] Title [10][Ma10p_1080p][x265_flac].mkv",
            "[VCB-Studio] Title [02][Ma10p_1080p][x265_flac].mkv",
        )
        assertEquals(
            listOf(1, 2, 3, 10),
            names.sortedWith(NaturalSort.comparator())
                .map { Regex("\\[(\\d+)]\\[Ma10p").find(it)!!.groupValues[1].toInt() }
        )
    }
}
