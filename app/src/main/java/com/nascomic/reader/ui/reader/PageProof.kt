package com.nascomic.reader.ui.reader

import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.RenderedPageSource
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 对原始内容取证，阅读缩放、裁边和磁盘缩略页都不参与。只对用户修正过的页做。 */
internal object PageProof {
    fun read(source: BookSource, page: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val locator = source.pageLocator(page).toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(locator.size).array())
        digest.update(locator)
        val rendered = (source as? RenderedPageSource)?.renderProofPage(page)
        if (rendered != null) {
            try {
                digest.update(1.toByte())
                digest.update(ByteBuffer.allocate(8).putInt(rendered.width).putInt(rendered.height).array())
                val row = IntArray(rendered.width)
                val bytes = ByteBuffer.allocate(row.size * 4)
                for (y in 0 until rendered.height) {
                    rendered.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    bytes.asIntBuffer().put(row)
                    digest.update(bytes.array())
                }
            } finally {
                rendered.recycle()
            }
        } else {
            digest.update(0.toByte())
            source.openPage(page).use { stream ->
                val bytes = ByteArray(32 * 1024)
                var any = false
                while (true) {
                    val n = stream.read(bytes)
                    if (n < 0) break
                    if (n > 0) {
                        any = true
                        digest.update(bytes, 0, n)
                    }
                }
                check(any) { "Empty page" }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
