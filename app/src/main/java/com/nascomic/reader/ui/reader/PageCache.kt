package com.nascomic.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 预载页缓存：把解码好、裁好边的页面按页号存着，翻过去直接出图，不再等网络与解码。
 *
 * 按字节封顶（上限由 `ReaderPrefs.cacheBudgetBytes()` 给），超出先丢最久没用过的；
 * 画面几何或裁边参数一变（转屏、开关双页、改阈值），同样的页号已经不是同一张图，整份作废。
 *
 * 多个线程会同时读写（画当前页与后台预载都在 IO 上），所以对外的方法都上了锁。
 */
class PageCache(private val budgetBytes: Long) {

    /** 一页解好之后的样子：位图 + 裁边范围 + 是不是跨页宽图 */
    class Entry(val bitmap: Bitmap, val bounds: Rect, val wide: Boolean)

    // accessOrder = true：get 也算用过，淘汰时先丢最久没碰的
    private val entries = LinkedHashMap<Int, Entry>(8, 0.75f, true)
    private var bytes = 0L
    private var width = 0
    private var height = 0

    /** 目标尺寸变了就清空：按旧尺寸解码的图放大会糊 */
    @Synchronized
    fun ensureGeometry(w: Int, h: Int) {
        if (w != width || h != height) {
            width = w
            height = h
            clearLocked()
        }
    }

    @Synchronized
    operator fun get(index: Int): Entry? = entries[index]

    @Synchronized
    fun put(index: Int, entry: Entry) {
        entries.put(index, entry)?.let { bytes -= it.bitmap.byteCount }
        bytes += entry.bitmap.byteCount
        // 只留一条也要留：当前页可能就是最久没用的那条
        while (bytes > budgetBytes && entries.size > 1) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) break
            val victim = oldest.next()
            oldest.remove()
            bytes -= victim.value.bitmap.byteCount
        }
    }

    /** 只留这个区间里的页：往后翻时把身后的页丢掉，别让缓存越滚越大 */
    @Synchronized
    fun keepOnly(range: IntRange) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in range) {
                bytes -= entry.value.bitmap.byteCount
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() = clearLocked()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun bytes(): Long = bytes

    private fun clearLocked() {
        entries.clear()
        bytes = 0
    }
}
