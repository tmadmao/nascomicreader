package com.nascomic.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 一页要画的东西：位图 + 取哪一块（裁白边、跨页拆分都只改这块矩形，不复制位图）。
 */
data class ReaderPage(
    val bitmap: Bitmap,
    val src: Rect,
    /** 宽图（跨页大图）。宽图不并排，可拆两半看 */
    val wide: Boolean,
) {
    val width: Int get() = src.width()
    val height: Int get() = src.height()
}

/**
 * 阅读画面。一屏画一到两页，位置由 [PageFit] 算好。
 *
 * 用自绘而不是 ImageView：双页要两页并排，裁白边与拆页要按矩形取源，
 * ImageView 的 scaleType 表达不了这些。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var pages: List<ReaderPage> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

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

    fun setPages(list: List<ReaderPage>) {
        pages = list
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
        pages.forEachIndexed { index, page ->
            if (page.bitmap.isRecycled) return@forEachIndexed
            val box = boxes.getOrNull(index) ?: return@forEachIndexed
            if (box.width <= 0f || box.height <= 0f) return@forEachIndexed
            dst.set(box.left, box.top, box.right, box.bottom)
            canvas.drawBitmap(page.bitmap, page.src, dst, paint)
        }
    }
}
