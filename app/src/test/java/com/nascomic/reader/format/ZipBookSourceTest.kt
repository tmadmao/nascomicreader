package com.nascomic.reader.format

import com.nascomic.reader.format.zip.RandomAccessData
import com.nascomic.reader.format.zip.ZipCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipBookSourceTest {

    private class MemData(private val bytes: ByteArray) : RandomAccessData {
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), dest, destOffset, n)
            return n
        }
        override fun close() = Unit
    }

    private fun build(vararg names: String): ZipBookSource {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            names.forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zos.write(name.toByteArray())
                zos.closeEntry()
            }
        }
        return ZipBookSource.open(MemData(out.toByteArray()))
    }

    @Test
    fun `单章包：页按自然序`() {
        build("p10.jpg", "p2.jpg", "p1.jpg", "说明.txt").use { src ->
            assertEquals(3, src.pageCount)          // txt 被排除
            assertEquals(1, src.chapters.size)
            assertEquals("p1.jpg", src.pageName(0))
            assertEquals("p2.jpg", src.pageName(1))
            assertEquals("p10.jpg", src.pageName(2))
            assertEquals(0, src.chapterOf(2))
        }
    }

    @Test
    fun `子目录即章，章与章首尾相接`() {
        build(
            "ch2/01.jpg", "ch2/02.jpg",
            "ch1/01.jpg", "ch1/02.jpg", "ch1/03.jpg",
        ).use { src ->
            assertEquals(2, src.chapters.size)
            assertEquals(listOf("ch1", "ch2"), src.chapters.map { it.title })
            assertEquals(listOf(3, 2), src.chapters.map { it.pageCount })

            assertEquals(0, src.firstPageOfChapter(0))
            assertEquals(3, src.firstPageOfChapter(1))
            // 第 3 页（0 基）是第二章第一页
            assertEquals(1, src.chapterOf(3))
            assertEquals(0, src.chapterOf(2))
        }
    }

    @Test
    fun `根目录散图单独成章且排在最前`() {
        build("cover.jpg", "ch1/01.jpg").use { src ->
            assertEquals(2, src.chapters.size)
            assertEquals("", src.chapters[0].title)
            assertEquals(1, src.chapters[0].pageCount)
            assertEquals("cover.jpg", src.pageName(0))
            assertEquals("ch1/01.jpg", src.pageName(1))
        }
    }

    @Test
    fun `章名按自然序而不是字典序`() {
        build("ch10/a.jpg", "ch2/a.jpg", "ch1/a.jpg").use { src ->
            assertEquals(listOf("ch1", "ch2", "ch10"), src.chapters.map { it.title })
        }
    }

    @Test
    fun `只有非图片的包没有页`() {
        build("readme.txt", "info.nfo").use { src ->
            assertEquals(0, src.pageCount)
            assertTrue(src.chapters.isEmpty())
        }
    }

    @Test
    fun `页内容可读`() {
        build("a.jpg", "b.jpg").use { src ->
            val text = src.openPage(0).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("a.jpg", text)
        }
    }

    @Test
    fun `扩展名判定`() {
        assertTrue(ImageNames.isImage("a.JPG"))
        assertTrue(ImageNames.isImage("dir/x.webp"))
        assertTrue(!ImageNames.isImage("a.txt"))
        assertTrue(!ImageNames.isImage("noext"))
        assertEquals("jpg", ImageNames.extensionOf("a.b.JPG"))
        assertEquals("a.b", ImageNames.baseName("a.b.JPG"))
    }
}
