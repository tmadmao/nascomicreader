package com.nascomic.reader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Spinner
import android.widget.TextView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.R
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.settings.ReaderPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ReadingPageEditsTest {
    @Test
    fun saveReopenClearAndIgnoreChangedPageAfterRescan() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val device = InstrumentationRegistry.getInstrumentation()
        val context = device.targetContext
        check(context.packageName.endsWith(".debug"))
        fun foregroundDebug() {
            if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return
            val command = "am start -W -n ${context.packageName}/com.nascomic.reader.ui.MainActivity"
            val result = ParcelFileDescriptor.AutoCloseInputStream(device.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            assertTrue(result, result.contains("Status: ok"))
        }
        val archive = AtomicReference(sampleArchive(replacement = false))
        val db = ReaderDatabase.get(context)
        for (source in db.sourceDao().all()) {
            val endpoint = source.endpoint ?: continue
            if (source.label != FIXTURE || source.addedAt != 1L || !endpoint.startsWith("http://127.0.0.1:")) continue
            val leftover = db.bookDao().bySourceNow(source.id).singleOrNull()
            if (leftover?.docRef != endpoint + "page-fix.cbz") continue
            db.sourceDao().delete(source.id)
            ReaderPrefs(context, leftover.id).clearBookOverrides()
            DiskPageCache(context).clearBook(leftover.id)
        }
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = thread(isDaemon = true) { serve(server, archive) }
            val base = "http://127.0.0.1:${server.localPort}/"
            val url = base + "page-fix.cbz"
            val sourceId = db.sourceDao().insert(SourceEntity(kind = SourceEntity.KIND_WEBDAV,
                label = FIXTURE, endpoint = base, addedAt = 1))
            var book = 0L
            try {
                suspend fun scan() = LibraryScanner(context, db).scanRemoteForTest(db.sourceDao().get(sourceId)!!, base) {
                    listOf(LibraryScanner.RemoteEntry(url, "page-fix.cbz", false, archive.get().size.toLong()))
                }
                assertEquals(1, scan().added)
                book = db.bookDao().findByRef(sourceId, url)!!.id
                ReaderPrefs(context, book).apply {
                    scrollMode = 1
                    dualPage = false
                    zoneHint = false
                    detectSideways = true
                    showPageLabel = true
                    prefetchPrev = 0
                    prefetchNext = 0
                }
                fun open(): ActivityScenario<ReadingActivity> {
                    foregroundDebug()
                    return ActivityScenario.launch(Intent(context, ReadingActivity::class.java)
                        .putExtra(ReadingActivity.EXTRA_BOOK_ID, book)
                        .putExtra(ReadingActivity.EXTRA_START_PAGE, 0))
                }
                open().use { session ->
                    waitFor(session) { it.findViewById<View>(R.id.sideways_hint).isShown }
                    session.onActivity { it.findViewById<View>(R.id.sideways_rotate).performClick() }
                    waitFor(session) { it.findViewById<TextView>(R.id.sideways_skip).text.toString() ==
                        context.getString(R.string.reader_sideways_accept) }
                    session.onActivity { it.findViewById<View>(R.id.sideways_skip).performClick() }
                    waitFor(session) { it.findViewById<View>(R.id.edit_page).isEnabled }
                    assertEquals(90, db.pageEditDao().forBook(book).single().rotation)
                    edit(session) { fields ->
                        assertEquals(1, fields[1].selectedItemPosition)
                        fields[0].setSelection(PageEdits.NORMAL)
                        fields[2].setSelection(2) // 首页：自动、单独、后一页
                        button(android.R.id.button1).performClick()
                    }
                    waitFor(session) { label(it) == context.getString(R.string.reader_page_range, 1, 2, 3) }
                    session.onActivity { it.findViewById<View>(R.id.next_page).performClick() }
                    waitFor(session) { label(it) == context.getString(R.string.reader_page_format, 3, 3) }
                    session.onActivity { it.findViewById<View>(R.id.prev_page).performClick() }
                    waitFor(session) { label(it) == context.getString(R.string.reader_page_range, 1, 2, 3) }
                }
                open().use { session ->
                    waitFor(session) { label(it) == context.getString(R.string.reader_page_range, 1, 2, 3) }
                    edit(session, pickFirst = true) { fields ->
                        assertEquals(listOf(PageEdits.NORMAL, 1, 2), fields.map { it.selectedItemPosition })
                        button(android.R.id.button3).performClick()
                    }
                    waitFor(session) { it.findViewById<View>(R.id.sideways_hint).isShown }
                    assertTrue(db.pageEditDao().forBook(book).isEmpty())
                    edit(session) { fields ->
                        fields[1].setSelection(1)
                        button(android.R.id.button1).performClick()
                    }
                    waitFor(session) { it.findViewById<View>(R.id.edit_page).isEnabled }
                    assertEquals(90, db.pageEditDao().forBook(book).single().rotation)
                }
                val replacement = sampleArchive(replacement = true)
                assertEquals(archive.get().size, replacement.size)
                archive.set(replacement)
                scan()
                assertEquals(book, db.bookDao().findByRef(sourceId, url)!!.id)
                open().use { session ->
                    waitFor(session) { label(it) == context.getString(R.string.reader_page_format, 1, 3) }
                    edit(session) { fields ->
                        assertEquals(listOf(0, 0, 0), fields.map { it.selectedItemPosition })
                        button(android.R.id.button2).performClick()
                    }
                }
            } finally {
                db.sourceDao().delete(sourceId)
                if (book != 0L) {
                    ReaderPrefs(context, book).clearBookOverrides()
                    DiskPageCache(context).clearBook(book)
                }
                server.close()
                peer.join(2000)
            }
        }
    }

    private fun edit(session: ActivityScenario<ReadingActivity>, pickFirst: Boolean = false, action: (List<Spinner>) -> Unit) {
        waitFor(session) { !it.findViewById<View>(R.id.loading).isShown && it.findViewById<View>(R.id.edit_page).isEnabled }
        session.onActivity { it.findViewById<View>(R.id.edit_page).performClick() }
        if (pickFirst) {
            waitFor(session) { windows().flatMap(::descendants).any { it is ListView } }
            session.onActivity {
                val list = windows().flatMap(::descendants).filterIsInstance<ListView>().first()
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
            }
        }
        waitFor(session) { windows().flatMap(::spinners).size == 3 }
        session.onActivity { action(windows().flatMap(::spinners)) }
    }

    private fun windows(): List<View> = WindowInspector.getGlobalWindowViews().asReversed()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun spinners(view: View): List<Spinner> = descendants(view).filterIsInstance<Spinner>()
    private fun button(id: Int): View = windows().firstNotNullOf { it.findViewById<View>(id) }
    private fun label(reader: ReadingActivity) = reader.findViewById<TextView>(R.id.page_label).text.toString()

    private fun waitFor(session: ActivityScenario<ReadingActivity>, condition: (ReadingActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        var state = ""
        do {
            var done = false
            session.onActivity {
                done = condition(it)
                state = "page=" + label(it) + ", loading=" + it.findViewById<View>(R.id.loading).isShown +
                    ", error=" + it.findViewById<View>(R.id.error).isShown
            }
            if (done) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Reader did not reach the expected state: " + state)
    }

    private fun sampleArchive(replacement: Boolean): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            for (page in 0..2) {
                val sideways = page == 0 && !replacement
                val bitmap = Bitmap.createBitmap(if (sideways) 320 else 240, if (sideways) 232 else 320, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(if (sideways) Color.WHITE else Color.rgb(210, 190 + page * 12, 220))
                    if (sideways) {
                        val paint = Paint().apply { color = Color.BLACK }
                        for (x in 28..289 step 17) {
                            if (x !in 145..170) canvas.drawRect(x.toFloat(), 15f, x + 6f, 216f, paint)
                        }
                    }
                    zip.putNextEntry(ZipEntry("0${page + 1}.png"))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally { bitmap.recycle() }
            }
        }
        // 只填 ZIP 注释，让重扫前后路径、页数、大小都不变。
        val zip = out.toByteArray()
        val padded = zip.copyOf(8192)
        val commentSize = padded.size - zip.size
        check(commentSize > 0)
        padded[zip.size - 2] = commentSize.toByte()
        padded[zip.size - 1] = (commentSize ushr 8).toByte()
        padded
    }

    private fun serve(server: ServerSocket, archive: AtomicReference<ByteArray>) {
        while (!server.isClosed) runCatching {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                val bytes = archive.get()
                val range = headers.firstNotNullOfOrNull { Regex("Range: bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).matchEntire(it) }
                val start = range?.groupValues?.get(1)?.toInt() ?: 0
                val end = range?.groupValues?.get(2)?.toInt()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                val length = end - start + 1
                val response = if (range == null) "200 OK" else "206 Partial Content"
                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${bytes.size}\r\n"
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $response\r\nContent-Length: $length\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
                    if (request.startsWith("GET ")) write(bytes, start, length)
                    flush()
                }
            }
        }
    }

    private companion object {
        const val FIXTURE = "Page edit regression fixture"
    }
}
