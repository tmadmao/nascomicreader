package com.nascomic.reader.ui.reader

/**
 * 预载窗口的算术。单独拎出来是因为这部分不碰位图，能在 JVM 上直接测。
 *
 * 窗口口径：当前页往前留一页（往回翻不用重解），往后留「预载页数」页，再多留一页当缓冲。
 */
object PageWindow {

    /** 缓存里该保留哪些页：身后留够往回翻的，前面留两倍于预载深度，防止刚解好就被丢掉 */
    fun keep(current: Int, ahead: Int, total: Int): IntRange {
        val aheadPages = ahead.coerceAtLeast(0)
        val back = aheadPages.coerceAtLeast(1)
        return keep(current, back, ahead, total)
    }

    /** 前后页数各自给定时保留的范围：身后按 [prev]，前面留两倍 [next] 再兜一页 */
    fun keep(current: Int, prev: Int, next: Int, total: Int): IntRange {
        if (total <= 0) return IntRange.EMPTY
        val back = prev.coerceAtLeast(0)
        val aheadPages = next.coerceAtLeast(0)
        val from = (current - back).coerceAtLeast(0)
        val to = (current + aheadPages * 2 + 1).coerceAtMost(total - 1)
        return if (to < from) IntRange.EMPTY else from..to
    }

    /** 这一轮真正要去解的页：当前页之后到 ahead 页，越界截断 */
    fun prefetch(current: Int, ahead: Int, total: Int): IntRange {
        if (total <= 0 || ahead <= 0) return IntRange.EMPTY
        val from = current + 1
        val to = (current + ahead).coerceAtMost(total - 1)
        return if (to < from) IntRange.EMPTY else from..to
    }

    /**
     * 双向预载：往后按预载页数，往前留一半。
     * 只往后解的话，往回翻一页就是一次现解——「翻页转圈」有一半是这个方向来的。
     */
    fun prefetchBoth(current: Int, ahead: Int, total: Int): IntRange {
        val back = (ahead / 2).coerceAtLeast(1)
        return prefetchAround(current, back, ahead, total)
    }

    /**
     * 前后各自定页数的预载范围。
     *
     * [prev] 与 [next] 是分开的两档：正翻远多于回翻，往回留太多只是白占内存
     * （原版也是两个滑杆）。0 表示这个方向不预载。
     */
    fun prefetchAround(current: Int, prev: Int, next: Int, total: Int): IntRange {
        if (total <= 0) return IntRange.EMPTY
        val back = prev.coerceAtLeast(0)
        val ahead = next.coerceAtLeast(0)
        if (back == 0 && ahead == 0) return IntRange.EMPTY
        val from = (current - back).coerceAtLeast(0)
        val to = (current + ahead).coerceAtMost(total - 1)
        return if (to < from) IntRange.EMPTY else from..to
    }
}
