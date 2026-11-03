package com.nascomic.reader.format.zip

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 压缩包目录。只读中央目录，不解包、不整包进内存。
 *
 * 为什么自己解析而不是直接用平台的 ZipFile：ZipFile 只接受 File，而用户的书放在系统文件选择器
 * 授权的目录里（拿不到 File）；而且平台会把条目名按一种编码解析，编码猜错就整本乱码。
 * 这里按包内记录读原始字节，再按「严格 UTF-8 → 失败退 GB18030」解码，两种情况都能读对。
 */
class ZipCatalog private constructor(
    private val data: RandomAccessData,
    val entries: List<Entry>,
) : Closeable {

    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    /** 打开某个条目的流。压缩方式 0 直接读，8 走 inflate。 */
    fun open(entry: Entry): InputStream {
        val header = data.readFully(entry.localHeaderOffset, 30)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != LOCAL_HEADER_SIGNATURE) {
            throw ZipFormatException("条目 ${entry.name} 的本地头损坏")
        }
        buf.position(26)
        val nameLen = buf.short.toInt() and 0xFFFF
        val extraLen = buf.short.toInt() and 0xFFFF
        val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen

        val raw = RegionInputStream(data, dataStart, entry.compressedSize)
        return when (entry.method) {
            METHOD_STORED -> raw
            // 压缩包里是裸 deflate（没有 zlib 头），必须 nowrap，否则报 incorrect header check
            METHOD_DEFLATE -> {
                val inflater = Inflater(true)
                object : InflaterInputStream(raw, inflater) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> {
                raw.close()
                throw ZipFormatException("不支持的压缩方式 ${entry.method}：${entry.name}")
            }
        }
    }

    override fun close() = data.close()

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50

        /** 中央目录本身也是整块读进内存的，超过这个数就是坏包——正常几千页也就几 MB */
        private const val MAX_CENTRAL_DIRECTORY = 256L * 1024 * 1024
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8

        /** EOCD 最长 22 + 65535 字节注释 */
        private const val MAX_EOCD_SEARCH = 22 + 0xFFFF

        private val GB18030: Charset = Charset.forName("GB18030")

        fun open(data: RandomAccessData): ZipCatalog {
            val entries = try {
                readCentralDirectory(data)
            } catch (e: Exception) {
                data.close()
                throw e
            }
            return ZipCatalog(data, entries)
        }

        private fun readCentralDirectory(data: RandomAccessData): List<Entry> {
            if (data.size < 22) throw ZipFormatException("文件太短，不是压缩包")

            val tailLen = minOf(data.size, MAX_EOCD_SEARCH.toLong()).toInt()
            val tailStart = data.size - tailLen
            val tail = data.readFully(tailStart, tailLen)

            val eocdAt = findEocd(tail)
                ?: throw ZipFormatException("找不到压缩包目录（文件可能被截断）")

            val eocd = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val totalEntries16 = eocd.getShort(eocdAt + 10).toInt() and 0xFFFF
            val cdSize32 = eocd.getInt(eocdAt + 12).toLong() and 0xFFFFFFFFL
            val cdOffset32 = eocd.getInt(eocdAt + 16).toLong() and 0xFFFFFFFFL

            var totalEntries = totalEntries16.toLong()
            var cdSize = cdSize32
            var cdOffset = cdOffset32

            // 三个字段任意一个顶到上限，就说明真实值在 ZIP64 的结束记录里
            if (totalEntries16 == 0xFFFF || cdSize32 == 0xFFFFFFFFL || cdOffset32 == 0xFFFFFFFFL) {
                val zip64 = readZip64End(data, tailStart + eocdAt)
                totalEntries = zip64.totalEntries
                cdSize = zip64.cdSize
                cdOffset = zip64.cdOffset
            }

            if (cdOffset < 0 || cdOffset >= data.size) throw ZipFormatException("目录偏移越界")
            if (cdSize <= 0 || cdSize > MAX_CENTRAL_DIRECTORY) throw ZipFormatException("目录大小异常")
            if (cdOffset + cdSize > data.size) throw ZipFormatException("中央目录越界")
            if (totalEntries > cdSize) throw ZipFormatException("条目数异常")

            val cd = data.readFully(cdOffset, cdSize.toInt())
            val buf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

            val out = ArrayList<Entry>(totalEntries.toInt())
            var seen = 0L
            while (seen < totalEntries) {
                if (buf.remaining() < 46) throw ZipFormatException("中央目录条目不足")
                val sig = buf.int
                if (sig != CENTRAL_HEADER_SIGNATURE) throw ZipFormatException("中央目录条目签名不符")

                buf.position(buf.position() + 4)          // version made by + version needed
                val flags = buf.short.toInt() and 0xFFFF
                val method = buf.short.toInt() and 0xFFFF
                buf.position(buf.position() + 8)          // time + date + crc
                var compSize = buf.int.toLong() and 0xFFFFFFFFL
                var uncompSize = buf.int.toLong() and 0xFFFFFFFFL
                val nameLen = buf.short.toInt() and 0xFFFF
                val extraLen = buf.short.toInt() and 0xFFFF
                val commentLen = buf.short.toInt() and 0xFFFF
                buf.position(buf.position() + 8)          // disk start + internal/external attr
                var localOffset = buf.int.toLong() and 0xFFFFFFFFL

                if (buf.remaining() < nameLen + extraLen + commentLen) {
                    throw ZipFormatException("条目名长度越界")
                }
                val rawName = ByteArray(nameLen)
                buf.get(rawName)

                if (extraLen > 0) {
                    val extra = ByteArray(extraLen)
                    buf.get(extra)
                    // 只有写满 0xFFFFFFFF 的字段才在 ZIP64 extra 里，且顺序固定
                    if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                        val z = readZip64Extra(extra, uncompSize, compSize, localOffset)
                        uncompSize = z[0]
                        compSize = z[1]
                        localOffset = z[2]
                    }
                }
                buf.position(buf.position() + commentLen)

                val utf8Flag = flags and 0x0800 != 0
                out += Entry(
                    name = decodeName(rawName, utf8Flag),
                    method = method,
                    compressedSize = compSize,
                    uncompressedSize = uncompSize,
                    localHeaderOffset = localOffset,
                )
                seen++
            }
            return out
        }

        private class Zip64End(val totalEntries: Long, val cdSize: Long, val cdOffset: Long)

        /**
         * ZIP64 的结束记录：EOCD 前面 20 字节是一个定位器，里面存着结束记录的绝对偏移。
         */
        private fun readZip64End(data: RandomAccessData, eocdOffset: Long): Zip64End {
            val locatorAt = eocdOffset - 20
            if (locatorAt < 0) throw ZipFormatException("ZIP64 定位记录缺失")

            val locator = ByteBuffer.wrap(data.readFully(locatorAt, 20)).order(ByteOrder.LITTLE_ENDIAN)
            if (locator.int != ZIP64_LOCATOR_SIGNATURE) throw ZipFormatException("ZIP64 定位记录签名不符")
            locator.position(8)
            val recordAt = locator.long

            if (recordAt < 0 || recordAt + 56 > data.size) throw ZipFormatException("ZIP64 结束记录越界")
            val record = ByteBuffer.wrap(data.readFully(recordAt, 56)).order(ByteOrder.LITTLE_ENDIAN)
            if (record.int != ZIP64_EOCD_SIGNATURE) throw ZipFormatException("ZIP64 结束记录签名不符")

            record.position(32)
            val totalEntries = record.long
            val cdSize = record.long
            val cdOffset = record.long
            return Zip64End(totalEntries, cdSize, cdOffset)
        }

        /**
         * 单个条目的 ZIP64 扩展字段（header id = 0x0001）。
         *
         * 里面的值只覆盖「中央目录里写不下」的那几个字段，顺序固定为
         * 原始大小 → 压缩大小 → 本地头偏移 → 起始盘号，取几个由调用方决定。
         */
        private fun readZip64Extra(
            extra: ByteArray,
            uncompSize: Long,
            compSize: Long,
            localOffset: Long,
        ): LongArray {
            var outUncomp = uncompSize
            var outComp = compSize
            var outOffset = localOffset

            val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.remaining() >= 4) {
                val id = buf.short.toInt() and 0xFFFF
                val size = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < size) break
                val end = buf.position() + size
                if (id == 0x0001) {
                    if (outUncomp == 0xFFFFFFFFL && buf.position() + 8 <= end) outUncomp = buf.long
                    if (outComp == 0xFFFFFFFFL && buf.position() + 8 <= end) outComp = buf.long
                    if (outOffset == 0xFFFFFFFFL && buf.position() + 8 <= end) outOffset = buf.long
                    return longArrayOf(outUncomp, outComp, outOffset)
                }
                buf.position(end)
            }
            return longArrayOf(outUncomp, outComp, outOffset)
        }

        private fun findEocd(tail: ByteArray): Int? {
            var i = tail.size - 22
            while (i >= 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    return i
                }
                i--
            }
            return null
        }

        /**
         * 条目名解码。包内声明了 UTF-8 就按 UTF-8；没声明时先严格试 UTF-8，
         * 失败再退 GB18030 —— 很多中文压缩包就是这么存的。
         */
        internal fun decodeName(raw: ByteArray, utf8Flag: Boolean): String {
            if (utf8Flag) return String(raw, Charsets.UTF_8)
            return try {
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                dec.decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: CharacterCodingException) {
                String(raw, GB18030)
            }
        }
    }
}

class ZipFormatException(message: String) : IOException(message)

/** 把 [RandomAccessData] 的一段包成 InputStream，避免整条目读进内存 */
private class RegionInputStream(
    private val data: RandomAccessData,
    private val start: Long,
    private val length: Long,
) : InputStream() {

    private var pos = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val want = minOf(len.toLong(), length - pos).toInt()
        val n = data.read(start + pos, b, off, want)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val step = minOf(n, length - pos)
        if (step <= 0) return 0
        pos += step
        return step
    }

    override fun available(): Int = (length - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
