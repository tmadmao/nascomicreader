package com.nascomic.reader.ui.reader

/**
 * 条漫自动下滑的状态与算术：开着就以恒定速度往下推进，一碰就停，滚到书的尾自停。
 *
 * 速度用 dp/s 而不是 px/s：同一档在不同分辨率的设备上滚过的物理距离一致。
 * 这里只管状态与位移量，定时驱动与真正的滚动在阅读页。
 */
class AutoScroll {

    /** 速度档位，1–9；档位与 dp/s 的换算见 [speedDpPerSecond] */
    var level: Int = DEFAULT_LEVEL
        set(value) {
            field = clampLevel(value)
        }

    /** 是否正在自动下滑 */
    var running: Boolean = false
        private set

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }

    /** 内容上落下一次触摸：立即停。调速浮层上的触摸不走这里 */
    fun onContentTouch() {
        stop()
    }

    /**
     * 推进一帧。返回这一帧该往下滚多少 dp；没在跑就返回 0。
     * [atEnd] 为真（整本读完的空态已出现）时自停，之后不再推进。
     */
    fun tick(seconds: Float, atEnd: Boolean): Float {
        if (!running) return 0f
        if (atEnd) {
            stop()
            return 0f
        }
        if (seconds <= 0f) return 0f
        return speedDpPerSecond(level) * seconds
    }

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 9
        const val DEFAULT_LEVEL = 3

        /** 每档 40 dp/s：最低档约 20 秒一屏（800dp 高），最高档约 2 秒一屏 */
        private const val DP_PER_LEVEL = 40f

        fun clampLevel(value: Int): Int = value.coerceIn(MIN_LEVEL, MAX_LEVEL)

        fun speedDpPerSecond(level: Int): Float = clampLevel(level) * DP_PER_LEVEL
    }
}