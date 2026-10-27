package com.nascomic.reader.library.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.RenderedPageSource
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.ui.reader.PageBitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 封面缩略图。
 *
 * 每本书取封面页（默认第 1 页）生成一张小图存在缓存里，书架直接读，不用每次解原图。
 * 缓存被系统清掉了会重新生成。
 */
class ThumbnailStore(private val context: Context) {

    private val dir: File = File(context.cacheDir, "reader/thumbs").apply { mkdirs() }

    fun fileOf(bookId: Long): File = File(dir, "b$bookId.jpg")

    fun has(bookId: Long): Boolean {
        val f = fileOf(bookId)
        return f.isFile && f.length() > 0
    }

    /** 生成一本书的封面；打不开或没有页时返回 null，不回退到占位图 */
    suspend fun generate(
        book: BookEntity,
        source: com.nascomic.reader.library.data.SourceEntity,
        chapters: List<ChapterEntity>,
        opener: BookOpener,
        targetWidth: Int = TARGET_WIDTH,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val bookSource = opener.open(book, source, chapters)
            bookSource.use { src ->
                if (src.pageCount == 0) return@use null
                val index = book.coverPage.coerceIn(0, src.pageCount - 1)
                // PDF 这类来源没有字节流，直接按尺寸渲染
                val decoded = if (src is RenderedPageSource) {
                    src.renderPage(index, targetWidth, targetWidth * 3 / 2)
                } else {
                    src.openPage(index).use {
                        PageBitmapLoader.load(it, targetWidth, targetWidth * 3 / 2)
                    }
                } ?: return@use null

                val scaled = scaleToWidth(decoded, targetWidth)
                val out = fileOf(book.id)
                FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (scaled !== decoded) decoded.recycle()
                scaled.recycle()
                out
            }
        }.getOrNull()
    }

    /** 读缓存好的封面，按所需尺寸降采样 */
    fun load(bookId: Long, reqWidth: Int, reqHeight: Int): Bitmap? {
        val f = fileOf(bookId)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    fun invalidate(bookId: Long) {
        runCatching { fileOf(bookId).delete() }
    }

    /** 清掉不属于现存书的残留（书被删了、缓存还在） */
    fun prune(aliveIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix("b").removeSuffix(".jpg").toLongOrNull()
            if (id != null && id !in aliveIds) runCatching { f.delete() }
        }
    }

    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val h = (src.height.toFloat() * targetWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, h, true)
    }

    private fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return 1
        var sample = 1
        while (w / (sample * 2) >= maxW && h / (sample * 2) >= maxH) {
            sample *= 2
            if (sample >= 16) break
        }
        return sample
    }

    private companion object {
        /** 三列网格下每格约 110dp，@3x 屏幕约 330px，留一点余量 */
        const val TARGET_WIDTH = 360
    }
}
