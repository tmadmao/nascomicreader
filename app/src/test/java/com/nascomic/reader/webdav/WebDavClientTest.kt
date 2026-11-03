package com.nascomic.reader.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    private val multiStatus = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E5%8D%B7%E4%B8%80.zip</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>10240</D:getcontentlength></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/sub/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""

    @Test
    fun `解析 207 列出文件与目录`() {
        val entries = WebDavClient.parseMultiStatus(multiStatus, "https://nas.local/dav/")
        assertEquals(3, entries.size)

        val file = entries[1]
        assertEquals("卷一.zip", file.name)               // href 是百分号编码，要解码
        assertEquals(10240L, file.size)
        assertFalse(file.isDirectory)
        assertEquals("https://nas.local/dav/%E5%8D%B7%E4%B8%80.zip", file.url)

        assertTrue(entries[2].isDirectory)
    }

    @Test
    fun `没有命名空间前缀也能解析`() {
        val xml = """<multistatus xmlns="DAV:">
            <response><href>a.cbz</href><propstat><prop><resourcetype/>
            <getcontentlength>123</getcontentlength></prop></propstat></response>
        </multistatus>"""
        val entries = WebDavClient.parseMultiStatus(xml, "https://x/y/")
        assertEquals(1, entries.size)
        assertEquals("a.cbz", entries[0].name)
        assertEquals(123L, entries[0].size)
    }

    @Test
    fun `href 拼成完整地址`() {
        assertEquals(
            "https://nas.local/dav/a.zip",
            WebDavClient.absoluteUrl("https://nas.local/dav/", "/dav/a.zip")
        )
        assertEquals(
            "https://nas.local/dav/a.zip",
            WebDavClient.absoluteUrl("https://nas.local/dav/", "a.zip")
        )
        assertEquals(
            "https://other/x.zip",
            WebDavClient.absoluteUrl("https://nas.local/dav/", "https://other/x.zip")
        )
    }

    @Test
    fun `文件名解码与取末段`() {
        assertEquals("卷一.zip", WebDavClient.decodeName("https://n/dav/%E5%8D%B7%E4%B8%80.zip"))
        assertEquals("a.zip", WebDavClient.decodeName("https://n/dav/a.zip/"))
    }

    @Test
    fun `href 里的 XML 实体要还原`() {
        // 文件名带 & 时服务端不会百分号编码，而是按 XML 转义；不还原，拿去请求就是 404
        assertEquals("[组A&组B].zip", WebDavClient.unescapeXml("[组A&amp;组B].zip"))
        assertEquals("a<b>c.zip", WebDavClient.unescapeXml("a&lt;b&gt;c.zip"))
        assertEquals("引\"号.zip", WebDavClient.unescapeXml("引&quot;号.zip"))
        assertEquals("& 与 & 都是 &", WebDavClient.unescapeXml("&amp; 与 &#38; 都是 &#x26;"))
        // 不是实体的 & 原样保留（比如已经解码过的内容）
        assertEquals("a&b", WebDavClient.unescapeXml("a&b"))
        assertEquals("未知&zz;保留", WebDavClient.unescapeXml("未知&zz;保留"))
    }

    @Test
    fun `带 & 的文件名整条解析出来`() {
        // 对着真实服务端的返回形态造的：& 原样出现在 href 里、被转义成 &amp;
        val xml = """<D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/test/%5B%E7%BB%84A%5D%20%E5%8D%B7%E4%B8%80%20%5B%E7%BB%84B&amp;%E7%BB%84C%5D.zip</D:href>
            <D:propstat><D:prop><D:resourcetype/>
            <D:getcontentlength>4688002298</D:getcontentlength></D:prop></D:propstat></D:response>
        </D:multistatus>"""
        val entries = WebDavClient.parseMultiStatus(xml, "http://nas.local:5005/")
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("[组A] 卷一 [组B&组C].zip", e.name)
        // 请求地址里必须是字面量 &，不能再带着 amp;
        assertEquals(
            "http://nas.local:5005/test/%5B%E7%BB%84A%5D%20%E5%8D%B7%E4%B8%80%20%5B%E7%BB%84B&%E7%BB%84C%5D.zip",
            e.url
        )
    }
}
