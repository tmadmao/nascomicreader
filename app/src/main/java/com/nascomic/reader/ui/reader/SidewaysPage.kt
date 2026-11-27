package com.nascomic.reader.ui.reader

/**
 * 一张横向页面到底是「跨页大图」还是「单页横着扫进来」。
 *
 * 两者的外框几乎一样宽（都在 1.4:1 上下），只看尺寸分不出来，得看内容走向：
 * 正文是一条条水平的字行；单页横着扫进来以后，这些行在图上就变成竖的条带。
 * 所以比较「逐行均值」与「逐列均值」的高频起伏——起伏更大的那个方向就是字行方向。
 *
 * 判不准一律 [Verdict.UNKNOWN]：调用方据此保持原样，既不提示、也不改变拆分。
 * 宁可漏掉一页，也不要把两张不相干的内容拼一起，或者把跨页拦腰切断。
 */
object SidewaysPage {

    /** 横向页面的一段内容走向 */
    enum class Verdict {
        /** 竖版页，不参与判定 */
        NONE,

        /** 字行是竖的：单页横着扫进来，转 90° 才是正的 */
        SIDEWAYS,

        /** 字行是横的：左右两页装在一张图里，中间是装订缝 */
        SPREAD,

        /** 证据不足，保持原逻辑 */
        UNKNOWN,
    }

    /** 低于这个宽高比的不算横向页面 */
    private const val MIN_RATIO = 1.25f

    /** 超过这个宽高比的多是长条图，既不是跨页也不是横置单页 */
    private const val MAX_RATIO = 2.6f

    /** 逐行 / 逐列起伏的均值至少要这么大，才认为画面上有内容 */
    private const val ENERGY_FLOOR = 400.0

    /** 两个方向差到这个倍数才下结论 */
    private const val DOMINANCE = 2.0

    /** 中缝判据：中央条带的明暗差至少要这么大（灰度），且占列起伏的比例够高 */
    private const val SEAM_MIN = 12.0
    private const val SEAM_RATIO = 0.2

    /**
     * @param luma 逐像素亮度，0–255，按行排列，长度不小于 width * height
     */
    fun judge(luma: IntArray, width: Int, height: Int): Verdict {
        if (width <= 2 || height <= 2) return Verdict.UNKNOWN
        if (luma.size < width * height) return Verdict.UNKNOWN

        val ratio = width.toFloat() / height
        if (ratio < MIN_RATIO) return Verdict.NONE
        if (ratio > MAX_RATIO) return Verdict.UNKNOWN

        // 页边一圈常是纸白或扫描阴影，不参与统计
        val inset = (minOf(width, height) / 40).coerceAtLeast(1)
        val x0 = inset
        val x1 = width - inset
        val y0 = inset
        val y1 = height - inset
        if (x1 - x0 < 8 || y1 - y0 < 8) return Verdict.UNKNOWN

        val rows = profileByRows(luma, width, x0, x1, y0, y1)
        val cols = profileByCols(luma, width, height, x0, x1, y0, y1)

        // 中缝：装订处的阴影或纸白，是一条窄的明暗带
        if (hasSeam(cols, width)) return Verdict.SPREAD

        val window = (minOf(width, height) / 20).coerceAtLeast(2)
        val rowEnergy = highPassEnergy(rows, y0, y1, window)
        val colEnergy = highPassEnergy(cols, x0, x1, window)
        if (rowEnergy + colEnergy < ENERGY_FLOOR) return Verdict.UNKNOWN

        return when {
            colEnergy > rowEnergy * DOMINANCE -> Verdict.SIDEWAYS
            rowEnergy > colEnergy * DOMINANCE -> Verdict.SPREAD
            else -> Verdict.UNKNOWN
        }
    }

    private fun profileByRows(
        luma: IntArray,
        width: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): DoubleArray {
        val out = DoubleArray(y1)
        val span = (x1 - x0).coerceAtLeast(1)
        for (y in y0 until y1) {
            var sum = 0L
            val base = y * width
            for (x in x0 until x1) sum += luma[base + x]
            out[y] = sum.toDouble() / span
        }
        return out
    }

    private fun profileByCols(
        luma: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): DoubleArray {
        val out = DoubleArray(x1)
        val span = (y1 - y0).coerceAtLeast(1)
        for (x in x0 until x1) {
            var sum = 0L
            for (y in y0 until y1) sum += luma[y * width + x]
            out[x] = sum.toDouble() / span
        }
        return out
    }

    /**
     * 均值曲线的高频起伏：减去左右各隔 [window] 的均值再平方。
     * 字行与空白交替得越密、越整齐，这个值越大；整片色块则接近于零。
     */
    private fun highPassEnergy(profile: DoubleArray, from: Int, to: Int, window: Int): Double {
        if (to - from <= 2) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val lo = profile[(i - window).coerceAtLeast(from)]
            val hi = profile[(i + window).coerceAtMost(to - 1)]
            val diff = profile[i] - (lo + hi) / 2.0
            sum += diff * diff
        }
        return sum / (to - from)
    }

    private fun hasSeam(cols: DoubleArray, width: Int): Boolean {
        if (cols.isEmpty()) return false
        val mid = width / 2
        val band = (width / 200).coerceAtLeast(2)
        val inner = meanOf(cols, mid - band, mid + band + 1)
        val left = meanOf(cols, mid - 4 * band, mid - 2 * band)
        val right = meanOf(cols, mid + 2 * band + 1, mid + 4 * band + 1)
        val seam = kotlin.math.abs(inner - (left + right) / 2.0)
        val range = (cols.maxOrNull() ?: 0.0) - (cols.minOrNull() ?: 0.0)
        return seam > SEAM_MIN && seam > range * SEAM_RATIO
    }

    private fun meanOf(values: DoubleArray, from: Int, to: Int): Double {
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(values.size)
        if (hi <= lo) return 0.0
        var sum = 0.0
        for (i in lo until hi) sum += values[i]
        return sum / (hi - lo)
    }
}
