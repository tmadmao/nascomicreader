package com.nascomic.reader.format.tar

import com.nascomic.reader.format.zip.RandomAccessData
import java.io.IOException
import java.io.InputStream

/**
 * tar 目录解析。tar 没有中央目录，只能从头一条条读 512 字节的头。
 *
 * 支持 POSIX ustar 的 `prefix` 字段、GNU 长文件名（`L`）与 PAX 扩展头（`x`）里的 path 覆盖；
 * 这几种是实际 tar 包里最常见的差异。
 */
class TarCatalog private constructor(
    private val data: RandomAccessData,
    val entries: List<Entry>,
) {

    data class Entry(
        val name: String,
        val size: Long,
        val dataOffset: Long,
        val isDirectory: Boolean,
    )

    fun open(entry: Entry): InputStream {
        if (entry.size <= 0) return ByteArray(0).inputStream()
        var remaining = entry.size
        var position = entry.dataOffset
        return object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0L) return -1
                val want = minOf(len.toLong(), remaining).toInt()
                val got = data.read(position, b, off, want)
                if (got <= 0) return -1
                position += got
                remaining -= got
                return got
            }
        }
    }

    fun close() = data.close()

    companion object {

        private const val BLOCK = 512
        private const val MAX_ENTRIES = 200_000

        fun open(data: RandomAccessData): TarCatalog {
            val entries = readEntries(data)
            if (entries.isEmpty()) throw IOException("不是有效的 tar：没有条目")
            return TarCatalog(data, entries)
        }

        private fun readEntries(data: RandomAccessData): List<Entry> {
            val out = ArrayList<Entry>()
            val header = ByteArray(BLOCK)
            var offset = 0L
            var longName: String? = null
            var paxPath: String? = null

            while (offset + BLOCK <= data.size && out.size < MAX_ENTRIES) {
                if (data.read(offset, header, 0, BLOCK) != BLOCK) break
                if (header.all { it == 0.toByte() }) break   // 结束块

                val rawName = cString(header, 0, 100)
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val prefix = if (cString(header, 257, 6) == "ustar") cString(header, 345, 155) else ""

                val dataOffset = offset + BLOCK
                val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK

                when (type) {
                    'L' -> longName = readAll(data, dataOffset, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                    'x' -> paxPath = parsePaxPath(readAll(data, dataOffset, size)) ?: paxPath
                    'g' -> Unit
                    '5' -> {
                        val name = longName ?: paxPath ?: joinName(prefix, rawName)
                        out += Entry(name, 0, dataOffset, isDirectory = true)
                        longName = null
                        paxPath = null
                    }
                    '0', '\u0000' -> {
                        val name = longName ?: paxPath ?: joinName(prefix, rawName)
                        if (size > 0) {
                            out += Entry(name, size, dataOffset, isDirectory = false)
                        }
                        longName = null
                        paxPath = null
                    }
                    else -> {
                        longName = null
                        paxPath = null
                    }
                }
                offset = dataOffset + padded
            }
            return out
        }

        private fun joinName(prefix: String, name: String): String =
            if (prefix.isEmpty()) name else "$prefix/$name"

        private fun readAll(data: RandomAccessData, offset: Long, size: Long): ByteArray {
            if (size <= 0 || size > 1024 * 1024) return ByteArray(0)
            val buf = ByteArray(size.toInt())
            var read = 0
            while (read < buf.size) {
                val got = data.read(offset + read, buf, read, buf.size - read)
                if (got <= 0) break
                read += got
            }
            return if (read == buf.size) buf else buf.copyOf(read)
        }

        /** PAX 头的正文是「长度 key=value\n」，这里只关心 path */
        private fun parsePaxPath(body: ByteArray): String? {
            val text = body.toString(Charsets.UTF_8)
            for (line in text.split('\n')) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(line.indexOf(' ') + 1, eq)
                if (key == "path") return line.substring(eq + 1)
            }
            return null
        }

        private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
            val end = (offset until minOf(offset + length, bytes.size))
                .firstOrNull { bytes[it] == 0.toByte() } ?: minOf(offset + length, bytes.size)
            return String(bytes, offset, end - offset, Charsets.UTF_8).trim()
        }

        /** 八进制字段；有的实现会在字段里塞 base-256，那类大文件这里按 0 处理 */
        private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
            if (bytes[offset].toInt() and 0x80 != 0) return 0L
            var value = 0L
            for (i in offset until offset + length) {
                val c = bytes[i].toInt()
                if (c == 0 || c == ' '.code) {
                    if (value != 0L) break else continue
                }
                if (c < '0'.code || c > '7'.code) return value
                value = value * 8 + (c - '0'.code)
            }
            return value
        }
    }
}
