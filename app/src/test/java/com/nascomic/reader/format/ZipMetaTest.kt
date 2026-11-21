package com.nascomic.reader.format

import com.nascomic.reader.format.zip.RandomAccessData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 压缩包这一层：ComicInfo.xml 能不能从真实 zip 字节里读出来。 */
class ZipMetaTest {

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

    private fun build(files: Map<String, String>): ZipBookSource {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            files.forEach { (name, body) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return ZipBookSource.open(MemData(out.toByteArray()))
    }

    private val comicInfo = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo>
          <Series>测试系列</Series>
          <Number>2</Number>
          <Writer>测试作者甲</Writer>
          <Penciller>测试作者乙</Penciller>
        </ComicInfo>
    """.trimIndent()

    @Test
    fun `包里有 ComicInfo 就读得到`() {
        build(mapOf("p1.jpg" to "x", "ComicInfo.xml" to comicInfo)).use { src ->
            val meta = src.meta
            assertEquals("测试作者甲", meta?.writer)
            assertEquals("2", meta?.volume)
            assertEquals(1, src.pageCount)
        }
    }

    @Test
    fun `子目录里的 ComicInfo 也认`() {
        build(mapOf("book/p1.jpg" to "x", "book/ComicInfo.xml" to comicInfo)).use { src ->
            assertEquals("测试作者甲", src.meta?.writer)
        }
    }

    @Test
    fun `没有就是 null`() {
        build(mapOf("p1.jpg" to "x", "p2.jpg" to "y")).use { src ->
            assertNull(src.meta)
        }
    }

    @Test
    fun `坏 XML 不影响打开`() {
        build(mapOf("p1.jpg" to "x", "ComicInfo.xml" to "<ComicInfo><Writer>半截")).use { src ->
            assertNull(src.meta?.writer)
            assertEquals(1, src.pageCount)
        }
    }
}
