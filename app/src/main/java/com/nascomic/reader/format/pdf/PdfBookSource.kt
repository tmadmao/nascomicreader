package com.nascomic.reader.format.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.ChapterInfo
import com.nascomic.reader.format.RenderedPageSource
import java.io.InputStream

/**
 * PDF 书源。用系统自带的 [PdfRenderer]，不引第三方库。
 *
 * PDF 没有「图片字节」，页面是按尺寸现画的，所以同时实现 [RenderedPageSource]；
 * 上层看到这个接口就直接取位图，不去碰 [openPage]。
 *
 * [PdfRenderer] 不是线程安全的，而阅读页会从当前页与两个预载 worker 同时进来，
 * 所以渲染与关闭都串行化——并发它的后果是随机抛异常甚至 native 崩溃。
 */
class PdfBookSource(private val pfd: ParcelFileDescriptor) : BookSource, RenderedPageSource {

    private val renderer = PdfRenderer(pfd)

    private val lock = Any()
    private var closed = false

    override val pageCount: Int get() = synchronized(lock) {
        if (closed) 0 else renderer.pageCount
    }

    override val chapters: List<ChapterInfo>
        get() = listOf(ChapterInfo(ordinal = 0, title = "", pageCount = pageCount))

    override fun pageName(page: Int): String = "第 ${page + 1} 页"

    override fun openPage(page: Int): InputStream =
        throw UnsupportedOperationException("PDF 页面要按尺寸渲染，不走字节流")

    override fun chapterOf(page: Int): Int = 0

    override fun firstPageOfChapter(chapter: Int): Int = 0

    override fun renderPage(page: Int, width: Int, height: Int): Bitmap? = synchronized(lock) {
        if (closed || width <= 0 || height <= 0) return null
        runCatching {
            if (page < 0 || page >= renderer.pageCount) return null
            renderer.openPage(page).use { pdfPage ->
                val pw = pdfPage.width
                val ph = pdfPage.height
                if (pw <= 0 || ph <= 0) return null

                val scale = minOf(width.toFloat() / pw, height.toFloat() / ph)
                val outW = (pw * scale).toInt().coerceAtLeast(1)
                val outH = (ph * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                // PDF 的背景是透明的，不铺白底会得到一张只有字和图的透明图
                bitmap.eraseColor(Color.WHITE)
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}
