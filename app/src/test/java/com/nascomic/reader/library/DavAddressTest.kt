package com.nascomic.reader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavAddressTest {

    @Test
    fun `只有主机时按协议补默认端口`() {
        val p = DavAddress.parse("HTTPS", "nas.example.com", "")
        assertEquals("https", p.scheme)
        assertEquals(443, p.port)
        assertFalse(p.portSet)
        assertEquals("https://nas.example.com", DavAddress.toUrl(p))
    }

    @Test
    fun `单独填的端口要生效`() {
        val p = DavAddress.parse("http", "192.168.1.10", "5005")
        assertEquals(5005, p.port)
        assertTrue(p.portSet)
        assertEquals("http://192.168.1.10:5005", DavAddress.toUrl(p))
    }

    @Test
    fun `地址里带端口也能拆出来`() {
        val p = DavAddress.parse("https", "192.168.1.10:5006", "")
        assertEquals("192.168.1.10", p.host)
        assertEquals(5006, p.port)
        assertEquals("https://192.168.1.10:5006", DavAddress.toUrl(p))
    }

    @Test
    fun `端口框优先于地址里的端口`() {
        // 从 NAS 后台复制来一整串，又另外填了端口：以框里填的为准
        val p = DavAddress.parse("https", "192.168.1.10:5006/dav", "5007")
        assertEquals(5007, p.port)
        assertEquals("https://192.168.1.10:5007/dav", DavAddress.toUrl(p))
    }

    @Test
    fun `地址里带协议头时以它为准`() {
        val p = DavAddress.parse("https", "http://192.168.1.10:5005/dav/", "")
        assertEquals("http", p.scheme)
        assertEquals("192.168.1.10", p.host)
        assertEquals(5005, p.port)
        assertEquals("dav", p.path)
        assertEquals("http://192.168.1.10:5005/dav", DavAddress.toUrl(p))
    }

    @Test
    fun `路径前后的斜杠都吃掉`() {
        val p = DavAddress.parse("https", "  nas.example.com//dav//  ", "")
        assertEquals("nas.example.com", p.host)
        assertEquals("https://nas.example.com/dav", DavAddress.toUrl(p))
        assertEquals("https://nas.example.com/dav", DavAddress.toUrl(DavAddress.parse("https", "nas.example.com/dav/", "")))
    }

    @Test
    fun `默认端口不写进 URL`() {
        assertEquals("http://h", DavAddress.toUrl(DavAddress.parse("http", "h", "80")))
        assertEquals("https://h", DavAddress.toUrl(DavAddress.parse("https", "h", "443")))
        assertEquals("http://h:443", DavAddress.toUrl(DavAddress.parse("http", "h", "443")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地址空着要报错`() {
        DavAddress.parse("https", "   ", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `端口越界要报错`() {
        DavAddress.parse("https", "h", "70000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `端口不是数字要报错`() {
        DavAddress.parse("https", "h", "50a5")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `地址里端口不是数字要报错`() {
        DavAddress.parse("https", "h:abc", "")
    }
}
