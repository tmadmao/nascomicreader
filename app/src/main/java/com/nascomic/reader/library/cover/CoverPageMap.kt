package com.nascomic.reader.library.cover

/**
 * 全书页号 → 「第几章 + 章内第几页」。
 *
 * 封面存的是**全书页号**，而取封面时为了省请求只打开一章。
 * 只开第一章却拿全书页号去取，第二章的封面就会被钳到第一章末页——
 * 这里负责把页号折算到正确的章上。
 */
object CoverPageMap {

    /**
     * @param pageTotals 各章页数，按阅读顺序
     * @param page 全书页号（0 基）
     * @return 章下标与该章内的页号；折算不出来时返回 null（调用方退回整本打开）
     */
    fun pick(pageTotals: List<Int>, page: Int): Pair<Int, Int>? {
        if (page < 0 || pageTotals.isEmpty()) return null
        // 只要有一章没量过页数，累计值就不可靠，宁可整本打开也不猜
        if (pageTotals.any { it <= 0 }) return null

        var rest = page
        pageTotals.forEachIndexed { index, count ->
            if (rest < count) return index to rest
            rest -= count
        }
        // 页号比整本还大：交给取图那一端钳位
        return null
    }
}
