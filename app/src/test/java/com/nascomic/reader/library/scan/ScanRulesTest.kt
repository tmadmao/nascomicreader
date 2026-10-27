package com.nascomic.reader.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulesTest {

    @Test
    fun `pdf 自成一本书，不算章`() {
        assertTrue(ScanRules.isPdf("漫画.pdf"))
        assertTrue(ScanRules.isPdf("A.PDF"))
        assertFalse(ScanRules.isPdf("a.zip"))
        assertFalse(ScanRules.isArchive("a.pdf"))

        // 章回体目录里出现 pdf 时不作为章（当前只认目录与压缩包）
        val children = listOf(
            ScanNode(docId = "d1", name = "第1话", isDir = true),
            ScanNode(docId = "f1", name = "封面.pdf", isDir = false),
        )
        assertEquals(listOf("第1话"), ScanRules.chapterUnitsOf(children).map { it.name })
    }

    private fun file(name: String) = ScanNode(docId = "id:$name", name = name, isDir = false)
    private fun dir(name: String) = ScanNode(docId = "id:$name", name = name, isDir = true)

    @Test
    fun `压缩包判定`() {
        assertTrue(ScanRules.isArchive("a.zip"))
        assertTrue(ScanRules.isArchive("卷1.CBZ"))
        assertTrue(ScanRules.isArchive("a.tar"))
        assertTrue(ScanRules.isArchive("a.7z"))
        assertTrue(ScanRules.isArchive("卷1.RAR"))   // 大小写不敏感
        assertTrue(ScanRules.isArchive("a.cbr"))
        assertFalse(ScanRules.isArchive("a.jpg"))
        assertFalse(ScanRules.isArchive("zip"))
        assertFalse(ScanRules.isArchive("a.zip.txt"))
    }

    @Test
    fun `单行本：目录里直接是图片`() {
        val children = listOf(file("001.jpg"), file("002.jpg"), file("说明.txt"))
        assertEquals(ScanRules.FolderKind.DIRECT_PAGES, ScanRules.folderKind(children))
    }

    @Test
    fun `章回体：目录里是子文件夹`() {
        val children = listOf(dir("第1话"), dir("第2话"), file("封面.jpg") )
        // 注意：根目录混了一张封面图，此时按「直接含图片」判为单行本，
        // 这是有意的取舍——宁可当单行本读，也不要把封面当成一章
        assertEquals(ScanRules.FolderKind.DIRECT_PAGES, ScanRules.folderKind(children))
    }

    @Test
    fun `章回体：目录里只有子文件夹`() {
        val children = listOf(dir("第1话"), dir("第2话"), dir("第3话"))
        assertEquals(ScanRules.FolderKind.BY_CHAPTER, ScanRules.folderKind(children))
    }

    @Test
    fun `章回体：目录里是压缩包`() {
        val children = listOf(file("第1话.zip"), file("第2话.cbz"))
        assertEquals(ScanRules.FolderKind.BY_CHAPTER, ScanRules.folderKind(children))
    }

    @Test
    fun `章回体：子文件夹与压缩包混排`() {
        val children = listOf(dir("第1话"), file("第2话.zip"), file("readme.txt"))
        val units = ScanRules.chapterUnitsOf(children)
        assertEquals(listOf("第1话", "第2话.zip"), units.map { it.name })
    }

    @Test
    fun `章按自然序排`() {
        val children = listOf(
            dir("第10话"), dir("第2话"), file("第1话.zip")
        )
        assertEquals(
            listOf("第1话.zip", "第2话", "第10话"),
            ScanRules.chapterUnitsOf(children).map { it.name }
        )
    }

    @Test
    fun `空目录不构成书`() {
        assertEquals(ScanRules.FolderKind.EMPTY, ScanRules.folderKind(emptyList()))
    }

    @Test
    fun `只有说明文件的目录不构成书`() {
        assertEquals(ScanRules.FolderKind.EMPTY, ScanRules.folderKind(listOf(file("readme.txt"))))
    }

    @Test
    fun `书名取文件名去扩展名`() {
        assertEquals("第1卷", ScanRules.titleOf("第1卷.cbz"))
        assertEquals("a.b", ScanRules.titleOf("a.b.zip"))
        assertEquals("无扩展名", ScanRules.titleOf("无扩展名"))
    }
}
