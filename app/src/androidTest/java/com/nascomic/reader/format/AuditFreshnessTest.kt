package com.nascomic.reader.format

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 健康检查的「读当前源文件」：PDF / 7z 这类必须先落盘的格式，审计不能复用旧副本。
 *
 * 真机上曾经这样漏报：远端 PDF 从 1 页换成 2 页，检查仍数旧的 1 页。
 * 这里拿真 PDF 与真 7z 走 [BookOpener] 的落盘路径，确认 refresh 会重新取一份。
 */
@RunWith(AndroidJUnit4::class)
class AuditFreshnessTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `审计重下PDF而不是复用旧副本`() {
        val bookId = -20261123L
        val book = BookEntity(
            id = bookId,
            sourceId = 1,
            docRef = "remote.pdf",
            title = "audit.pdf",
            titleSort = "audit.pdf",
            format = BookEntity.FORMAT_PDF,
            // 0 让缓存名不带大小，两次调用必然指向同一个文件
            byteSize = 0,
            addedAt = 1000,
        )
        var writes = 0
        val opener = BookOpener(context)
        LocalCopies.evictBooks(context, listOf(bookId))
        try {
            opener.openLocalCopy(book, refresh = false) { file ->
                writes++
                writePdf(file, pages = 1)
            }.use { source ->
                assertEquals("第一份是 1 页", 1, source.pageCount)
            }

            // 源文件已经变成 2 页：不刷新的阅读路径继续用旧副本，这是缓存的本分
            opener.openLocalCopy(book, refresh = false) { file ->
                writes++
                writePdf(file, pages = 2)
            }.use { source ->
                assertEquals("阅读路径仍用旧副本", 1, source.pageCount)
                assertEquals("没有重新落盘", 1, writes)
            }

            opener.openLocalCopy(book, refresh = true) { file ->
                writes++
                writePdf(file, pages = 2)
            }.use { source ->
                assertEquals("审计必须读到现在的 2 页", 2, source.pageCount)
                assertEquals("审计重新落了一份", 2, writes)
            }
        } finally {
            LocalCopies.evictBooks(context, listOf(bookId))
        }
    }

    @Test
    fun `审计重下远端压缩包而不是复用旧副本`() {
        val bookId = -20261124L
        val fixture = android.util.Base64.decode(SEVEN_ZIP_2_PAGES, android.util.Base64.DEFAULT)
        var downloads = 0
        LocalCopies.evictBooks(context, listOf(bookId))
        try {
            BookOpener(context).openRemoteArchiveCopy(bookId, "audit.7z") { file ->
                downloads++
                file.writeBytes(fixture)
            }.use { source ->
                assertEquals(2, source.pageCount)
            }

            BookOpener(context).openRemoteArchiveCopy(bookId, "audit.7z") { file ->
                downloads++
                file.writeBytes(fixture)
            }.use { source ->
                assertEquals("阅读路径复用旧副本", 2, source.pageCount)
                assertEquals("没有重新下载", 1, downloads)
            }

            BookOpener(context).openRemoteArchiveCopy(bookId, "audit.7z", refresh = true) { file ->
                downloads++
                file.writeBytes(fixture)
            }.use { source ->
                assertEquals(2, source.pageCount)
                assertEquals("审计重新下载了一次", 2, downloads)
            }
        } finally {
            LocalCopies.evictBooks(context, listOf(bookId))
        }
    }

    private fun writePdf(file: File, pages: Int) {
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val info = PdfDocument.PageInfo.Builder(200, 300, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                document.finishPage(page)
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private companion object {
        /** 2 页的 7z：与 RemoteArchiveOpenTest 用的是同一份夹具 */
        const val SEVEN_ZIP_2_PAGES =
            "N3q8ryccAAORkQXFBAAAAAAAAACMAAAAAAAAACmw/xFjZAIAAQQGAAEJBAAHCwEAAQMEAQgMAgAIDQIJAQoBG98FpaGODDwAAAUCERkAMQAuAGoAcABnAAAAMgAuAGoAcABnAAAAFBIBAONhi9Eged0B8oiL0SB53QESEgEA42GL0SB53QHjYYvRIHndARMSAQDjYYvRIHndAfKIi9Eged0BFQoBACCAtoEggLaBAAA=" 
    }
}
