package com.nascomic.reader.ui.reader

/**
 * 跨页宽图拆半的纯几何：左半、右半各取哪个矩形，以及竖向连贯里两半的先后。
 *
 * 中点向下取整（奇数宽时右半多一列），横向逐半看与竖向上下排用同一套矩形，
 * 显示与导航才不会各算各的。半页顺序由「先看左半 / 先看右半」独立设置决定，
 * 不随阅读方向自动反转。
 */
object WideSplit {

    /** 源位图上的一块矩形，右 / 下为开区间 */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 取哪一半：0 左半，1 右半 */
    fun halfAt(full: Box, which: Int): Box {
        val mid = full.left + full.width / 2
        return if (which == 1) {
            Box(mid, full.top, full.right, full.bottom)
        } else {
            Box(full.left, full.top, mid, full.bottom)
        }
    }

    /**
     * 竖向连贯用的两半，按阅读先后排：[firstHalf] 0 左半在上，1 右半在上。
     * 两半上下相接铺满屏宽，等效于把跨页大图放大一倍逐段看。
     */
    fun orderedHalves(full: Box, firstHalf: Int): List<Box> {
        val left = halfAt(full, 0)
        val right = halfAt(full, 1)
        return if (firstHalf == 1) listOf(right, left) else listOf(left, right)
    }

    /**
     * 横向逐半导航：前进时看完 [current] 接着看哪半；null 表示该翻到下一页。
     * 顺序只由 [firstHalf] 决定，不随阅读方向反转。
     */
    fun nextHalf(current: Int, firstHalf: Int): Int? =
        if (current == firstHalf) 1 - firstHalf else null

    /** 后退时看完 [current] 回看哪半；null 表示该翻到上一页 */
    fun prevHalf(current: Int, firstHalf: Int): Int? =
        if (current == 1 - firstHalf) firstHalf else null

    /** 进入宽图落在哪半：正着进看第一半，从后往前进看最后读的那半 */
    fun entryHalf(firstHalf: Int, fromBehind: Boolean): Int =
        if (fromBehind) 1 - firstHalf else firstHalf
}