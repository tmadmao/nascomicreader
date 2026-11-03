package com.nascomic.reader.ui.reader

/**
 * 双页组屏的纯算术：这一屏从第几页到第几页。
 *
 * 组屏规则由「首张单页 / 末张单页 / 每章首张单页」三档决定，且**不跨章**——
 * 一章最后一页不和下一章第一页拼在一起，否则章的边界在阅读里就看不出来了。
 *
 * 抽成纯函数是为了能在 JVM 上把边界试完；阅读页只负责按结果取图。
 */
object SpreadPlan {

    const val ORDER_NONE = 0
    const val ORDER_FIRST_SINGLE = 1
    const val ORDER_LAST_SINGLE = 2
    const val ORDER_CHAPTER_FIRST_SINGLE = 3

    /**
     * 包含 [page] 的那一屏。
     *
     * @param chapterFirst 当前章第一页的全局页号
     * @param chapterLast 当前章最后一页的全局页号
     * @param order 组屏档位，见上面的 ORDER_*
     * @return 闭区间；单页时 start == end
     */
    fun range(page: Int, chapterFirst: Int, chapterLast: Int, order: Int): IntRange {
        val first = chapterFirst.coerceAtLeast(0)
        val last = chapterLast.coerceAtLeast(first)
        val p = page.coerceIn(first, last)

        return when (order) {
            // 每章首张单页：每章第一页单独一屏，之后两页一组
            ORDER_CHAPTER_FIRST_SINGLE ->
                if (p == first) p..p else pairFrom(first + 1, p, last)

            // 首张单页只对整本的第 1 页生效——那是封面或扉页，翻开时不该与下一页并排
            ORDER_FIRST_SINGLE -> {
                if (first == 0 && p == 0) {
                    0..0
                } else {
                    pairFrom(if (first == 0) 1 else first, p, last)
                }
            }

            // 末张单页：本章最后一页单独一屏，前面两页一组
            ORDER_LAST_SINGLE -> {
                if (p == last) {
                    p..p
                } else {
                    val pairingLast = if (last > first) last - 1 else last
                    pairFrom(first, p, pairingLast)
                }
            }

            else -> p..p
        }
    }

    /**
     * 本章最后一页的全局页号：下一章首页的前一页。
     * 已是最后一章（[chapter] + 1 不在章数内）就没有「下一章」，整本收尾兜底——
     * 这里越界一次，整本书都打不开。
     */
    fun chapterLast(
        chapter: Int,
        chapterFirst: Int,
        chapterCount: Int,
        firstPageOf: (Int) -> Int,
        total: Int,
    ): Int {
        val next = (chapter + 1).takeIf { it < chapterCount }?.let(firstPageOf) ?: -1
        return (if (next > chapterFirst) next - 1 else total - 1).coerceAtMost(total - 1)
    }

    /**
     * 从 [from] 起两页一组，返回包含 [page] 的那一组。
     * 组的起点落在 from、from+2、from+4……上；最后一组不足两页就只取一页。
     */
    private fun pairFrom(from: Int, page: Int, last: Int): IntRange {
        val start = from.coerceAtLeast(0)
        val end = last.coerceAtLeast(start)
        val p = page.coerceIn(start, end)
        val groupStart = start + ((p - start) / 2) * 2
        return groupStart..(groupStart + 1).coerceAtMost(end)
    }
}
