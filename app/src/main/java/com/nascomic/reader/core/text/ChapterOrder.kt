package com.nascomic.reader.core.text

/**
 * 章的先后。
 *
 * 有一类名字不能按字面排：`序章`、`前篇`、`上卷` 是正文之前的引子，
 * `终章`、`后篇`、`下卷` 收在最后。可它们的首字码位都比「第」小——
 * 「下」(U+4E0B) 比「第」(U+7B2C) 还小，不管的话 `下卷` 会跑到「第1话」前面去。
 *
 * 所以先分档再比名字：引子档 → 正文档 → 收尾档。档内仍按名称自然序。
 *
 * 只认「单独一个字」或「字 + 量词」（`上`、`上卷`、`序章`、`终`）：
 * `下北泽`、`终末的女武神` 这类开头带这些字的正常名字不受影响。
 *
 * 覆盖的是漫画的存法（序 / 前 / 上 / 中 / 下 / 后 / 终 / 尾）。动画那套
 * `SP`、`OVA`、`S00E01` 不在其中。
 */
object ChapterOrder {

    /** 档位前缀：先比这一位，正文一律居中；同一档里再按下面的次序号分先后 */
    private const val BEFORE = '0'
    private const val NORMAL = '1'
    private const val AFTER = '2'

    /** 正文之前，按这个次序：序 → 前 → 上（越靠上越接近正文） */
    private val OPENERS = mapOf('序' to 0, '前' to 1, '上' to 2)

    /** 收在最后：下 → 后 → 终 → 尾 */
    private val CLOSERS = mapOf('下' to 0, '后' to 1, '後' to 1, '终' to 2, '終' to 2, '尾' to 3)

    /** 这些字跟在上面那些字后面，才说明它是在讲章节位置（`上卷`、`序章`） */
    private val UNITS = setOf(
        '章', '篇', '編', '卷', '巻', '话', '話', '集', '部', '幕', '回', '册', '冊', '本',
    )

    /** 一章在排序里用的键：档位 + 名称自然序 */
    fun key(title: String): String = slotOf(title) + NaturalSort.key(title)

    fun <T> comparator(titleOf: (T) -> String): Comparator<T> =
        Comparator { a, b -> key(titleOf(a)).compareTo(key(titleOf(b))) }

    private fun slotOf(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return NORMAL.toString()
        val alone = t.length == 1
        val withUnit = t.length > 1 && t[1] in UNITS
        if (!alone && !withUnit) return NORMAL.toString()
        OPENERS[t[0]]?.let { return "$BEFORE$it" }
        CLOSERS[t[0]]?.let { return "$AFTER$it" }
        return NORMAL.toString()
    }
}
