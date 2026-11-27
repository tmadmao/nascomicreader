package com.nascomic.reader.ui.reader

/**
 * 竖向窗口里「槽位 ↔ 全局页号」的映射。
 *
 * 一个槽位是窗口里上下相接的一段画面。普通页一页占一个槽位；
 * 跨页宽图拆开看时一页占两个槽位（上半、下半），所以槽位数不等于页数，
 * 页码、接页、收窗口都要经这张表换算，不能拿窗口长度当页数用。
 */
class PageSlots {

    enum class TrimSide { START, END }

    private val ids = ArrayList<Int>()

    val size: Int get() = ids.size

    fun isEmpty(): Boolean = ids.isEmpty()

    /** 按整窗重建：[pages] 是每个槽位对应的全局页号，同一页可以连续出现多次 */
    fun rebuild(pages: List<Int>) {
        ids.clear()
        ids.addAll(pages)
    }

    fun clear() {
        ids.clear()
    }

    fun firstPage(): Int? = ids.firstOrNull()

    fun lastPage(): Int? = ids.lastOrNull()

    fun pageAt(slot: Int): Int? = ids.getOrNull(slot)

    /** 这一页最早出现的槽位；不在窗口里返回 -1 */
    fun firstSlotOf(page: Int): Int = ids.indexOf(page)

    /** 末尾接一页，占 [slots] 个槽位 */
    fun append(page: Int, slots: Int) {
        repeat(slots.coerceAtLeast(0)) { ids.add(page) }
    }

    /** 头部插一页，占 [slots] 个槽位 */
    fun prepend(page: Int, slots: Int) {
        repeat(slots.coerceAtLeast(0)) { ids.add(0, page) }
    }

    /**
     * 丢掉最前面 n 个槽位，返回实际丢掉的个数。
     * 与画面同一口径：至少留一个槽位，正看着的内容不能被丢空。
     */
    fun dropFirst(n: Int): Int {
        val count = n.coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        repeat(count) { ids.removeAt(0) }
        return count
    }

    /** 丢掉最后面 n 个槽位，口径同 [dropFirst] */
    fun dropLast(n: Int): Int {
        val count = n.coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        repeat(count) { ids.removeAt(ids.size - 1) }
        return count
    }

    /** 窗口里有几页（同一页占多段只算一页） */
    fun pageCount(): Int {
        var count = 0
        var i = 0
        while (i < ids.size) {
            count++
            val id = ids[i]
            while (i < ids.size && ids[i] == id) i++
        }
        return count
    }

    /** 头一页占几个槽位：拆开的上下两段要一起丢，不能只丢半页 */
    fun slotsOfFirstPage(): Int {
        if (ids.isEmpty()) return 0
        val id = ids[0]
        var n = 0
        while (n < ids.size && ids[n] == id) n++
        return n
    }

    /** 末页占几个槽位 */
    fun slotsOfLastPage(): Int {
        if (ids.isEmpty()) return 0
        val id = ids[ids.size - 1]
        var n = 0
        while (n < ids.size && ids[ids.size - 1 - n] == id) n++
        return n
    }

    /** [slot] 所在页之前还有几整页：收窗口时身后留几页供往回翻 */
    fun pagesBefore(slot: Int): Int {
        if (ids.isEmpty()) return 0
        val target = ids[slot.coerceIn(0, ids.size - 1)]
        var count = 0
        var i = 0
        while (i < ids.size && ids[i] != target) {
            count++
            val id = ids[i]
            while (i < ids.size && ids[i] == id) i++
        }
        return count
    }

    /** 优先裁掉身后最远的整页，只裁视口之外的页；两端都不能裁时暂时保留。 */
    fun trimSide(top: Int, anchor: Int, bottom: Int, back: Int, minSlots: Int): TrimSide? {
        if (top !in ids.indices || bottom !in ids.indices) return null
        val firstSlots = slotsOfFirstPage()
        if (top >= firstSlots && size - firstSlots >= minSlots && pagesBefore(anchor) > back) {
            return TrimSide.START
        }
        val lastPageStart = size - slotsOfLastPage()
        if (bottom < lastPageStart && lastPageStart >= minSlots) return TrimSide.END
        return null
    }

    /** 从头部丢 [pages] 整页，返回丢掉的槽位数；至少留一页 */
    fun dropFirstPages(pages: Int): Int {
        var dropped = 0
        repeat(pages.coerceAtLeast(0)) {
            if (pageCount() <= 1) return dropped
            val n = slotsOfFirstPage()
            dropFirst(n)
            dropped += n
        }
        return dropped
    }

    /** 从末尾丢 [pages] 整页，口径同 [dropFirstPages] */
    fun dropLastPages(pages: Int): Int {
        var dropped = 0
        repeat(pages.coerceAtLeast(0)) {
            if (pageCount() <= 1) return dropped
            val n = slotsOfLastPage()
            dropLast(n)
            dropped += n
        }
        return dropped
    }

    companion object {
        /**
         * 重建窗口的页范围：常规以锚点页为中心前 [back] 后 [ahead]；
         * 但恢复页内位置时窗口必须接得住恢复前的**整个视口**——短页跨屏时
         * 屏顶可见页可能比锚点页落后不止 [back] 页（屏底同理超前），
         * 只留一页会把负偏移的目标钳出窗口、画面前跳。
         */
        fun windowRange(anchor: Int, top: Int, bottom: Int, back: Int, ahead: Int, total: Int): IntRange {
            val from = minOf(anchor - back, top).coerceAtLeast(0)
            val to = maxOf(anchor + ahead, bottom).coerceAtMost((total - 1).coerceAtLeast(0))
            return from..maxOf(from, to)
        }
    }
}
