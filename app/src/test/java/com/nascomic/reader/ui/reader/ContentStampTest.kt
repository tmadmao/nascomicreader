package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 页缓存的内容版本。
 *
 * 远端目录书的字节数是 0——章的结构变了，版本必须跟着变，
 * 否则同一个全局页号会读到上一章缓存下来的图。
 */
class ContentStampTest {

    @Test
    fun `同样的结构给出同样的版本`() {
        val a = ContentStamp.of(0, listOf("第1章" to 3, "第2章" to 4), 7)
        val b = ContentStamp.of(0, listOf("第1章" to 3, "第2章" to 4), 7)
        assertEquals(a, b)
    }

    @Test
    fun `前面插一章_版本必须变`() {
        val before = ContentStamp.of(0, listOf("第1章" to 3), 3)
        // 插进一章之后，原本的第 1 页变成了新一章的第 1 页
        val after = ContentStamp.of(0, listOf("第0章" to 2, "第1章" to 3), 5)
        assertNotEquals(before, after)
    }

    @Test
    fun `同一页数换了顺序也算变了`() {
        val a = ContentStamp.of(0, listOf("第1章" to 3, "第2章" to 3), 6)
        val b = ContentStamp.of(0, listOf("第2章" to 3, "第1章" to 3), 6)
        assertNotEquals(a, b)
    }

    @Test
    fun `章内页数变了版本也变`() {
        val a = ContentStamp.of(0, listOf("第1章" to 3), 3)
        val b = ContentStamp.of(0, listOf("第1章" to 4), 4)
        assertNotEquals(a, b)
    }

    @Test
    fun `本地单文件书_字节数参与版本`() {
        val a = ContentStamp.of(1024, listOf("全书" to 20), 20)
        val b = ContentStamp.of(2048, listOf("全书" to 20), 20)
        assertNotEquals(a, b)
    }

    @Test
    fun `目录书重扫版本加一_缓存键必须变`() {
        val chapters = listOf("第1章" to 3)
        val before = ContentStamp.of(0, chapters, 3, contentVersion = 0)
        val after = ContentStamp.of(0, chapters, 3, contentVersion = 1)
        assertNotEquals("换图但页数不变时，版本号是唯一的失效依据", before, after)
    }

    @Test
    fun `单文件书版本号恒零_不误伤`() {
        val a = ContentStamp.of(1024, listOf("全书" to 20), 20, contentVersion = 0)
        val b = ContentStamp.of(1024, listOf("全书" to 20), 20, contentVersion = 0)
        assertEquals(a, b)
    }
}
