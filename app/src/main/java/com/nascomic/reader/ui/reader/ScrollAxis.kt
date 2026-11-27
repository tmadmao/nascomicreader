package com.nascomic.reader.ui.reader

/**
 * 竖向滚动的纯算术：位置与边界。
 *
 * 小位移不能报「到头」：低速档配高刷新率时一帧可能不足半像素，
 * 若拿「这一帧没挪够半像素」当边界，调用方会误接页、甚至误判整本读完。
 * 位置本身是浮点，亚像素位移自然累计；只有真滚到该方向的边界才报 false。
 */
class ScrollAxis {

    var position: Float = 0f
        private set

    /** 内容总高（px） */
    var total: Float = 0f

    /** 视口高（px） */
    var viewport: Float = 0f

    val max: Float get() = (total - viewport).coerceAtLeast(0f)

    val atTop: Boolean get() = position <= EDGE

    val atBottom: Boolean get() = position >= max - EDGE

    /**
     * 滚 [dy]（正 = 往下看）。返回 false 仅当已在该方向的边界上；
     * 不足一像素的位移照样累计进位置，不算到头。
     */
    fun scrollBy(dy: Float): Boolean {
        if (dy > 0f && position >= max - EDGE) return false
        if (dy < 0f && position <= EDGE) return false
        if (dy == 0f) return true
        position = (position + dy).coerceIn(0f, max)
        return true
    }

    /** 直接跳到某处（接页后落位、进度条） */
    fun scrollTo(y: Float) {
        position = y.coerceIn(0f, max)
    }

    /**
     * 当前位置距某槽位顶部的**有符号**偏移：视口跨两页时屏幕顶还在上一页，
     * 偏移为负；钳成 0 会让后台返回重建窗口后画面前跳。落回靠 [scrollTo] 钳界。
     */
    fun offsetFrom(top: Float): Float = position - top

    /** 头部插/丢内容后把位置补偿同样的量，画面不跳 */
    fun shift(delta: Float) {
        position = (position + delta).coerceIn(0f, max)
    }

    /** 内容总高变化后把位置钳回界内 */
    fun clamp() {
        position = position.coerceIn(0f, max)
    }

    fun reset() {
        position = 0f
    }

    companion object {
        /** 边界容差：与画面「到顶 / 到底」的判定同口径 */
        private const val EDGE = 0.5f
    }
}