package com.nascomic.reader.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterOrderTest {

    private fun sorted(vararg names: String) =
        names.toList().sortedWith(ChapterOrder.comparator { it })

    @Test
    fun `序前上排在正文前，终后下收在最后`() {
        assertEquals(
            listOf("序章", "前篇", "第1话", "第2话", "第10话", "后篇", "终章"),
            sorted("第2话", "终章", "序章", "第10话", "后篇", "第1话", "前篇"),
        )
    }

    @Test
    fun `上中下卷`() {
        // 中卷不在两档里：它按字面排，正好落在正文之前、下卷之后
        assertEquals(
            listOf("上卷", "中卷", "第1话", "第2话", "下卷"),
            sorted("下卷", "第1话", "中卷", "上卷", "第2话"),
        )
    }

    @Test
    fun `单独一个字也认`() {
        assertEquals(
            listOf("序", "第1话", "终"),
            sorted("终", "第1话", "序"),
        )
    }

    @Test
    fun `开头带这些字的正常书名不受影响`() {
        // 下北泽 / 终末的女武神 都不是卷次：档位与正文一样，只按字面排
        assertEquals(
            listOf("下北泽", "第1话", "终末的女武神"),
            sorted("终末的女武神", "第1话", "下北泽"),
        )
    }

    @Test
    fun `档内仍按名称自然序`() {
        assertEquals(
            listOf("序章", "序章二", "序章十", "第1话"),
            sorted("第1话", "序章十", "序章", "序章二"),
        )
    }
}
