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
        val sorted = NaturalSort.sortedBy(names) { it }
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
        val once = NaturalSort.sortedBy(names) { it }
        val twice = NaturalSort.sortedBy(once) { it }
        assertEquals(once, twice)
        assertEquals(listOf("a2", "a10", "b2"), once)
    }
}
