package com.nascomic.reader.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 拿本地真实书库验证识别规则（样本不进仓库，没有时自动跳过）。
 *
 * 目录约定：项目根的 `test/` 就是「用户在应用里选的那一个漫画目录」。
 */
class RealLibraryLayoutTest {

    private val root: File = File("../test")

    private fun toNode(f: File) = ScanNode(
        docId = f.absolutePath,
        name = f.name,
        isDir = f.isDirectory,
        size = if (f.isFile) f.length() else 0,
        lastModified = f.lastModified(),
    )

    @Test
    fun `漫画目录下的每一项各自成一本书`() {
        assumeTrue("没有本地样本，跳过", root.isDirectory)

        val items = root.listFiles().orEmpty().map { toNode(it) }
        val books = ScanRules.bookCandidates(items)

        println("漫画目录下共 ${books.size} 项：")
        books.forEach { println("  ${if (it.isDir) "[目录]" else "[文件]"} ${it.name}") }

        // 目录与压缩包各自成一本书；样本会随测试素材增减，不写死项数
        assertTrue(books.isNotEmpty())
        assertEquals(items.size, books.size)
    }

    @Test
    fun `书文件夹里的子文件夹与压缩包是章`() {
        assumeTrue("没有本地样本，跳过", root.isDirectory)

        val bookDir = root.listFiles().orEmpty().firstOrNull { it.isDirectory } ?: return
        val children = bookDir.listFiles().orEmpty().map { toNode(it) }

        val kind = ScanRules.folderKind(children)
        assertEquals(ScanRules.FolderKind.BY_CHAPTER, kind)

        val units = ScanRules.chapterUnitsOf(children)
        println("「${bookDir.name}」识别为 ${units.size} 章：")
        units.forEachIndexed { i, n ->
            val tag = if (n.isDir) "目录" else "压缩包"
            println("  %2d. %-8s %s".format(i + 1, tag, n.name))
        }

        // 四个压缩包 + 七个子目录
        assertEquals(4, units.count { !it.isDir })
        assertEquals(7, units.count { it.isDir })
        assertEquals(11, units.size)
    }

    @Test
    fun `章按数字顺序而不是字典序`() {
        assumeTrue("没有本地样本，跳过", root.isDirectory)

        val bookDir = root.listFiles().orEmpty().firstOrNull { it.isDirectory } ?: return
        val units = ScanRules.chapterUnitsOf(bookDir.listFiles().orEmpty().map { toNode(it) })

        val titles = units.map { ScanRules.titleOf(it) }
        println("章顺序：$titles")

        // 0 一定在最前，9 在最后；小数（0.5 / 5.1）夹在对应整数之间
        assertEquals("0", titles.first())
        assertEquals("9", titles.last())

        val idx0 = titles.indexOf("0")
        val idx05 = titles.indexOf("0.5")
        val idx1 = titles.indexOf("1")
        if (idx05 >= 0 && idx1 >= 0) {
            assertTrue("0 应排在 0.5 前", idx0 < idx05)
            assertTrue("0.5 应排在 1 前", idx05 < idx1)
        }
    }
}
