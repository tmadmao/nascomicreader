package com.nascomic.reader.smb

import com.nascomic.reader.format.zip.RandomAccessData

/**
 * 把 SMB 上的文件当随机读的文件用，带一块本地缓存把碎读合并。
 *
 * 与 [com.nascomic.reader.webdav.HttpRandomAccess] 同样的思路：zip / tar / epub 的解析
 * 只认「按偏移读一段字节」，换成 SMB 后一行都不用改。
 */
class SmbRandomAccess(
    private val client: SmbClient,
    private val path: String,
    override val size: Long,
) : RandomAccessData {

    private var cacheStart = -1L
    private var cache: ByteArray? = null

    override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= size) return -1
        val want = minOf(length.toLong(), size - offset).toInt()
        if (want <= 0) return -1

        val cached = cache
        val start = cacheStart
        if (cached != null && offset >= start && offset + want <= start + cached.size) {
            System.arraycopy(cached, (offset - start).toInt(), dest, destOffset, want)
            return want
        }

        // 一次多取一点，后面的连续读就不用再往返
        val blockLength = maxOf(want, minOf(CHUNK.toLong(), size - offset).toInt())
        val block = client.read(path, offset, blockLength) ?: return -1
        if (block.isEmpty()) return -1

        cacheStart = offset
        cache = block
        val n = minOf(want, block.size)
        System.arraycopy(block, 0, dest, destOffset, n)
        return n
    }

    override fun close() {
        cache = null
        cacheStart = -1L
    }

    private companion object {
        /** 局域网 SMB 一往返不到 1 ms，但页图解码会连着读很多小块，取大一点更省 */
        const val CHUNK = 256 * 1024
    }
}
