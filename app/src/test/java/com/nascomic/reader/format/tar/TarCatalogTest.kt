package com.nascomic.reader.format.tar

import com.nascomic.reader.format.zip.RandomAccessData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TarCatalogTest {

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

    /** 造一个最小 tar：ustar 头 + 512 对齐的数据块 */
    private fun tarBytes(files: List<Triple<String, String, Char>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content, type) in files) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            writeOctal(header, 100, 8, 420L)   // 0o644
            writeOctal(header, 108, 8, 0L)
            writeOctal(header, 116, 8, 0L)
            writeOctal(header, 124, 12, content.toByteArray(Charsets.UTF_8).size.toLong())
            writeOctal(header, 136, 12, 0L)
            header[156] = type.code.toByte()
            val magic = "ustar".toByteArray()
            System.arraycopy(magic, 0, header, 257, magic.size)
            // 校验和：先按空格算，再写回
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0
            header.forEach { sum += it.toInt() and 0xFF }
            val check = String.format("%06o\u0000 ", sum).toByteArray()
            System.arraycopy(check, 0, header, 148, 8)
            out.write(header)

            val data = content.toByteArray(Charsets.UTF_8)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
        System.arraycopy(text.toByteArray(), 0, buf, offset, length)
    }

    @Test
    fun `列出条目与大小`() {
        val bytes = tarBytes(
            listOf(
                Triple("p1.jpg", "aaa", '0'),
                Triple("p2.jpg", "bbbbb", '0'),
            )
        )
        val catalog = TarCatalog.open(MemData(bytes))
        assertEquals(listOf("p1.jpg", "p2.jpg"), catalog.entries.map { it.name })
        assertEquals(listOf(3L, 5L), catalog.entries.map { it.size })
    }

    @Test
    fun `按数据块偏移读出内容`() {
        val bytes = tarBytes(
            listOf(
                Triple("a/1.jpg", "hello", '0'),
                Triple("a/2.jpg", "world!!", '0'),
            )
        )
        val catalog = TarCatalog.open(MemData(bytes))
        val first = catalog.open(catalog.entries[0]).use { it.readBytes().toString(Charsets.UTF_8) }
        val second = catalog.open(catalog.entries[1]).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("hello", first)
        assertEquals("world!!", second)
    }

    @Test
    fun `目录条目被标记出来`() {
        val bytes = tarBytes(
            listOf(
                Triple("第1话/", "", '5'),
                Triple("第1话/1.jpg", "x", '0'),
            )
        )
        val catalog = TarCatalog.open(MemData(bytes))
        assertTrue(catalog.entries.first { it.name.startsWith("第1话") && it.isDirectory }.isDirectory)
    }

    @Test
    fun `空输入不是有效 tar`() {
        val error = runCatching { TarCatalog.open(MemData(ByteArray(0))) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }
}
