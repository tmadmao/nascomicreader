package com.nascomic.reader.format.zip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipCatalogTest {

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

    private fun zipBytes(names: List<String>, charset: Charset = Charsets.UTF_8): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zos ->
            names.forEachIndexed { i, name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("page-$i".toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `读出全部条目与内容`() {
        val bytes = zipBytes(listOf("b.jpg", "a.jpg", "c.jpg"))
        ZipCatalog.open(MemData(bytes)).use { cat ->
            assertEquals(3, cat.entries.size)
            // 目录顺序 = 写入顺序，排序不归这里管
            assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), cat.entries.map { it.name })

            val first = cat.entries.first { it.name == "a.jpg" }
            val text = cat.open(first).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("page-1", text)
        }
    }

    @Test
    fun `中文文件名按 UTF-8 读`() {
        val bytes = zipBytes(listOf("第3页.jpg", "封面.jpg"))
        ZipCatalog.open(MemData(bytes)).use { cat ->
            assertEquals(setOf("第3页.jpg", "封面.jpg"), cat.entries.map { it.name }.toSet())
        }
    }

    @Test
    fun `未声明 UTF-8 的 GB18030 包名能读回来`() {
        // ZipOutputStream 用非 UTF-8 字符集时不会设置 UTF-8 标志位，正好模拟老压缩工具
        val gbk = Charset.forName("GB18030")
        val bytes = zipBytes(listOf("第一话.jpg", "第二话.jpg"), gbk)
        ZipCatalog.open(MemData(bytes)).use { cat ->
            assertEquals(setOf("第一话.jpg", "第二话.jpg"), cat.entries.map { it.name }.toSet())
        }
    }

    @Test
    fun `截断的包给出明确错误`() {
        val bytes = zipBytes(listOf("a.jpg", "b.jpg"))
        val broken = bytes.copyOf(bytes.size - 40)
        val ex = assertThrows(ZipFormatException::class.java) {
            ZipCatalog.open(MemData(broken))
        }
        assertTrue("错误信息应说明原因，实际：${ex.message}", ex.message!!.isNotEmpty())
    }

    @Test
    fun `空文件与小文件不崩`() {
        assertThrows(ZipFormatException::class.java) { ZipCatalog.open(MemData(ByteArray(0))) }
        assertThrows(ZipFormatException::class.java) { ZipCatalog.open(MemData(ByteArray(10))) }
    }

    @Test
    fun `真实文件走文件通道也能读`() {
        val tmp = File.createTempFile("zipcat", ".zip")
        try {
            tmp.writeBytes(zipBytes(listOf("p2.jpg", "p10.jpg")))
            val data = FileData(tmp)
            ZipCatalog.open(data).use { cat ->
                assertEquals(2, cat.entries.size)
                assertArrayEquals(
                    "page-1".toByteArray(),
                    cat.open(cat.entries.first { it.name == "p10.jpg" }).use { it.readBytes() }
                )
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `目录条目被识别出来`() {
        val bytes = zipBytes(listOf("ch1/", "ch1/a.jpg"))
        ZipCatalog.open(MemData(bytes)).use { cat ->
            assertEquals(1, cat.entries.count { it.isDirectory })
            assertEquals(1, cat.entries.count { !it.isDirectory })
        }
    }

    private class FileData(file: File) : RandomAccessData {
        private val raf = java.io.RandomAccessFile(file, "r")
        override val size: Long get() = raf.length()
        override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
            raf.seek(offset)
            return raf.read(dest, destOffset, length)
        }
        override fun close() = raf.close()
    }
}
