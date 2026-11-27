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
    /** 此页不参加自动并排 */
    val wide: Boolean,
    val split: Boolean = false,
) {
    val width: Int get() = src.width()
    val height: Int get() = src.height()
}

/**
 * 阅读画面。一屏画一到两页，位置由 [PageFit] 算好，再叠一层用户缩放与平移。
 *
 * 用自绘而不是 ImageView：双页要两页并排，裁白边与拆页要按矩形取源，
 * ImageView 的 scaleType 表达不了这些；缩放也要按「只动矩阵、不重解位图」来做。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var pages: List<ReaderPage> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    /** 每页在屏幕上的位置（缩放前） */
    private var boxes: List<PageFit.Box> = emptyList()

    /** 所有页合起来的外框（缩放前），平移的边界按它算 */
    private val content = RectF()

    /** 左右各留的边距（px） */
    var marginPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                rebuildFit()
                clampPan()
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

    /**
     * 页面适应：true = 按宽度铺满（高图上下超出，靠拖动看）；
     * false = 整页可见（受屏高约束）。
     */
    var fitWidth: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                resetZoom()
                rebuildFit()
                invalidate()
            }
        }

    /** 用户缩放倍数，1 = 未放大 */
    var scale: Float = 1f
        private set

    val isZoomed: Boolean get() = scale > 1.001f

    private var panX = 0f
    private var panY = 0f

    /**
     * 竖向连贯：多页上下相接，靠上下滑浏览。
     * 页由外面按需追加/前插，滚动位置在插入时补偿，画面不会跳。
     */
    var vertical: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resetZoom()
                axis.reset()
                rebuildFit()
                invalidate()
            }
        }

    private val axis = ScrollAxis()
    private var verticalTops = FloatArray(0)
    private var verticalTotal = 0f
    private var verticalLeft = 0f
    private var verticalWidth = 0f

    val pageCount: Int get() = pages.size

    /**
     * 窗口里这些页一共占多少像素内存。
     *
     * 竖排不能只按页数封顶：条漫单页解码后远大于普通页，页数一多就顶穿堆。
     * 这里数的是位图本身的字节（ARGB_8888 四位一像素），不是绘制尺寸。
     */
    fun bitmapBytes(): Long {
        var sum = 0L
        // 拆开的上下两段共用一张位图，按身份去重，同一张图只算一次
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        for (page in pages) {
            if (!page.bitmap.isRecycled && seen.add(page.bitmap)) {
                sum += page.bitmap.byteCount.toLong()
            }
        }
        return sum
    }

    /** 窗口里最大的那一页占多少字节：它决定最少能留几页 */
    fun maxPageBytes(): Long {
        var max = 0L
        for (page in pages) {
            if (!page.bitmap.isRecycled) max = maxOf(max, page.bitmap.byteCount.toLong())
        }
        return max
    }

    /** 竖向时当前落在窗口里的哪一页 */
    val currentIndex: Int
        get() {
            if (!vertical || verticalTops.isEmpty()) return 0
            return slotAt(axis.position + height * 0.4f)
        }

    /** 屏幕顶所在的槽位：短页跨屏时它可能比锚点页落后好几页 */
    val topIndex: Int
        get() {
            if (!vertical || verticalTops.isEmpty()) return 0
            return slotAt(axis.position)
        }

    /** 屏幕底所在的槽位：重建窗口要接得住恢复前的整个视口 */
    val bottomIndex: Int
        get() {
            if (!vertical || verticalTops.isEmpty()) return 0
            return slotAt(axis.position + height)
        }

    private fun slotAt(y: Float): Int {
        var index = 0
        for (i in verticalTops.indices) {
            if (verticalTops[i] <= y) index = i else break
        }
        return index
    }

    val atTop: Boolean get() = vertical && axis.atTop

    val atBottom: Boolean get() = vertical && axis.atBottom

    /** 竖向滚动。返回 false 表示真到了该方向的边界；亚像素小位移先攒着，不算到头 */
    fun scrollBy(dy: Float): Boolean {
        if (!vertical) return false
        val before = axis.position
        val ok = axis.scrollBy(dy)
        if (axis.position != before) invalidate()
        return ok
    }

    /** 竖向滚到某一页的顶部 */
    fun scrollToIndex(index: Int) {
        if (!vertical || verticalTops.isEmpty()) {
            axis.reset()
            return
        }
        val i = index.coerceIn(0, verticalTops.size - 1)
        axis.scrollTo(verticalTops[i])
        invalidate()
    }

    /** 当前滚动位置（px）：按键推进要算实际挪了多少，余量接着滚 */
    val scrollPosition: Float get() = axis.position

    /** 当前位置距某槽位顶部的有符号偏移：重建窗口后落回页内原处用（跨两页时为负） */
    fun offsetFromSlot(index: Int): Float {
        if (!vertical || index !in verticalTops.indices) return 0f
        return axis.offsetFrom(verticalTops[index])
    }

    /** 滚到某槽位顶部再往下 [offset]：后台返回重建窗口后恢复页内位置 */
    fun scrollToSlotOffset(index: Int, offset: Float) {
        if (!vertical || verticalTops.isEmpty()) {
            axis.reset()
            return
        }
        val i = index.coerceIn(0, verticalTops.size - 1)
        axis.scrollTo(verticalTops[i] + offset)
        invalidate()
    }

    /** 末尾接一页：滚动位置不动，正是无缝的那一半 */
    fun appendPage(page: ReaderPage) {
        if (!vertical) return
        pages = pages + page
        relayoutVertical()
        invalidate()
    }

    /** 头部插一页：把插入的高度补进滚动位置，画面才不跳 */
    fun prependPage(page: ReaderPage) {
        if (!vertical) return
        pages = listOf(page) + pages
        relayoutVertical()
        axis.shift(heightOf(page))
        invalidate()
    }

    /** 丢掉窗口最前面几页，滚动位置跟着补偿——竖向滚久了窗口不能无限长 */
    fun dropFirst(count: Int) {
        if (!vertical || count <= 0) return
        val n = count.coerceAtMost(pages.size - 1)
        if (n <= 0) return
        val removed = verticalTops[n]
        pages = pages.drop(n)
        // 先补偿再按新边界钳位：反过来会先被旧位置钳到新边界、再减一次，画面回跳
        axis.shift(-removed)
        relayoutVertical()
        invalidate()
    }

    /** 丢掉窗口最后面几页 */
    fun dropLast(count: Int) {
        if (!vertical || count <= 0) return
        val n = count.coerceAtMost(pages.size - 1)
        if (n <= 0) return
        pages = pages.dropLast(n)
        relayoutVertical()
        invalidate()
    }

    /** 内容本身比屏幕大（按宽度铺满时的长图），没缩放也能拖 */
    private val contentOverflows: Boolean
        get() = scale > 1.001f || content.height() * scale > height + 0.5f ||
            content.width() * scale > width + 0.5f

    private val gapPx: Int get() = (marginPx * 0.5f).toInt().coerceAtLeast(0)

    fun setPages(list: List<ReaderPage>) {
        pages = list
        // 换页等同于换内容：上一页的缩放和平移不能带过来
        scale = 1f
        panX = 0f
        panY = 0f
        axis.reset()
        rebuildFit()
        invalidate()
    }

    fun resetZoom() {
        if (scale == 1f && panX == 0f && panY == 0f) return
        scale = 1f
        panX = 0f
        panY = 0f
        applyPan(0f, 0f)
    }

    /**
     * 双击缩放：未放大时放大到 [factor]，已放大则还原。
     * 放大后锚点按 [focusH] / [focusV] 落位，取值同规格里的五档枚举。
     */
    fun doubleTapZoom(factor: Float, tapX: Float, tapY: Float, focusH: Int, focusV: Int) {
        if (pages.isEmpty()) return
        if (isZoomed) {
            resetZoom()
            return
        }
        val k = factor.coerceIn(ZoomMath.MIN_SCALE, ZoomMath.MAX_SCALE)
        val cx = width / 2f
        val cy = height / 2f
        val anchorX = if (focusH == FOCUS_PAGE_CENTER) content.centerX() else tapX
        val anchorY = if (focusV == FOCUS_PAGE_CENTER) content.centerY() else tapY
        val targetX = when (focusH) {
            FOCUS_TAP -> tapX
            FOCUS_TAP_TO_CENTER, FOCUS_PAGE_CENTER -> cx
            FOCUS_TOP_LEFT -> 0f
            else -> width.toFloat()
        }
        val targetY = when (focusV) {
            FOCUS_TAP -> tapY
            FOCUS_TAP_TO_CENTER, FOCUS_PAGE_CENTER -> cy
            else -> 0f
        }
        val pan = ZoomMath.panTo(anchorX, anchorY, targetX, targetY, cx, cy, k)
        scale = k
        applyPan(pan.x, pan.y)
    }

    /** 长按放大：以按下的点为准，已经放大时还原 */
    fun longPressZoom(factor: Float, tapX: Float, tapY: Float, focusH: Int, focusV: Int) {
        if (pages.isEmpty()) return
        if (isZoomed) {
            resetZoom()
            return
        }
        doubleTapZoom(factor, tapX, tapY, focusH, focusV)
    }

    /**
     * 双指缩放：以两指中点为枢轴，手指下的内容不动。
     * [factor] 是本次的增量倍率（1 = 不变）。
     */
    fun pinchScale(factor: Float, focusX: Float, focusY: Float) {
        if (pages.isEmpty() || factor <= 0f) return
        val k = scale
        val next = (k * factor).coerceIn(ZoomMath.MIN_SCALE, ZoomMath.MAX_SCALE)
        if (next == k) return
        val cx = width / 2f
        val cy = height / 2f
        // 焦点对应的基础坐标：把屏幕坐标按当前变换退回去
        val ax = (focusX - cx - panX) / k + cx
        val ay = (focusY - cy - panY) / k + cy
        val pan = ZoomMath.panTo(ax, ay, focusX, focusY, cx, cy, next)
        scale = next
        applyPan(pan.x, pan.y)
    }

    /** 拖动。没放大、内容也没超出屏幕时不动，把事件留给点击翻页 */
    fun panBy(dx: Float, dy: Float): Boolean {
        // 竖向模式由 scrollBy 负责，拖动不再平移整屏
        if (vertical) return false
        if (!contentOverflows) return false
        applyPan(panX + dx, panY + dy)
        return true
    }

    /**
     * 页内滚动：页面比屏幕高时，先滚一段再翻页。
     * [dy] 为正表示往下看（内容上移）。滚到头就返回 false，交给翻页。
     */
    fun scrollWithin(dy: Float): Boolean {
        if (content.isEmpty || height == 0) return false
        val before = panY
        applyPan(panX, panY - dy)
        return kotlin.math.abs(panY - before) > 0.5f
    }

    /** 页内滚到顶 */
    fun scrollToTop() {
        if (panY != 0f) applyPan(panX, 0f)
    }

    private fun applyPan(x: Float, y: Float) {
        if (width <= 0 || height <= 0 || content.isEmpty) {
            panX = x
            panY = y
            invalidate()
            return
        }
        val clamped = ZoomMath.clampPan(
            panX = x,
            panY = y,
            contentLeft = content.left,
            contentTop = content.top,
            contentRight = content.right,
            contentBottom = content.bottom,
            viewW = width.toFloat(),
            viewH = height.toFloat(),
            centerX = width / 2f,
            centerY = height / 2f,
            scale = scale,
        )
        panX = if (clamped.x.isNaN()) 0f else clamped.x
        panY = if (clamped.y.isNaN()) 0f else clamped.y
        invalidate()
    }

    private fun clampPan() = applyPan(panX, panY)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildFit()
        clampPan()
    }

    private fun rebuildFit() {
        if (pages.isEmpty() || width == 0 || height == 0) {
            boxes = emptyList()
            content.setEmpty()
            verticalTops = FloatArray(0)
            verticalTotal = 0f
            axis.viewport = height.toFloat()
            axis.total = 0f
            axis.clamp()
            return
        }
        if (vertical) {
            relayoutVertical()
            return
        }
        boxes = PageFit.fit(
            containerW = width,
            containerH = height,
            margin = marginPx,
            gap = gapPx,
            sizes = pages.map { it.width to it.height },
            fitWidth = fitWidth,
        )
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (box in boxes) {
            if (box.width <= 0f || box.height <= 0f) continue
            left = minOf(left, box.left)
            top = minOf(top, box.top)
            right = maxOf(right, box.right)
            bottom = maxOf(bottom, box.bottom)
        }
        if (right <= left || bottom <= top) {
            content.setEmpty()
        } else {
            content.set(left, top, right, bottom)
        }
    }

    /** 竖向重排：每页按宽度铺满，上下相接，累计出总高 */
    private fun relayoutVertical() {
        if (pages.isEmpty() || width == 0 || height == 0) {
            verticalTops = FloatArray(0)
            verticalTotal = 0f
            axis.viewport = height.toFloat()
            axis.total = 0f
            axis.clamp()
            return
        }
        val margin = marginPx.coerceIn(0, width / 2)
        verticalLeft = margin.toFloat()
        verticalWidth = (width - 2 * margin).coerceAtLeast(1).toFloat()

        val tops = FloatArray(pages.size)
        var y = 0f
        for (i in pages.indices) {
            tops[i] = y
            y += heightOf(pages[i])
        }
        verticalTops = tops
        verticalTotal = y
        axis.viewport = height.toFloat()
        axis.total = y
        axis.clamp()
    }

    private fun heightOf(page: ReaderPage): Float =
        if (page.width <= 0) verticalWidth else verticalWidth * page.height / page.width.toFloat()

    private fun drawVertical(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, -axis.position)
        for (i in pages.indices) {
            val page = pages[i]
            if (page.bitmap.isRecycled) continue
            val top = verticalTops[i]
            val height = (if (i + 1 < pages.size) verticalTops[i + 1] else verticalTotal) - top
            if (height <= 0f) continue
            // 只画落在视口附近的页，绘制量与书的总页数无关
            val visibleTop = top - axis.position
            if (visibleTop > this.height || visibleTop + height < 0f) continue
            dst.set(verticalLeft, top, verticalLeft + verticalWidth, top + height)
            canvas.drawBitmap(page.bitmap, page.src, dst, paint)
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        if (pages.isEmpty()) return
        if (vertical) {
            drawVertical(canvas)
            return
        }
        if (boxes.isEmpty()) return

        canvas.save()
        if (scale != 1f || panX != 0f || panY != 0f) {
            canvas.translate(panX, panY)
            canvas.scale(scale, scale, width / 2f, height / 2f)
        }
        pages.forEachIndexed { index, page ->
            if (page.bitmap.isRecycled) return@forEachIndexed
            val box = boxes.getOrNull(index) ?: return@forEachIndexed
            if (box.width <= 0f || box.height <= 0f) return@forEachIndexed
            dst.set(box.left, box.top, box.right, box.bottom)
            canvas.drawBitmap(page.bitmap, page.src, dst, paint)
        }
        canvas.restore()
    }

    companion object {
        /** 双击 / 长按后对准的五档：点击点、点击点移到屏幕中心、页面中心、左上、右上 */
        const val FOCUS_TAP = 0
        const val FOCUS_TAP_TO_CENTER = 1
        const val FOCUS_PAGE_CENTER = 2
        const val FOCUS_TOP_LEFT = 3
        const val FOCUS_TOP_RIGHT = 4
    }
}
