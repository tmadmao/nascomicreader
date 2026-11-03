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

    /** 认为「够白」的默认亮度下限。设置里可调，见 [detect] 的 level 参数 */
    const val DEFAULT_LEVEL = 236

    /** 一行里多少比例的像素够白，才算整行是白边 */
    private const val WHITE_RATIO = 0.98f

    /**
     * 单边最多裁掉的比例。
     *
     * 规格定的是「单边裁剪量 ≤ 边长 10%」——两侧各让 10%，内容最多放大到 1.25×。
     * 再放宽就会在极端白边样本上把内容裁掉，所以这个数不能随手调大。
     */
    const val PROTECT = 0.10f

    /**
     * 量一张位图的白边。
     *
     * 逐点 `getPixel` 是跨 JNI 调用，一页要几万次；这里按行/列一次性 `getPixels`
     * 把整条取进复用数组再判，JNI 次数从「行数 × 64」降到「行数」。
     */
    fun detect(
        bitmap: Bitmap,
        level: Int = DEFAULT_LEVEL,
        /** 只裁上下、不动左右：条漫裁完两侧常对不齐 */
        ignoreSides: Boolean = false,
        /** 识别前四边各忽略多少像素，对付扫描件的细黑边 */
        ignorePx: Int = 0,
        /** false = 关掉「单边 ≤ 10%」的保护，可以裁到贴边 */
        protect: Boolean = true,
        /** true = 逐行扫；false = 隔行抽样（快，边界可能差一两像素） */
        precise: Boolean = true,
    ): Bounds {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return Bounds(0, 0, w, h)
        val row = IntArray(w)
        val col = IntArray(h)
        // 忽略边：先整体内缩 N 像素再判，判完再把边界加回来
        val pad = ignorePx.coerceIn(0, minOf(w, h) / 4)
        val innerW = (w - pad * 2).coerceAtLeast(1)
        val innerH = (h - pad * 2).coerceAtLeast(1)
        val result = detect(
            w = innerW,
            h = innerH,
            protect = if (protect) PROTECT else 1f,
            ignoreSides = ignoreSides,
            whiteRow = { y ->
                bitmap.getPixels(row, 0, w, 0, y + pad, w, 1)
                isWhiteLine(innerW, level, offset = pad) { row[it] }
            },
            whiteCol = { x ->
                bitmap.getPixels(col, 0, 1, x + pad, 0, 1, h)
                isWhiteLine(innerH, level, offset = pad) { col[it] }
            },
            rowStep = if (precise) 1 else 2,
        )
        return Bounds(
            result.left + pad,
            result.top + pad,
            result.right + pad,
            result.bottom + pad,
        )
    }

    /**
     * 整页是不是空白（用于跳过空白页）。抽样看，不逐像素扫。
     */
    fun isBlank(bitmap: Bitmap, level: Int = DEFAULT_LEVEL): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (!isWhite(bitmap.getPixel(x, y), level)) return false
                y += stepY
            }
            x += stepX
        }
        return true
    }

    /**
     * 纯逻辑部分，便于单测：给出行/列的白边判定，返回内容边界。
     */
    fun detect(
        w: Int,
        h: Int,
        protect: Float,
        whiteRow: (Int) -> Boolean,
        whiteCol: (Int) -> Boolean,
        ignoreSides: Boolean = false,
        /** 上下各扫多少行取一次样；2 = 隔行（快档），1 = 逐行（精档） */
        rowStep: Int = 1,
    ): Bounds {
        if (w <= 0 || h <= 0) return Bounds(0, 0, w, h)

        val maxX = (w * protect).toInt()
        val maxY = (h * protect).toInt()
        val step = rowStep.coerceAtLeast(1)

        var top = 0
        while (top < maxY && whiteRow(top)) top += step
        if (top > maxY) top = maxY

        var bottom = h
        while (bottom > h - maxY && whiteRow(bottom - 1)) bottom -= step
        if (bottom < h - maxY) bottom = h - maxY

        var left = 0
        var right = w
        // 只裁上下时，左右一律不裁
        if (!ignoreSides) {
            while (left < maxX && whiteCol(left)) left += step
            if (left > maxX) left = maxX
            while (right > w - maxX && whiteCol(right - 1)) right -= step
            if (right < w - maxX) right = w - maxX
        }

        // 四边都顶到保护上限，说明整页几乎全白：保留原页，别裁成一条
        val hitAllLimits = top == maxY && bottom == h - maxY && left == maxX && right == w - maxX
        if (hitAllLimits || right <= left || bottom <= top) return Bounds(0, 0, w, h)
        return Bounds(left, top, right, bottom)
    }

    /** 逐像素采样判断一整条是不是白（按长度取步长，长边也不会太慢） */
    private inline fun isWhiteLine(
        length: Int,
        level: Int,
        offset: Int = 0,
        pixel: (Int) -> Int,
    ): Boolean {
        if (length <= 0) return true
        val step = (length / 64).coerceAtLeast(1)
        var total = 0
        var white = 0
        var i = 0
        while (i < length) {
            if (isWhite(pixel(i + offset), level)) white++
            total++
            i += step
        }
        return total > 0 && white.toFloat() / total >= WHITE_RATIO
    }

    private fun isWhite(color: Int, level: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // 亮度按人眼权重，纯白与接近白的灰都算
        return (r * 299 + g * 587 + b * 114) / 1000 >= level
    }
}
