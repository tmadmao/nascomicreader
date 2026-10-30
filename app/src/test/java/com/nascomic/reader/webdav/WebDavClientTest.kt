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
}
