package com.nascomic.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/** 把一页图片解码成屏幕够用的位图。先量尺寸再按需降采样，避免大图撑爆内存。 */
object PageBitmapLoader {

    fun load(stream: InputStream, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bytes = stream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

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
