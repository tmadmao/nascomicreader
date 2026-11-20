package com.nascomic.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nascomic.reader.R
import kotlin.math.roundToInt

/**
 * 阅读菜单里那条半透明的进度条。
 *
 * 整条就是触摸区（48dp 高），不要求手指压在那 4dp 的线上——细线上抓东西是手机上手感最差的事。
 * 按下去时轨道变粗、滑块变大，但**颜色不变**：深浅变化会被当成两种控件。
 *
 * 右侧阅读（日漫）时整条镜像：右端是 0，往左才增长，页码与百分比两个文字也跟着换边。
 */
class ProgressScrubber @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var max: Int = 0
        private set
    var progress: Int = 0
        private set
    var rtl: Boolean = false
        private set

    /** 拖动中实时回调：调用方直接把页面跳过去，不做动画 */
    var onScrub: ((Int) -> Unit)? = null
    var onScrubEnd: (() -> Unit)? = null

    private var dragging = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shell_ink)
        alpha = (0.84f * 255).roundToInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = (0.26f * 255).roundToInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1 }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x1
        alpha = (0.18f * 255).roundToInt()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1 }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x1
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val pctPaint = Paint(labelPaint).apply {
        alpha = (0.72f * 255).roundToInt()
        isFakeBoldText = false
    }

    fun setState(max: Int, progress: Int, rtl: Boolean) {
        val changed = this.max != max || this.progress != progress || this.rtl != rtl
        this.max = max.coerceAtLeast(0)
        this.progress = progress.coerceIn(0, this.max)
        this.rtl = rtl
        if (changed) {
            contentDescription = context.getString(
                R.string.reader_scrub_desc,
                progress + 1,
                this.max.coerceAtLeast(1),
            )
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320f).roundToInt(), widthMeasureSpec),
            resolveSize(dp(48f).roundToInt(), heightMeasureSpec),
        )
    }

    private fun trackHeight(): Float = dp(if (dragging) 6f else 4f)
    private fun thumbRadius(): Float = dp(if (dragging) 9f else 7f)
    private fun haloRadius(): Float = dp(if (dragging) 15f else 11f)

    private fun pad(): Float = dp(18f)
    private fun gap(): Float = dp(12f)

    private fun pageLabel(): String = "P.${progress + 1}"

    /** 最后一页 = 100%：分母是「页数 - 1」，不是页数 */
    private fun lastIndex(): Int = (max - 1).coerceAtLeast(1)

    private fun ratio(): Float = (progress.toFloat() / lastIndex()).coerceIn(0f, 1f)

    private fun percentLabel(): String = "${(ratio() * 100).roundToInt()}%"

    /** 轨道的左右边界：两个文字各自占位后剩下的那一段 */
    private fun trackBounds(): RectF {
        val labelW = labelPaint.measureText(pageLabel())
        val pctW = pctPaint.measureText(percentLabel())
        val left = pad() + labelW + gap()
        val right = width - pad() - pctW - gap()
        return RectF(left, 0f, right.coerceAtLeast(left + 1f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, capsule)

        val tb = trackBounds()
        val cy = h / 2f
        val th = trackHeight()
        val radius = th / 2f
        canvas.drawRoundRect(RectF(tb.left, cy - radius, tb.right, cy + radius), radius, radius, trackPaint)

        val ratio = ratio()
        val fillW = (tb.width() * ratio).coerceAtLeast(if (progress > 0) th else 0f)
        if (fillW > 0f) {
            val left = if (rtl) tb.right - fillW else tb.left
            canvas.drawRoundRect(
                RectF(left, cy - radius, left + fillW, cy + radius),
                radius, radius, fillPaint,
            )
        }

        // 滑块压在已读与未读的交界处
        val thumbX = if (rtl) tb.right - fillW else tb.left + fillW
        canvas.drawCircle(thumbX, cy, haloRadius(), haloPaint)
        canvas.drawCircle(thumbX, cy, thumbRadius(), thumbPaint)

        // 文字：右侧阅读时页码在右、百分比在左，整条是镜像的
        val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        if (rtl) {
            canvas.drawText(pageLabel(), w - pad() - labelPaint.measureText(pageLabel()), baseline, labelPaint)
            canvas.drawText(percentLabel(), pad(), baseline, pctPaint)
        } else {
            canvas.drawText(pageLabel(), pad(), baseline, labelPaint)
            canvas.drawText(percentLabel(), w - pad() - pctPaint.measureText(percentLabel()), baseline, pctPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (max <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                val target = valueAt(event.x)
                if (target != progress) {
                    progress = target
                    invalidate()
                    onScrub?.invoke(target)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    invalidate()
                    onScrubEnd?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun valueAt(x: Float): Int {
        val tb = trackBounds()
        val raw = ((x - tb.left) / tb.width()).coerceIn(0f, 1f)
        val ratio = if (rtl) 1f - raw else raw
        return (ratio * lastIndex()).roundToInt().coerceIn(0, lastIndex())
    }
}
