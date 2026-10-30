package com.nascomic.reader.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbTargetTest {

    @Test
    fun `完整地址拆成主机共享与路径`() {
        val t = SmbTarget.parse("smb://192.168.1.10/漫画/单行本")
        assertEquals("192.168.1.10", t.host)
        assertEquals(SmbTarget.DEFAULT_PORT, t.port)
        assertEquals("漫画", t.share)
        assertEquals("单行本", t.path)
    }

    @Test
    fun `带端口与共享根`() {
        val t = SmbTarget.parse("smb://nas.local:1445/comics")
        assertEquals("nas.local", t.host)
        assertEquals(1445, t.port)
        assertEquals("comics", t.share)
        assertEquals("", t.path)
    }

    @Test
    fun `UNC 写法与反斜杠`() {
        val t = SmbTarget.parse("\\\\192.168.1.10\\漫画\\第1话")
        assertEquals("192.168.1.10", t.host)
        assertEquals("漫画", t.share)
        assertEquals("第1话", t.path)
    }

    @Test
    fun `少打协议头也能认`() {
        val t = SmbTarget.parse("192.168.1.10/share/a/b")
        assertEquals("192.168.1.10", t.host)
        assertEquals("share", t.share)
        assertEquals("a/b", t.path)
    }

    @Test
    fun `多余的斜杠不影响`() {
        val t = SmbTarget.parse("  smb://host//share//dir/  ")
        assertEquals("host", t.host)
        assertEquals("share", t.share)
        assertEquals("dir", t.path)
    }

    @Test
    fun `缺共享名要报错`() {
        assertThrows(IllegalArgumentException::class.java) { SmbTarget.parse("smb://192.168.1.10") }
        assertThrows(IllegalArgumentException::class.java) { SmbTarget.parse("   ") }
    }

    @Test
    fun `路径拼接用共享内相对路径`() {
        val root = SmbTarget.parse("smb://h/share")
        assertEquals("a.zip", root.pathOf("a.zip"))

        val sub = SmbTarget.parse("smb://h/share/漫画")
        assertEquals("漫画/第1话", sub.pathOf("第1话"))
    }
}
