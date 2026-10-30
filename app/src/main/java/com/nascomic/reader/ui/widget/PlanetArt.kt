package com.nascomic.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 深色卡片右侧的星球：一层暖光、一个渐变球、一道轨道环、几颗碎星。
 *
 * 全用 Canvas 画，不出位图——卡片在手机与平板上宽度差一倍，
 * 一张图要么糊要么留白，画出来就跟着卡片走。
 */
class PlanetArt @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x73F5C37B.toInt()
        strokeWidth = dp(1.2f)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF6E8.toInt() }

    private var glow: RadialGradient? = null
    private var body: LinearGradient? = null
    private val ringRect = RectF()

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = h * 0.30f
        centerX = w - radius * 2.1f
        centerY = h * 0.47f

        glow = RadialGradient(
            centerX, centerY, h * 0.95f,
            intArrayOf(0x73E58557, 0x14E58557, 0x001A2338),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        body = LinearGradient(
            centerX - radius, centerY - radius, centerX + radius, centerY + radius,
            0xFFFFB06E.toInt(), 0xFFD95E3B.toInt(),
            Shader.TileMode.CLAMP,
        )
        val rx = radius * 1.68f
        val ry = radius * 0.44f
        ringRect.set(centerX - rx, centerY - ry, centerX + rx, centerY + ry)
    }

    override fun onDraw(canvas: Canvas) {
        val g = glow ?: return
        paint.shader = g
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 行星本体
        paint.shader = body
        paint.alpha = 240
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.alpha = 255

        // 球面上的一道高光
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.16f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0x5CFFD1A6
        canvas.drawArc(
            RectF(centerX - radius * 0.62f, centerY - radius * 0.78f, centerX + radius * 0.5f, centerY - radius * 0.1f),
            200f, 70f, false, paint,
        )
        paint.style = Paint.Style.FILL

        // 轨道环：压着球画，前后一对才像绕着走
        canvas.save()
        canvas.rotate(-18f, centerX, centerY)
        canvas.drawOval(ringRect, ringPaint)
        canvas.restore()

        // 几颗碎星，位置按卡片高度取比例
        val stars = floatArrayOf(0.18f, 0.12f, 1.4f, 0.42f, 0.74f, 1.1f, 0.62f, 0.28f, 0.9f, 0.88f, 0.2f, 1.5f)
        var i = 0
        while (i + 2 < stars.size) {
            starPaint.alpha = (stars[i + 1] * 255).toInt().coerceIn(60, 255)
            canvas.drawCircle(width * stars[i], height * stars[i + 2], dp(1.4f), starPaint)
            i += 3
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
