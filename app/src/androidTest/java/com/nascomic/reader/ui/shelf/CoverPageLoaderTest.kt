package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.format.CancelSignal
import com.nascomic.reader.format.ChapterInfo
import com.nascomic.reader.format.FolderBookSource
import com.nascomic.reader.format.PageOpener
import com.nascomic.reader.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 挑封面的缩略图解码：失败不抛、解过就缓存、取消后不再交付。
 *
 * 取消这条是给「快速滑动 / 跳远页 / 直接关窗」兜底的——格子已经复用给别的页时，
 * 旧请求就算最后解出了图，也不能再往这个格子上贴。
 */
@RunWith(AndroidJUnit4::class)
class CoverPageLoaderTest {

    private val png: ByteArray by lazy {
        val bmp = Bitmap.createBitmap(8, 11, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.RED)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class FakeSource(
        override val pageCount: Int,
        private val bytes: ByteArray,
        private val failPages: Set<Int> = emptySet(),
        private val openDelayMs: Long = 0,
    ) : BookSource {

        val opens = AtomicInteger()

        override val chapters = listOf(ChapterInfo(0, "", pageCount))

        override fun pageName(page: Int): String = "第 ${page + 1} 页"

        override fun openPage(page: Int): InputStream {
            opens.incrementAndGet()
            if (openDelayMs > 0) Thread.sleep(openDelayMs)
            if (page in failPages) throw IOException("坏页")
            return ByteArrayInputStream(bytes)
        }

        override fun chapterOf(page: Int): Int = 0

        override fun firstPageOfChapter(chapter: Int): Int = 0

        override fun close() = Unit
    }

    @Test
    fun `坏页返回空_不抛异常`() = runBlocking {
        val loader = CoverPageLoader(FakeSource(2, png, failPages = setOf(1)), 80, 106)
        assertNotNull(loader.load(0))
        assertNull(loader.load(1))
    }

    @Test
    fun `解过的页进缓存_第二次不再开流`() = runBlocking {
        val source = FakeSource(1, png)
        val loader = CoverPageLoader(source, 80, 106)
        assertNotNull(loader.load(0))
        assertNotNull(loader.load(0))
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `取消后不再交付结果`() = runBlocking {
        val source = FakeSource(1, png, openDelayMs = 300)
        val loader = CoverPageLoader(source, 80, 106)
        var delivered: Bitmap? = null
        val job = launch(Dispatchers.Default) { delivered = loader.load(0) }
        delay(60)
        job.cancelAndJoin()
        assertNull(delivered)
    }

    @Test
    fun `失败页不影响别的页`() = runBlocking {
        val source = FakeSource(3, png, failPages = setOf(2))
        val loader = CoverPageLoader(source, 80, 106)
        assertNotNull(loader.load(0))
        assertNull(loader.load(2))
        assertNotNull(loader.load(1))
    }

    /**
     * 三个槽全被卡住的读占着时，取消必须马上让出槽位。
     *
     * 远端读是阻塞调用，协程取消不会让它立刻返回；只靠 isActive 检查的话，
     * 滑走三屏之后新的页就再也排不进来，网格一直空白。
     */
    @Test
    fun `取消后释放加载槽_后面的页还能进来`() = runBlocking {
        val started = CountDownLatch(3)
        val unblock = CountDownLatch(1)
        val next = CountDownLatch(1)
        val source = object : BookSource {
            override val pageCount = 4
            override val chapters = listOf(ChapterInfo(0, "", 4))
            override fun pageName(page: Int) = "第 ${page + 1} 页"
            override fun chapterOf(page: Int) = 0
            override fun firstPageOfChapter(chapter: Int) = 0
            override fun close() = Unit
            override fun openPage(page: Int): InputStream {
                if (page == 3) {
                    next.countDown()
                } else {
                    started.countDown()
                    unblock.await(5, TimeUnit.SECONDS)
                }
                throw IOException("模拟卡住的读")
            }
        }
        val loader = CoverPageLoader(source, 64, 88)
        val jobs = (0..2).map { launch(Dispatchers.Default) { loader.load(it) } }
        try {
            assertTrue(started.await(4, TimeUnit.SECONDS))
            jobs.forEach { it.cancel() }
            launch(Dispatchers.Default) { loader.load(3) }
            assertTrue("取消后槽位没有还回来", next.await(4, TimeUnit.SECONDS))
        } finally {
            unblock.countDown()
        }
    }

    /**
     * 取消发生在「请求已发出、响应头还没回来」那一段：这时还没有流可关，
     * 只能靠把取消挂到请求上。不挂的话，这条连接会一直挂着直到服务器回话或超时。
     */
    @Test
    fun `响应头没回来时取消_连接要断开`() = runBlocking {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            val requested = CountDownLatch(1)
            val observed = CountDownLatch(1)
            val stillConnected = AtomicBoolean(false)
            val url = "http://127.0.0.1:${server.localPort}/page.jpg"

            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { conn ->
                        conn.soTimeout = 4000
                        val reader = conn.getInputStream().bufferedReader()
                        reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requested.countDown()
                        // 故意不回响应头：客户端要么自己断开（读到 EOF/抛异常），要么就这么挂着
                        conn.soTimeout = 1500
                        try {
                            if (reader.read() != -1) stillConnected.set(true)
                        } catch (_: java.net.SocketTimeoutException) {
                            stillConnected.set(true)
                        }
                        observed.countDown()
                    }
                }
            }

            val client = WebDavClient(null, null, url)
            val opener = object : PageOpener {
                override fun open(ref: String) = client.openStream(ref)

                override fun open(ref: String, cancel: CancelSignal) = client.openStream(ref, cancel)
            }
            val source = FolderBookSource.from(listOf("page.jpg" to url), "样本", opener)
            val loader = CoverPageLoader(source, 64, 88)
            val job = launch(Dispatchers.Default) { loader.load(0) }
            try {
                assertTrue("请求没发出来", requested.await(4, TimeUnit.SECONDS))
                job.cancel()
                assertTrue("服务端没等到结果", observed.await(4, TimeUnit.SECONDS))
                assertTrue("取消后连接仍然连着", !stillConnected.get())
            } finally {
                job.cancel()
                server.close()
                worker.join(5000)
            }
        }
    }

    @Test
    fun bookOpenCancelsWebDavPropfindAndSmbNegotiation() = runBlocking {
        for (kind in listOf(SourceEntity.KIND_WEBDAV, SourceEntity.KIND_SMB)) {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                val sent = CountDownLatch(1)
                val eof = CountDownLatch(1)
                val peer = thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { conn ->
                            conn.soTimeout = 3_000
                            val input = conn.getInputStream()
                            if (input.read() < 0) return@use
                            sent.countDown()
                            while (input.read() >= 0) { }
                            eof.countDown()
                        }
                    }
                }
                val web = kind == SourceEntity.KIND_WEBDAV
                val endpoint = if (web) "http://127.0.0.1:${server.localPort}/dav/"
                    else "smb://127.0.0.1:${server.localPort}/share"
                val source = SourceEntity(id = 1, kind = kind, label = "loopback", endpoint = endpoint, addedAt = 1)
                val book = BookEntity(id = 1, sourceId = 1, docRef = if (web) endpoint else "Folder", title = "Test",
                    titleSort = "test", format = BookEntity.FORMAT_FOLDER, addedAt = 1, modifiedAt = 1)
                val opener = BookOpener(InstrumentationRegistry.getInstrumentation().targetContext)
                val token = CancelSignal()
                val job = launch(Dispatchers.IO) {
                    token.use { runCatching { opener.openForCoverPicker(book, source, emptyList(), token).close() } }
                }
                try {
                    assertTrue("打开书时没发出请求：$kind", sent.await(4, TimeUnit.SECONDS))
                    token.cancel()
                    assertTrue("关闭后连接仍在：$kind", eof.await(4, TimeUnit.SECONDS))
                } finally {
                    token.cancel()
                    job.cancel()
                    server.close()
                    peer.join(4000)
                }
            }
        }
    }

    @Test
    fun failedJpegAndFailedRenameKeepExistingCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "cover-write").toFile()
        try {
            val store = ThumbnailStore(context)
            val old = java.io.File(dir, "old.jpg").apply { writeBytes(png) }
            assertTrue(!store.publish(old) { it.write(1); false })
            assertTrue(old.readBytes().contentEquals(png))
            val blocked = java.io.File(dir, "destination.jpg").apply { mkdir() }
            val marker = java.io.File(blocked, "keep").apply { writeText("old") }
            assertTrue(!store.publish(blocked) { it.write(png); true })
            assertEquals("old", marker.readText())
            assertEquals(setOf(old.name, blocked.name), dir.list().orEmpty().toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `按目标尺寸解_不返回原图大小`() = runBlocking {
        val source = FakeSource(1, png)
        val loader = CoverPageLoader(source, 40, 53)
        val bmp = loader.load(0)
        assertNotNull(bmp)
        // 8×11 的原图比目标还小，不应该被放大
        assertEquals(8, bmp!!.width)
        assertEquals(11, bmp.height)
    }
}
