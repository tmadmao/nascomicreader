package com.nascomic.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.nascomic.reader.R

/**
 * 书脊进度条：一条竖细条，从下往上填已读比例。
 *
 * 全站唯一的高亮记号（设计说明 §8「记忆点」）：不做数字角标，也不叠在封面上，
 * 单独占 4dp 立在封面左侧，像书被抽出了多少。
 */
class SpineProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shell_divider)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_indigo)
    }
    private val rect = RectF()

    /** 0f–1f。0 或负数时不画填充，只留空槽 */
    var ratio: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = w / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val filled = h * ratio
        if (filled <= 0f) return
        val top = h - filled.coerceAtLeast(w)
        rect.set(0f, top, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }
}
