package com.nascomic.reader.ui.author

/**
 * 索引条拖到首尾之外时的位移换算。
 *
 * 单独放一份是为了能脱离 View 验证阻尼与封顶：手感参数就该有数可算，
 * 而不是只能靠手指试。
 */
object IndexBounce {

    /** 阻尼：手指越过边界 1px，条子只跟着走 0.35px */
    const val DAMPING = 0.35f

    /** 松手弹回用的时长 */
    const val SETTLE_MS = 240L

    /** 最多拉开多少：再远也只是「到顶了」的一个提示，不该让条子飞出去 */
    const val MAX_DP = 10f

    /**
     * [over] 是越过边界的位移：往上为负、往下为正。
     * 返回值同号，绝对值按阻尼缩小并封顶。
     */
    fun offset(over: Float, max: Float, damping: Float = DAMPING): Float {
        if (max <= 0f) return 0f
        return (over * damping).coerceIn(-max, max)
    }
}
