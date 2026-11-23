package com.nascomic.reader.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.health.FileHealthCheck
import com.nascomic.reader.settings.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 审计期间按取消，正在下载的单本必须立刻断开，而不是等服务器把整份发完。
 *
 * 这里在设备上起一个慢速 WebDAV 服务：GET 每 8 KB 停 10 ms。审计线程开始下载后
 * 设置取消标志，要求 1.5 秒内返回中断，并且缓存里不留最终文件或 `.part`。
 *
 * 两条：一条直接走 [BookOpener.openForAudit]，确认下载层能断；另一条用内存库走完整的
 * [FileHealthCheck.check]，确认「下载中断」会被归成“已取消”，不会误报成“这本书打不开”。
 */
@RunWith(AndroidJUnit4::class)
class AuditCancelTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `取消能中断审计中的单本下载`() {
        val server = SlowDavServer()
        val bookId = -20261125L
        SecretStore(context).put(SECRET, "p")
        LocalCopies.evictBooks(context, listOf(bookId))
        try {
            server.start()
            val source = SourceEntity(
                id = 1,
                kind = SourceEntity.KIND_WEBDAV,
                label = "慢速测试源",
                endpoint = server.base,
                account = "u",
                secretRef = SECRET,
                addedAt = 1,
            )
            val book = BookEntity(
                id = bookId,
                sourceId = 1,
                docRef = server.fileUrl,
                title = "slow.pdf",
                titleSort = "slow.pdf",
                format = BookEntity.FORMAT_PDF,
                byteSize = server.total.toLong(),
                addedAt = 1,
            )

            val cancelled = AtomicBoolean(false)
            var error: Throwable? = null
            val audit = thread(name = "audit-cancel-test") {
                error = runCatching {
                    BookOpener(context)
                        .openForAudit(book, source, emptyList()) { cancelled.get() }
                        .use { }
                }.exceptionOrNull()
            }

            assertTrue("下载应已开始", server.awaitSent(64L * 1024))
            // 确认它确实卡在传输途中，而不是已经下完
            Thread.sleep(100)
            val started = System.currentTimeMillis()
            cancelled.set(true)
            audit.join(2500)
            val elapsed = System.currentTimeMillis() - started

            assertFalse("取消后审计线程仍在跑，说明下载没有被中断", audit.isAlive)
            assertTrue("取消应报中断，实际是 $error", error is InterruptedIOException)
            assertTrue("取消后应秒级返回，实际 $elapsed ms", elapsed < 1500)
            assertFalse("取消后不能留下最终文件", cachedFiles(bookId).any { !it.name.endsWith(".part") })
            assertFalse("取消后不能留下 .part", cachedFiles(bookId).any { it.name.endsWith(".part") })
        } finally {
            server.close()
            LocalCopies.evictBooks(context, listOf(bookId))
            SecretStore(context).remove(SECRET)
        }
    }

    @Test
    fun `检查中点取消_整轮检查立即结束`() {
        val server = SlowDavServer()
        val bookId = -20261126L
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SecretStore(context).put(SECRET, "p")
        LocalCopies.evictBooks(context, listOf(bookId))
        try {
            server.start()
            val source = SourceEntity(
                kind = SourceEntity.KIND_WEBDAV,
                label = "慢速测试源",
                endpoint = server.base,
                account = "u",
                secretRef = SECRET,
                addedAt = 1,
            )
            val sourceId = runBlocking { db.sourceDao().insert(source) }
            runBlocking {
                db.bookDao().insert(
                    BookEntity(
                        id = bookId,
                        sourceId = sourceId,
                        docRef = server.fileUrl,
                        title = "slow.pdf",
                        titleSort = "slow.pdf",
                        format = BookEntity.FORMAT_PDF,
                        byteSize = server.total.toLong(),
                        addedAt = 1,
                    )
                )
            }

            val cancelled = AtomicBoolean(false)
            var error: Throwable? = null
            val check = FileHealthCheck(context, db)
            val worker = thread(name = "health-cancel-test") {
                error = runCatching {
                    runBlocking {
                        check.check(source.copy(id = sourceId)) { cancelled.get() }
                    }
                }.exceptionOrNull()
            }

            assertTrue("下载应已开始", server.awaitHeaders())
            Thread.sleep(100)
            val started = System.currentTimeMillis()
            cancelled.set(true)
            worker.join(2500)
            val elapsed = System.currentTimeMillis() - started

            assertFalse("取消后检查线程仍在跑，说明下载没有被中断", worker.isAlive)
            assertTrue("检查应按取消收尾，实际是 $error", error is FileHealthCheck.Cancelled)
            assertTrue("取消后应秒级返回，实际 $elapsed ms", elapsed < 1500)
            assertFalse("取消后不能留下最终文件", cachedFiles(bookId).any { !it.name.endsWith(".part") })
            assertFalse("取消后不能留下 .part", cachedFiles(bookId).any { it.name.endsWith(".part") })
        } finally {
            server.close()
            db.close()
            LocalCopies.evictBooks(context, listOf(bookId))
            SecretStore(context).remove(SECRET)
        }
    }

    /** 缓存目录里与这本书 id 相关的文件 */
    private fun cachedFiles(bookId: Long) =
        LocalCopies.remoteDir(context).listFiles().orEmpty().filter {
            it.name.startsWith("b$bookId")
        }

    /**
     * 一个够用的慢速 WebDAV：PROPFIND 返回列表，HEAD 给长度，GET 慢慢写。
     * 只管一个文件，端口让系统分配。
     */
    private class SlowDavServer {
        val total = 1_000_000
        private val socket = ServerSocket(0)
        private val sent = AtomicLong(0)
        private val started = CountDownLatch(1)
        private val headers = CountDownLatch(1)
        private val stopped = AtomicBoolean(false)
        private var thread: Thread? = null

        val base get() = "http://127.0.0.1:${socket.localPort}/dav"
        val fileUrl get() = "$base/book.pdf"

        fun start() {
            thread = thread(name = "slow-dav", isDaemon = true) {
                while (!stopped.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "slow-dav-conn", isDaemon = true) { handle(client) }
                }
            }
        }

        fun awaitSent(atLeast: Long): Boolean = started.await(5, TimeUnit.SECONDS)

        /** GET 的响应头已经回到客户端，正文还没开始（或刚开始） */
        fun awaitHeaders(): Boolean = headers.await(5, TimeUnit.SECONDS)

        private fun handle(client: Socket) {
            client.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val request = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val method = request.substringBefore(' ')
                val out = s.getOutputStream()
                when (method) {
                    "PROPFIND" -> {
                        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
<d:response><d:href>/dav/</d:href><d:propstat><d:prop>
<d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/dav/book.pdf</d:href><d:propstat><d:prop>
<d:resourcetype/><d:getcontentlength>$total</d:getcontentlength></d:prop>
<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""
                        val bytes = xml.toByteArray()
                        out.write(
                            (
                                "HTTP/1.1 207 Multi-Status\r\n" +
                                    "Content-Type: application/xml; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        out.write(bytes)
                        out.flush()
                    }
                    "HEAD" -> {
                        out.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: $total\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        out.flush()
                    }
                    "GET" -> {
                        out.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: $total\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        out.flush()
                        headers.countDown()
                        var offset = 0
                        while (offset < total && !stopped.get()) {
                            val n = minOf(8 * 1024, total - offset)
                            runCatching { out.write(ByteArray(n) { (it % 251).toByte() }) }
                                .onFailure { return }
                            runCatching { out.flush() }
                            offset += n
                            sent.addAndGet(n.toLong())
                            if (sent.get() >= 64 * 1024) started.countDown()
                            runCatching { Thread.sleep(10) }
                        }
                    }
                    else -> {
                        out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }
            }
        }

        fun close() {
            stopped.set(true)
            runCatching { socket.close() }
            thread?.interrupt()
        }
    }

    private companion object {
        const val SECRET = "audit-cancel-test"
    }
}
