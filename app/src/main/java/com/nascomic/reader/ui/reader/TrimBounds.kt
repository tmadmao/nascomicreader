package com.nascomic.reader.ui.reader

import android.graphics.Bitmap

/**
 * 白边识别：从四边向内找真正有内容的那一块。
 *
 * 判定的是「整行/整列是不是白」，不是「边上那个像素是不是白」——扫描件边缘常有噪点，
 * 按单像素判会把白边留下一圈。
 */
object TrimBounds {

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 认为「够白」的亮度下限 */
    private const val WHITE_LEVEL = 236

    /** 一行里多少比例的像素够白，才算整行是白边 */
    private const val WHITE_RATIO = 0.98f

    /** 单边最多裁掉的比例。整页都是白时不能把它裁成 0 像素 */
    private const val PROTECT = 0.25f

    fun detect(bitmap: Bitmap): Bounds =
        detect(
            w = bitmap.width,
            h = bitmap.height,
            protect = PROTECT,
            whiteRow = { y -> isWhiteLine(bitmap.width) { x -> bitmap.getPixel(x, y) } },
            whiteCol = { x -> isWhiteLine(bitmap.height) { y -> bitmap.getPixel(x, y) } },
        )

    /**
     * 纯逻辑部分，便于单测：给出行/列的白边判定，返回内容边界。
     */
    fun detect(
        w: Int,
        h: Int,
        protect: Float,
        whiteRow: (Int) -> Boolean,
        whiteCol: (Int) -> Boolean,
    ): Bounds {
        if (w <= 0 || h <= 0) return Bounds(0, 0, w, h)

        val maxX = (w * protect).toInt()
        val maxY = (h * protect).toInt()

        var top = 0
        while (top < maxY && whiteRow(top)) top++

        var bottom = h
        while (bottom > h - maxY && whiteRow(bottom - 1)) bottom--

        var left = 0
        while (left < maxX && whiteCol(left)) left++

        var right = w
        while (right > w - maxX && whiteCol(right - 1)) right--

        // 四边都顶到保护上限，说明整页几乎全白：保留原页，别裁成一条
        val hitAllLimits = top == maxY && bottom == h - maxY && left == maxX && right == w - maxX
        if (hitAllLimits || right <= left || bottom <= top) return Bounds(0, 0, w, h)
        return Bounds(left, top, right, bottom)
    }

    /** 逐像素采样判断一整条是不是白（按长度取步长，长边也不会太慢） */
    private inline fun isWhiteLine(length: Int, pixel: (Int) -> Int): Boolean {
        if (length <= 0) return true
        val step = (length / 64).coerceAtLeast(1)
        var total = 0
        var white = 0
        var i = 0
        while (i < length) {
            if (isWhite(pixel(i))) white++
            total++
            i += step
        }
        return total > 0 && white.toFloat() / total >= WHITE_RATIO
    }

    private fun isWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // 亮度按人眼权重，纯白与接近白的灰都算
        return (r * 299 + g * 587 + b * 114) / 1000 >= WHITE_LEVEL
    }
}
