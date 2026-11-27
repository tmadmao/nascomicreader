package com.nascomic.reader.ui.author

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nascomic.reader.R

/**
 * 右侧那条半透明的字母索引。
 *
 * 只画「真的有作者」的字母，没有的节不占位置——否则拖动时点到空节会跳不动。
 * 按住上下滑连续跳，松手停在最近一节。
 */
class LetterIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var letters: List<String> = emptyList()
    private var current: String? = null

    /**
     * 拖到首尾之外时整条被拉开的那一点位移（px）。
     * 手指还在滑，但已经没有下一节可跳了——让条子跟着走一点点再弹回去，
     * 比一动不动更容易读出「到头了」。
     */
    private var bounce = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 10f
    }
    private val activePaint = Paint(paint).apply { isFakeBoldText = true }

    /** 半透明胶囊底：只铺在字母下方，别挡住正文 */
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shell_ink)
        alpha = 26 // 约 10%
    }

    /** 顶部的心：收藏占位，功能没做，只画不接事件 */
    private val heart = android.graphics.Path()
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_accent)
        style = Paint.Style.FILL
    }

    /** 点/拖到某个字母；调用方负责滚到对应节 */
    var onLetter: ((String) -> Unit)? = null

    private fun pick(y: Float) {
        val idx = indexAt(y) ?: return
        val letter = letters[idx]
        if (letter != current) {
            current = letter
            invalidate()
        }
        onLetter?.invoke(letter)
    }

    fun setLetters(list: List<String>, current: String?) {
        val changed = list.size != letters.size || list != letters
        letters = list
        this.current = current
        // 列表换了，之前拉开的位移就不再对应当前这一条
        if (bounce != 0f) {
            bounce = 0f
            animate().cancel()
            translationY = 0f
        }
        // 高度是按字母数算的：不变就不重测，变了必须 requestLayout，
        // 否则首次进来（还没数据、高度 0）之后永远不会再长出来
        if (changed) requestLayout()
        invalidate()
    }

    fun setCurrent(letter: String?) {
        if (current == letter) return
        current = letter
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (heartHeight + letters.size * lineHeight).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(paddingLeft + paddingRight + dp(20), widthMeasureSpec), h)
    }

    private val lineHeight: Float
        get() = paint.textSize * 1.55f

    private val heartHeight: Float
        get() = paint.textSize * 1.6f

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return

        // 胶囊底 + 顶部的心
        val radii = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(radii, dp(13).toFloat(), dp(13).toFloat(), pill)
        drawHeart(canvas)

        val total = heartHeight + letters.size * lineHeight
        val startY = (height - total) / 2f + heartHeight + paint.textSize
        val cx = width / 2f
        letters.forEachIndexed { i, letter ->
            val isOn = letter == current
            val p = if (isOn) activePaint else paint
            p.color = ContextCompat.getColor(
                context,
                if (isOn) R.color.brand_accent else R.color.shell_ink_faint,
            )
            canvas.drawText(letter, cx, startY + i * lineHeight, p)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                followBounce(event.y)
                pick(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                followBounce(event.y)
                pick(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                settleBounce()
                return true
            }
        }
        return true
    }

    /**
     * 手指越过首/尾之后，条子按阻尼跟一小段。
     * 位移明显小于手指，看得出是被「拉住」的。
     */
    private fun followBounce(y: Float) {
        val top = contentTop()
        val bottom = contentBottom()
        val over = when {
            y < top -> y - top
            y > bottom -> y - bottom
            else -> 0f
        }
        val next = IndexBounce.offset(over, maxBounce())
        // 每次按下/滑动都重新落位：弹回被打断时视图可能停在半路，不能只比数值
        bounce = next
        translationY = bounce
    }

    /** 松手弹回原位。带一点过冲，才有「弹」的意思 */
    private fun settleBounce() {
        if (bounce == 0f) return
        bounce = 0f
        animate()
            .translationY(0f)
            .setDuration(IndexBounce.SETTLE_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .start()
    }

    private fun contentTop(): Float = (height - contentHeight()) / 2f

    private fun contentBottom(): Float = contentTop() + contentHeight()

    private fun contentHeight(): Float = heartHeight + letters.size * lineHeight

    private fun maxBounce(): Float =
        IndexBounce.MAX_DP * resources.displayMetrics.density

    private fun indexAt(y: Float): Int? {
        if (letters.isEmpty()) return null
        val total = heartHeight + letters.size * lineHeight
        val startY = (height - total) / 2f + heartHeight
        val idx = ((y - startY) / lineHeight).toInt()
        return idx.coerceIn(0, letters.size - 1)
    }

    /** 心形路径：两个上凸的肩 + 一个尖，够小的时候不用更精细 */
    private fun drawHeart(canvas: Canvas) {
        val w = paint.textSize * 0.95f
        val h = paint.textSize * 0.85f
        val left = (width - w) / 2f
        val top = (height - (heartHeight + letters.size * lineHeight)) / 2f + dp(1)
        heart.reset()
        heart.moveTo(left + w / 2f, top + h)
        heart.cubicTo(left - w * 0.15f, top + h * 0.55f, left + w * 0.18f, top, left + w / 2f, top + h * 0.28f)
        heart.cubicTo(left + w * 0.82f, top, left + w * 1.15f, top + h * 0.55f, left + w / 2f, top + h)
        heart.close()
        canvas.drawPath(heart, heartPaint)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
