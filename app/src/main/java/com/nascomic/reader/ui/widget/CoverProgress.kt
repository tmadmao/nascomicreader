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
 * 封面底部那条进度线：从左往右填已读比例，高度由布局给（3dp）。
 *
 * 没读过的书不画，只留一条空白——进度是读过的记号，不该给每本书都描一道边。
 */
class CoverProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shell_divider)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_accent)
    }
    private val rect = RectF()

    /** 0f–1f。0 时整条都不画 */
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
        if (w <= 0f || h <= 0f || ratio <= 0f) return

        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val filled = (w * ratio).coerceIn(h, w)
        rect.set(0f, 0f, filled, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }
}
