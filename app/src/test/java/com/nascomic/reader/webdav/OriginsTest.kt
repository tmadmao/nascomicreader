package com.nascomic.reader.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据只发给配置的站点。
 *
 * 判断口径：协议 + 主机 + 有效端口，三者都一样才算同源；
 * 端口写不写、大小写都不该影响判断。
 */
class OriginsTest {

    @Test
    fun `抽出站点身份`() {
        assertEquals("https://nas.local", Origins.of("https://nas.local/dav/漫画/"))
        assertEquals("http://192.168.5.18:5005", Origins.of("http://192.168.5.18:5005/dav/"))
        // 默认端口省掉：写法不同，站点是同一个
        assertEquals("https://nas.local", Origins.of("https://nas.local:443/dav/"))
        assertEquals("http://nas.local", Origins.of("http://NAS.Local:80/dav/"))
    }

    @Test
    fun `不是完整地址就没有站点`() {
        assertNull(Origins.of("/dav/a.zip"))
        assertNull(Origins.of(""))
    }

    @Test
    fun `同源才为真`() {
        val base = "https://nas.local:5006/dav/"
        assertTrue(Origins.same(base, "https://nas.local:5006/dav/a.zip"))
        assertTrue(Origins.same(base, "https://NAS.local:5006/other/b.zip"))

        // 换主机
        assertFalse(Origins.same(base, "https://evil.example/a.zip"))
        // 换端口
        assertFalse(Origins.same(base, "https://nas.local:5007/a.zip"))
        // HTTPS 降级成 HTTP
        assertFalse(Origins.same(base, "http://nas.local:5006/a.zip"))
        // 默认端口与显式端口算同一个
        assertTrue(Origins.same("https://nas.local/dav/", "https://nas.local:443/x"))
    }

    @Test
    fun `取路径`() {
        assertEquals("/dav/a.zip", Origins.pathOf("https://other:8080/dav/a.zip"))
        assertEquals("/", Origins.pathOf("https://other:8080"))
        assertEquals("/x?a=1", Origins.pathOf("https://other/x?a=1"))
    }
}
