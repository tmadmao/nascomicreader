package com.nascomic.reader.ui.reader

import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.nascomic.reader.R
import com.nascomic.reader.format.LocalCopies
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ReadingBookmarksTest {
    @Test
    fun chapterAndBookmarkLeaveBookEndInHorizontalMode() = readerTest(vertical = false)

    @Test
    fun chapterAndBookmarkLeaveBookEndInVerticalMode() = readerTest(vertical = true)

    private fun readerTest(vertical: Boolean) = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val db = ReaderDatabase.get(context)
        // 测试进程若被系统中断，只清理本测试留下的隔离样本。
        for (oldSource in db.sourceDao().all()) {
            if (oldSource.label != "Reader test" || oldSource.addedAt != 1L ||
                oldSource.endpoint?.startsWith("http://127.0.0.1:") != true) continue
            val oldBook = db.bookDao().bySourceNow(oldSource.id).singleOrNull() ?: continue
            if (oldBook.title != "Reader test" || oldBook.addedAt != 1L) continue
            val chapters = db.chapterDao().forBook(oldBook.id).map { it.chapterRef.substringAfterLast('/') }
            if (chapters == listOf("first.pdf", "second.pdf")) {
                db.sourceDao().delete(oldSource.id)
                ReaderPrefs(context, oldBook.id).clearBookOverrides()
                LocalCopies.evictBooks(context, listOf(oldBook.id))
                DiskPageCache(context).clearBook(oldBook.id)
            }
        }
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            // MIUI 会拦截后台测试进程启动 Activity；先让调试包进前台。
            val command = "am start -W -n ${context.packageName}/com.nascomic.reader.ui.MainActivity"
            val result = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            assertTrue(result, result.contains("Status: ok"))
        }
        val firstChapterRequested = CountDownLatch(1)
        val finishFirstChapter = CountDownLatch(1)
        val bytes = samplePdf()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { conn ->
                            conn.soTimeout = 5000
                            val reader = conn.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            if (request.startsWith("GET /first.pdf ")) {
                                firstChapterRequested.countDown()
                                check(finishFirstChapter.await(15, TimeUnit.SECONDS))
                            }
                            val out = conn.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (request.startsWith("GET ")) out.write(bytes)
                            out.flush()
                        }
                    }
                }
            }
            val base = "http://127.0.0.1:${server.localPort}/"
            val source = db.sourceDao().insert(SourceEntity(kind = SourceEntity.KIND_WEBDAV,
                label = "Reader test", endpoint = base, addedAt = 1))
            var book = 0L
            try {
                book = db.bookDao().insert(BookEntity(sourceId = source, docRef = base + "book/",
                    title = "Reader test", titleSort = "reader test", format = BookEntity.FORMAT_FOLDER,
                    addedAt = 1, modifiedAt = 1, isSingleVolume = 0, pageTotal = 6, chapterTotal = 2))
                db.chapterDao().insertAll(listOf(
                    ChapterEntity(bookId = book, ordinal = 0, title = "Ch 1", chapterRef = base + "first.pdf",
                        chapterKind = ChapterEntity.KIND_PDF, pageTotal = 3),
                    ChapterEntity(bookId = book, ordinal = 1, title = "Ch 2", chapterRef = base + "second.pdf",
                        chapterKind = ChapterEntity.KIND_PDF, pageTotal = 3),
                ))
                ReaderPrefs(context, book).apply {
                    scrollMode = if (vertical) 0 else 1
                    dualPage = true
                    spreadOrder = SpreadPlan.ORDER_FIRST_SINGLE
                    skipBlankFrom = 0
                    showPageLabel = true
                    zoneHint = false
                    prefetchPrev = 0
                    prefetchNext = 0
                }
                val intent = Intent(context, ReadingActivity::class.java)
                    .putExtra(ReadingActivity.EXTRA_BOOK_ID, book)
                val scenario = ActivityScenario.launch<ReadingActivity>(intent)
                try {
                    assertTrue("首章下载应当被测试服务暂缓", firstChapterRequested.await(8, TimeUnit.SECONDS))
                    scenario.onActivity { it.findViewById<View>(R.id.toc).performClick() }
                    scenario.onActivity {
                        val tabs = sheetView<TabLayout>(R.id.toc_tabs)!!
                        assertEquals(0, tabs.selectedTabPosition)
                        assertTrue(sheetView<View>(R.id.toc_order)!!.isShown)
                        tabs.getTabAt(1)!!.view.performClick()
                        assertEquals(View.INVISIBLE, sheetView<View>(R.id.toc_order)!!.visibility)
                    }
                    waitFor(scenario, "书签空态") { rows() == listOf(context.getString(R.string.reader_bookmark_empty)) }
                    db.bookmarkDao().insert(BookmarkEntity(bookId = book, chapterOrdinal = 0,
                        pageInChapter = 3, createdAt = 1)) // 越界后恰好是第二章首页，不能跳过去。
                    db.bookmarkDao().insert(BookmarkEntity(bookId = book, chapterOrdinal = 1,
                        pageInChapter = 1, createdAt = 2))
                    val row = context.getString(R.string.reader_bookmark_row, "Ch 2", 2)
                    waitFor(scenario, "只显示有效书签") { rows() == listOf(row) }
                    scenario.onActivity { sheetView<LinearLayout>(R.id.toc_list)!!.getChildAt(0).performClick() }
                    waitFor(scenario, "点书签后关闭抽屉") { sheetView<TabLayout>(R.id.toc_tabs) == null }
                    finishFirstChapter.countDown()
                    val label = context.getString(R.string.reader_page_chapter_format, "Ch 2", 2, 3)
                    waitFor(scenario, "跳到第二章第 2 页，而非并排的第 1 页") {
                        it.findViewById<TextView>(R.id.page_label).text == label
                    }

                    suspend fun assertPage(global: Int, chapter: Int, inChapter: Int) {
                        val until = SystemClock.uptimeMillis() + 5000
                        while (true) {
                            val saved = db.progressDao().forBook(book)
                            if (saved?.pageGlobal == global || SystemClock.uptimeMillis() >= until) {
                                assertEquals(global, saved?.pageGlobal)
                                assertEquals(chapter, saved?.chapterOrdinal)
                                assertEquals(inChapter, saved?.pageInChapter)
                                return
                            }
                            SystemClock.sleep(32)
                        }
                    }
                    assertPage(4, 1, 1)

                    goToEnd(scenario)
                    openTocAtEnd(scenario)
                    val firstChapter = context.getString(R.string.reader_toc_row_pages, 1, "Ch 1", 3)
                    scenario.onActivity {
                        val index = rows().indexOf(firstChapter)
                        assertTrue("目录应包含第一章", index >= 0)
                        sheetView<LinearLayout>(R.id.toc_list)!!.getChildAt(index).performClick()
                    }
                    waitFor(scenario, "点目录后退出书尾并显示第一章首页") { activity ->
                        !activity.findViewById<View>(R.id.end_page).isShown &&
                            activity.findViewById<TextView>(R.id.page_label).text ==
                            context.getString(R.string.reader_page_chapter_format, "Ch 1", 1, 3)
                    }
                    assertPage(0, 0, 0)

                    goToEnd(scenario)
                    openTocAtEnd(scenario)
                    scenario.onActivity { sheetView<TabLayout>(R.id.toc_tabs)!!.getTabAt(1)!!.view.performClick() }
                    waitFor(scenario, "书尾打开书签页签并看到已有书签") { rows() == listOf(row) }
                    scenario.onActivity { sheetView<LinearLayout>(R.id.toc_list)!!.getChildAt(0).performClick() }
                    waitFor(scenario, "选中书签后关闭抽屉并显示精确页") { activity ->
                        sheetView<TabLayout>(R.id.toc_tabs) == null &&
                            !activity.findViewById<View>(R.id.end_page).isShown &&
                            activity.findViewById<TextView>(R.id.page_label).text == label
                    }
                    assertPage(4, 1, 1)
                } finally {
                    finishFirstChapter.countDown()
                    scenario.close()
                }
                assertEquals(4, db.progressDao().forBook(book)?.pageGlobal)
            } finally {
                finishFirstChapter.countDown()
                server.close()
                peer.join(3000)
                db.sourceDao().delete(source)
                if (book != 0L) {
                    ReaderPrefs(context, book).clearBookOverrides()
                    LocalCopies.evictBooks(context, listOf(book))
                    DiskPageCache(context).clearBook(book)
                }
            }
        }
    }

    private fun goToEnd(scenario: ActivityScenario<ReadingActivity>) {
        val until = SystemClock.uptimeMillis() + 8000
        do {
            var end = false
            scenario.onActivity {
                end = it.findViewById<View>(R.id.end_page).isShown
                if (!end && !it.findViewById<View>(R.id.loading).isShown) {
                    it.findViewById<View>(R.id.next_page).performClick()
                }
            }
            if (end) return
            SystemClock.sleep(120)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("未到书尾")
    }

    private fun openTocAtEnd(scenario: ActivityScenario<ReadingActivity>) {
        scenario.onActivity { activity ->
            val at = SystemClock.uptimeMillis()
            val x = activity.window.decorView.width / 2f
            val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, 200f, 0)
            val up = MotionEvent.obtain(at, at + 30, MotionEvent.ACTION_UP, x, 200f, 0)
            try {
                activity.dispatchTouchEvent(down)
                activity.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }
        waitFor(scenario, "从书尾呼出工具栏") { it.findViewById<View>(R.id.toc).isShown }
        scenario.onActivity { it.findViewById<View>(R.id.toc).performClick() }
    }

    private fun samplePdf(): ByteArray {
        val doc = PdfDocument()
        try {
            listOf(Color.RED, Color.BLUE, Color.GREEN).forEachIndexed { index, color ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(100, 150, index + 1).create())
                page.canvas.drawColor(color)
                doc.finishPage(page)
            }
            return ByteArrayOutputStream().use { out ->
                doc.writeTo(out)
                out.toByteArray()
            }
        } finally {
            doc.close()
        }
    }

    private fun rows(): List<String> {
        val list = sheetView<LinearLayout>(R.id.toc_list) ?: return emptyList()
        return (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }
    }

    private fun <T : View> sheetView(id: Int): T? = WindowInspector.getGlobalWindowViews().asReversed()
        .firstNotNullOfOrNull { it.findViewById<T>(id)?.takeIf { view -> view.isAttachedToWindow } }

    private fun waitFor(scenario: ActivityScenario<ReadingActivity>, state: String, ready: (ReadingActivity) -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        do {
            var found = false
            scenario.onActivity { found = ready(it) }
            if (found) return
            SystemClock.sleep(32)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("未等到：$state")
    }
}
