package com.nascomic.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 阅读画面。一屏画一到两页，位置由 [PageFit] 算好。
 *
 * 用自绘而不是 ImageView：双页要两页并排、之后还要接裁白边（按裁剪后的矩形取源），
 * ImageView 的 scaleType 表达不了这些。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var pages: List<Bitmap> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** 左右各留的边距（px） */
    var marginPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 画布底色 */
    var canvasColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val gapPx: Int get() = (marginPx * 0.5f).toInt().coerceAtLeast(0)

    fun setPages(bitmaps: List<Bitmap>) {
        pages = bitmaps
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        if (pages.isEmpty() || width == 0 || height == 0) return

        val boxes = PageFit.fit(
            containerW = width,
            containerH = height,
            margin = marginPx,
            gap = gapPx,
            sizes = pages.map { it.width to it.height },
        )
        pages.forEachIndexed { index, bitmap ->
            if (bitmap.isRecycled) return@forEachIndexed
            val box = boxes.getOrNull(index) ?: return@forEachIndexed
            if (box.width <= 0f || box.height <= 0f) return@forEachIndexed
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(box.left, box.top, box.right, box.bottom),
                paint,
            )
        }
    }
}
