package com.nascomic.reader.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 进阅读时闪一下的分区提示：左右两块是翻页区，中间一条是呼出工具栏。
 * 只是看一眼的浮层，不接触摸，2.5 秒自己淡出。
 */
class ZoneHintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val box = RectF()

    /** 点右侧是下一页还是上一页，跟阅读方向一致 */
    var forwardRight: Boolean = true

    var leftLabel: String = ""
    var middleLabel: String = ""
    var rightLabel: String = ""

    private val radius = 10f * resources.displayMetrics.density
    private val verticalInset = 26f * resources.displayMetrics.density
    private val gap = 6f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val left = width * ZONE_LEFT
        val right = width * ZONE_RIGHT
        val top = verticalInset
        val bottom = height - verticalInset

        // 左右翻页区：品牌橙的淡色块
        fill.color = 0x2EF06A35
        box.set(0f, top, left - gap, bottom)
        canvas.drawRoundRect(box, radius, radius, fill)
        box.set(right + gap, top, width.toFloat(), bottom)
        canvas.drawRoundRect(box, radius, radius, fill)

        // 中间呼出工具栏的区：深靛，和两侧区分开
        fill.color = 0x2E1A2338
        box.set(left + gap, top, right - gap, bottom)
        canvas.drawRoundRect(box, radius, radius, fill)

        text.color = 0xFFF8F5EF.toInt()
        drawLabel(canvas, leftLabel, (0f + left) / 2f, top, bottom)
        drawLabel(canvas, middleLabel, (left + right) / 2f, top, bottom)
        drawLabel(canvas, rightLabel, (right + width) / 2f, top, bottom)
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, top: Float, bottom: Float) {
        if (label.isEmpty()) return
        val baseline = (top + bottom) / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, cx, baseline, text)
    }

    companion object {
        /** 与阅读页的分区判定共用同一组分界 */
        private const val ZONE_LEFT = 0.35f
        private const val ZONE_RIGHT = 0.65f
    }
}
