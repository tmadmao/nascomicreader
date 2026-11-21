package com.nascomic.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/** 把一页图片解码成屏幕够用的位图。先量尺寸再按需降采样，避免大图撑爆内存。 */
object PageBitmapLoader {

    fun load(
        stream: InputStream,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? = load(stream.use { it.readBytes() }, maxWidth, maxHeight, config)

    /**
     * 已经拿到字节时直接解。
     *
     * 两段式加载要拿**同一份字节**解两次（先低清、后高清），所以字节单独走这条路；
     * 否则远端一次翻页要读两遍图，先出的那一眼反而更慢。
     */
    fun load(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        val bounds = measure(bytes) ?: return null

        val sample = sampleSize(bounds.first, bounds.second, maxWidth, maxHeight)
        val sampledW = (bounds.first / sample).coerceAtLeast(1)
        val sampledH = (bounds.second / sample).coerceAtLeast(1)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
            // 采样只能按 2 的幂降，常常还比目标大不少（目标 1080、原图 2000 时只能降到 2000）。
            // 交给工厂直接按比例缩到目标宽，位图一出生就是最终尺寸，缓存才装得下整个预载窗口。
            if (maxWidth > 0 && maxHeight > 0 && (sampledW > maxWidth || sampledH > maxHeight)) {
                val scale = minOf(
                    maxWidth.toFloat() / sampledW,
                    maxHeight.toFloat() / sampledH,
                    1f,
                )
                inScaled = true
                inDensity = sampledW
                inTargetDensity = (sampledW * scale).toInt().coerceAtLeast(1)
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 只量尺寸、不出位图 */
    fun measure(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    /**
     * 这一页值不值得先出低清：**实际要解的像素**到六百万以上才给。
     *
     * 按「降采样之后」算，不按原图算——九千万像素的扫描件降到目标尺寸可能只解两百万，
     * 那种情况本来就不慢，先出低清等于白多解一次。实测六百万像素这一档解码要 150 ms 上下，
     * 低清（面积四分之一）大约 40 ms，值得先给一眼。
     */
    fun needsPreview(srcWidth: Int, srcHeight: Int, targetW: Int, targetH: Int): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0 || targetW <= 0 || targetH <= 0) return false
        val sample = sampleSize(srcWidth, srcHeight, targetW, targetH)
        val decoded = (srcWidth / sample).toLong() * (srcHeight / sample)
        return decoded >= PREVIEW_MIN_DECODED_PIXELS
    }

    /** 解码像素到这个数才值得先给一张低清 */
    const val PREVIEW_MIN_DECODED_PIXELS = 6_000_000L

    /** 取 2 的幂，保证解码后的图在两个方向都还不小于目标尺寸 */
    private fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        if (maxW <= 0 || maxH <= 0) return 1
        var sample = 1
        while (w / (sample * 2) >= maxW && h / (sample * 2) >= maxH) {
            sample *= 2
            if (sample >= 16) break
        }
        return sample
    }
}
