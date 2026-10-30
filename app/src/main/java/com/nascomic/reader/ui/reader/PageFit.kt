package com.nascomic.reader.ui.reader

/**
 * 一屏内多张图的摆放计算。纯数学，不碰 Canvas，可以在 JVM 上单测。
 *
 * 规则：每张图按自身比例缩放，整体横向均分、竖直居中，左右留边距，图与图之间留缝隙。
 */
object PageFit {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * @param containerW 容器宽（px）
     * @param containerH 容器高（px）
     * @param margin 左右各留的边距（px）
     * @param gap 并排时图与图之间的缝隙（px）
     * @param sizes 每张图的原始像素尺寸
     * @param fitWidth 页面适应：true = 按宽度铺满（高图可超出上下，靠拖动看），
     *                 false = 整页可见（受容器高约束）
     */
    fun fit(
        containerW: Int,
        containerH: Int,
        margin: Int,
        gap: Int,
        sizes: List<Pair<Int, Int>>,
        fitWidth: Boolean = false,
    ): List<Box> {
        if (sizes.isEmpty() || containerW <= 0 || containerH <= 0) return emptyList()

        val m = margin.coerceIn(0, containerW / 2)
        val usable = (containerW - 2 * m).coerceAtLeast(1)
        val gaps = gap * (sizes.size - 1)
        val slot = ((usable - gaps) / sizes.size.toFloat()).coerceAtLeast(1f)

        return sizes.mapIndexed { index, size ->
            val (w, h) = size
            if (w <= 0 || h <= 0) {
                Box(0f, 0f, 0f, 0f)
            } else {
                // 完整显示时同时受槽宽与容器高约束，取小的那个比例，保证不越界；
                // 按宽度铺满时只看槽宽，高图上下超出是预期的
                val scale = if (fitWidth) {
                    slot / w
                } else {
                    minOf(slot / w, containerH / h.toFloat())
                }
                val drawW = w * scale
                val drawH = h * scale
                val slotLeft = m + index * (slot + gap)
                val left = slotLeft + (slot - drawW) / 2f
                val top = (containerH - drawH) / 2f
                Box(left, top, left + drawW, top + drawH)
            }
        }
    }
}
