package com.nascomic.reader.library.author

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorAliasesTest {

    @Test
    fun `没有别名时原样返回`() {
        assertEquals("张三", AuthorAliases.resolve("张三", emptyMap()))
    }

    @Test
    fun `别名链压平后顺着走到底`() {
        val map = mapOf("A" to "B", "B" to "C")
        assertEquals("C", AuthorAliases.resolve("A", map))
        assertEquals("C", AuthorAliases.resolve("B", map))
        assertEquals("C", AuthorAliases.resolve("C", map))
    }

    @Test
    fun `合并 B 到 C 时指向 B 的旧名一起改指 C`() {
        val map = mapOf("A" to "B", "B" to "C")
        val merged = AuthorAliases.merged(map, from = "B", to = "C")

        // A 不再走两层，直接落到 C；B 自己那行也在
        assertEquals("C", merged["A"])
        assertEquals("C", merged["B"])
        assertEquals("C", AuthorAliases.resolve("A", merged))
    }

    @Test
    fun `合并到还没出现过的新名字`() {
        val merged = AuthorAliases.merged(mapOf("A" to "B"), from = "A", to = "新名")
        assertEquals("新名", merged["A"])
        // 原来 A 指向的 B 不是正名，表里没有 B 这一行
        assertEquals(null, merged["B"])
    }

    @Test
    fun `合并到自己是空操作`() {
        val map = mapOf("A" to "B")
        assertEquals(map, AuthorAliases.merged(map, from = "C", to = "C"))
    }

    @Test
    fun `人为写坏的环不会卡死`() {
        val map = mapOf("A" to "B", "B" to "A")
        // 停在环里第一个能确定的名字，不抛异常也不死循环
        assertEquals("A", AuthorAliases.resolve("A", map))
        assertEquals("B", AuthorAliases.resolve("B", map))
    }
}
