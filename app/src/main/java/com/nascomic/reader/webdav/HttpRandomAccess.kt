package com.nascomic.reader.webdav

import com.nascomic.reader.format.zip.RandomAccessData

/**
 * 把远程文件当随机读的文件用：按需发 Range 请求，带一块本地缓存把碎读合并。
 *
 * 有了它，zip / tar / epub 的解析代码不用改一行——它们只认「按偏移读一段字节」。
 */
class HttpRandomAccess(
    private val client: WebDavClient,
    private val url: String,
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

        // 一次多取一点，后面的连续读就不用再发请求
        val blockLength = maxOf(want, minOf(CHUNK.toLong(), size - offset).toInt())
        val block = client.readRange(url, offset, blockLength)
            ?: throw IllegalStateException("服务器不支持按区间读取")
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
        const val CHUNK = 128 * 1024
    }
}
