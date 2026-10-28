package com.nascomic.reader.ui.shelf

/**
 * 书架该分几列。
 *
 * 固定列数在手机上没问题，到了平板就变成三本巨大的封面、一屏放不下几本书。
 * 这里按「一格至少多宽」反推列数：手机 360dp 得到 3 列，平板 711dp 得到 6 列。
 */
object ShelfGrid {

    /** 一格的最小宽度（dp）。再窄书名就挤成两三行了 */
    const val MIN_CELL_DP = 108

    /** 上限，免得超宽屏出现一条细线般的封面 */
    const val MAX_COLUMNS = 8

    fun columns(availableWidthPx: Int, minCellPx: Int): Int {
        if (availableWidthPx <= 0 || minCellPx <= 0) return 3
        return (availableWidthPx / minCellPx).coerceIn(3, MAX_COLUMNS)
    }

    fun cellWidth(availableWidthPx: Int, columns: Int): Int {
        if (columns <= 0) return 0
        return availableWidthPx / columns
    }
}
