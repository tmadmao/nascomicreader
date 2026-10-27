package com.nascomic.reader.format.zip

import java.io.Closeable

/**
 * 随机读取一段字节的能力。
 *
 * 压缩包不能整包读进内存（一本几百 MB 很常见），所以先有这么一个「能按位置读」的抽象：
 * 本地文件用它读，系统文件选择器给的描述符也用它读，测试里用内存数组实现。
 */
interface RandomAccessData : Closeable {
    val size: Long

    /**
     * 从 [offset] 起读最多 [length] 字节到 [dest]。
     * 返回实际读到的字节数；读不到返回 -1。
     */
    fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int

    /** 把 [length] 字节读满，不够就抛错——解析头部时用这个，少一个字节都是坏包 */
    fun readFully(offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = read(offset + done, buf, done, length - done)
            if (n <= 0) throw java.io.EOFException("在 $offset 处字节不足：需要 $length，实到 $done")
            done += n
        }
        return buf
    }
}
