package com.nascomic.reader.ui.reader

/**
 * 缩放与平移的纯算术，不碰 Canvas，可以在 JVM 上单测。
 *
 * 坐标约定：缩放以视图中心为枢轴，先缩放再平移，
 * 缩放后的屏幕坐标 = (基础坐标 - 中心) × 倍率 + 中心 + 平移量。
 */
object ZoomMath {

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 3f

    data class Offset(val x: Float, val y: Float)

    /**
     * 求「锚点落到指定位置」所需的平移量。
     *
     * @param anchorX 锚点在缩放前的屏幕坐标
     * @param targetX 锚点缩放后要落到的屏幕坐标
     * @param centerX 枢轴（视图中心）
     * @param scale 新的缩放倍率
     */
    fun panTo(
        anchorX: Float,
        anchorY: Float,
        targetX: Float,
        targetY: Float,
        centerX: Float,
        centerY: Float,
        scale: Float,
    ): Offset = Offset(
        x = targetX - centerX - (anchorX - centerX) * scale,
        y = targetY - centerY - (anchorY - centerY) * scale,
    )

    /**
     * 把平移量夹在合法范围内：内容比视图小时居中，比视图大时边缘不留空。
     *
     * @param contentLeft 内容框在缩放前的屏幕坐标
     */
    fun clampPan(
        panX: Float,
        panY: Float,
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        contentBottom: Float,
        viewW: Float,
        viewH: Float,
        centerX: Float,
        centerY: Float,
        scale: Float,
    ): Offset {
        val w = (contentRight - contentLeft) * scale
        val h = (contentBottom - contentTop) * scale

        val x = if (w <= viewW) {
            // 比视图窄：整块居中。先算「不平移时」内容左边的屏幕坐标，再补到居中
            val leftAtZero = (contentLeft - centerX) * scale + centerX
            viewW / 2f - (leftAtZero + w / 2f)
        } else {
            val leftAtZero = (contentLeft - centerX) * scale + centerX
            val rightAtZero = leftAtZero + w
            panX.coerceIn(viewW - rightAtZero, -leftAtZero)
        }
        val y = if (h <= viewH) {
            val topAtZero = (contentTop - centerY) * scale + centerY
            viewH / 2f - (topAtZero + h / 2f)
        } else {
            val topAtZero = (contentTop - centerY) * scale + centerY
            val bottomAtZero = topAtZero + h
            panY.coerceIn(viewH - bottomAtZero, -topAtZero)
        }
        return Offset(x, y)
    }
}
