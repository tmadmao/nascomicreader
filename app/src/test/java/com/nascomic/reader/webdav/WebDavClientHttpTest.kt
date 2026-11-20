package com.nascomic.reader.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对着真的 HTTP 服务验证客户端行为——不是拿假数据调解析函数。
 *
 * 覆盖审计里三条：
 * - 凭据只发给配置的站点（别的站点一个请求都不该收到，更不该收到 Authorization）
 * - 列目录响应不是 WebDAV 列表时要报错，不能当成「空目录」
 * - 下载不完整不能留下最终文件，否则那本书从此打不开
 */
class WebDavClientHttpTest {

    private lateinit var primary: HttpServer
    private lateinit var other: HttpServer

    /** 另一个站点收到的请求数与其中带凭据的数量 */
    private val otherHits = AtomicInteger()
    private val otherAuthed = AtomicInteger()

    private var primaryPort = 0
    private var otherPort = 0

    /** 主站点的响应体由每个用例自己决定 */
    private var listingXml: () -> String = { multiStatus("/dav/") }
    private var fileBytes: ByteArray = ByteArray(0)
    private var sendHalf = false

    @Before
    fun setUp() {
        primary = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> handlePrimary(ex) }
            start()
        }
        other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                otherHits.incrementAndGet()
                if (ex.requestHeaders.getFirst("Authorization") != null) otherAuthed.incrementAndGet()
                ex.sendResponseHeaders(401, -1)
                ex.close()
            }
            start()
        }
        primaryPort = primary.address.port
        otherPort = other.address.port
    }

    @After
    fun tearDown() {
        primary.stop(0)
        other.stop(0)
    }

    @Test
    fun `列目录响应不是 WebDAV 列表时报错_不当成空目录`() {
        listingXml = { "<html><body>login</body></html>" }
        val client = WebDavClient("u", "p", origin())
        val error = runCatching { client.list(origin() + "/dav/") }.exceptionOrNull()
        assertTrue("应该抛错，实际是 $error", error is WebDavException)
    }

    @Test
    fun `目录真的空时返回空表`() {
        listingXml = { multiStatus("/dav/") }
        val client = WebDavClient("u", "p", origin())
        assertEquals(0, client.list(origin() + "/dav/").size)
    }

    @Test
    fun `目录项指向别的站点时_不把凭据发过去`() {
        // 列表里混进一条绝对地址，指向另一个端口上的站点
        listingXml = {
            multiStatus(
                "/dav/",
                "<D:response><D:href>http://127.0.0.1:$otherPort/dav/evil.7z</D:href>" +
                    "<D:propstat><D:prop><D:resourcetype/>" +
                    "<D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>",
            )
        }
        val client = WebDavClient("u", "p", origin())
        val entries = client.list(origin() + "/dav/")

        // 条目还在，但地址被换回了配置的站点：请求根本不会发到别处
        assertEquals(1, entries.size)
        assertTrue(entries[0].url.startsWith(origin() + "/dav/"))
        assertEquals(0, otherHits.get())
        assertEquals(0, otherAuthed.get())
    }

    @Test
    fun `下载不完整时不留下最终文件`() {
        fileBytes = ByteArray(4096) { it.toByte() }
        sendHalf = true
        val out = File.createTempFile("dav-partial", ".bin").apply { delete() }
        val client = WebDavClient("u", "p", origin())

        val error = runCatching { client.download(origin() + "/dav/a.7z", out) }.exceptionOrNull()
        assertTrue("应该报下载不完整，实际是 $error", error is WebDavException)
        assertFalse("半份文件不能出现在最终路径上", out.exists())
        assertFalse(File(out.parentFile, out.name + ".part").exists())
    }

    @Test
    fun `下载完整时才落到最终路径`() {
        fileBytes = ByteArray(1024) { 7 }
        sendHalf = false
        val out = File.createTempFile("dav-full", ".bin").apply { delete() }
        val client = WebDavClient("u", "p", origin())

        client.download(origin() + "/dav/a.7z", out)
        assertTrue(out.isFile)
        assertEquals(1024L, out.length())
        out.delete()
    }

    @Test
    fun `没有站点信息时不额外限制`() {
        // 手动浏览远端目录时还不知道站点，行为与改动前一致
        listingXml = { multiStatus("/dav/") }
        val client = WebDavClient("u", "p", null)
        assertNull(runCatching { client.list(origin() + "/dav/") }.exceptionOrNull())
    }

    // ---- 桩 ----

    private fun origin(): String = "http://127.0.0.1:$primaryPort"

    private fun multiStatus(href: String, extra: String = ""): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        append("<D:response><D:href>")
        append(href)
        append("</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>")
        append("</D:prop></D:propstat></D:response>")
        append(extra)
        append("</D:multistatus>")
    }

    private fun handlePrimary(ex: HttpExchange) {
        when (ex.requestMethod) {
            "PROPFIND" -> {
                val body = listingXml().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
                ex.sendResponseHeaders(207, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            "GET", "HEAD" -> {
                val total = fileBytes.size
                // 声明的是完整长度；真断了的时候只发一半就关门。
                // 「声明一半」不算中断——那样响应本身是完整的，客户端没理由报错
                ex.sendResponseHeaders(200, if (ex.requestMethod == "HEAD") -1 else total.toLong())
                if (ex.requestMethod != "HEAD") {
                    val send = if (sendHalf) total / 2 else total
                    runCatching {
                        ex.responseBody.write(fileBytes, 0, send)
                        ex.responseBody.flush()
                    }
                }
                // 少发一半就关门：模拟网络中断
                ex.close()
            }
            else -> {
                ex.sendResponseHeaders(405, -1)
                ex.close()
            }
        }
    }
}
