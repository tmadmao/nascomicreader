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

        // 章回体目录里出现 pdf 时也算一章——按话存 PDF 是常见存法
        val children = listOf(
            ScanNode(docId = "d1", name = "第1话", isDir = true),
            ScanNode(docId = "f1", name = "封面.pdf", isDir = false),
        )
        assertEquals(
            setOf("第1话", "封面.pdf"),
            ScanRules.chapterUnitsOf(children).map { it.name }.toSet(),
        )
    }

    @Test
    fun `章的排序可切`() {
        val children = listOf(
            dir("第10话"),
            dir("第2话"),
            dir("第1话"),
        )
        // 自然序：第1话、第2话、第10话
        assertEquals(
            listOf("第1话", "第2话", "第10话"),
            ScanRules.chapterUnitsOf(children).map { it.name },
        )
    }

    @Test
    fun `目录名里的点算名字，不当扩展名切掉`() {
        // vol.01 这种存法很常见。把 .01 当扩展名切掉，三个目录的排序键就都成了「vol」，
        // 排序退化成列目录时的顺序——NAS 上那就是乱的
        val children = listOf(dir("vol.10"), dir("vol.02"), dir("vol.01"))
        assertEquals(
            listOf("vol.01", "vol.02", "vol.10"),
            ScanRules.chapterUnitsOf(children).map { it.name },
        )

        // 压缩包则相反：扩展名不参与比较
        val archives = listOf(file("vol 10.zip"), file("vol 2.zip"), file("vol 1.zip"))
        assertEquals(
            listOf("vol 1.zip", "vol 2.zip", "vol 10.zip"),
            ScanRules.chapterUnitsOf(archives).map { it.name },
        )
    }

    @Test
    fun `远端列目录的顺序不参与排序`() {
        // 服务端给的顺序（拿书名当例子这里用卷号）与名称无关，排完必须是人看的顺序
        val asListed = listOf(
            dir("[Comic][通靈王][武井宏之][HK]vol_16"),
            dir("[Comic][通靈王][武井宏之][HK]vol_27"),
            dir("[Comic][通靈王][武井宏之][HK]vol_02"),
            dir("[Comic][通靈王][武井宏之][HK]vol_01"),
        )
        assertEquals(
            listOf("01", "02", "16", "27"),
            ScanRules.sortUnits(asListed, { it.name }, { it.isDir })
                .map { it.name.substringAfterLast("vol_") },
        )
    }

    @Test
    fun `扫描时与详情页排出的顺序一致`() {
        val children = listOf(
            dir("vol.10"), dir("vol.2"), file("vol 3.zip"), file("vol 1.zip"),
        )
        val units = ScanRules.chapterUnitsOf(children)

        // 详情页只有章标题（目录名原样、文件去掉扩展名），用标题再排一遍应该得到同一个顺序；
        // 两处用的键一旦不一致，详情页每次进来都会把库里的章序"修正"一次
        val byTitle = units.sortedWith { a, b ->
            com.nascomic.reader.core.text.NaturalSort.key(ScanRules.titleOf(a))
                .compareTo(com.nascomic.reader.core.text.NaturalSort.key(ScanRules.titleOf(b)))
        }
        assertEquals(units.map { it.name }, byTitle.map { it.name })
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
