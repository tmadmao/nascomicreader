package com.nascomic.reader.format.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubPagesTest {

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    @Test
    fun `从 container 取 opf 路径`() {
        assertEquals("OEBPS/content.opf", Opf.rootPath(container))
        assertNull(Opf.rootPath("<container/>"))
    }

    @Test
    fun `解析清单与书脊顺序`() {
        val opf = """
            <package version="3.0">
              <manifest>
                <item id="p2" href="pages/2.xhtml" media-type="application/xhtml+xml"/>
                <item id="p1" href="pages/1.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine>
                <itemref idref="p1"/>
                <itemref idref="p2"/>
              </spine>
            </package>
        """.trimIndent()
        val pkg = Opf.parse(opf)
        assertEquals(3, pkg.items.size)
        assertEquals(listOf("p1", "p2"), pkg.spine)
        assertEquals("pages/1.xhtml", pkg.items.first { it.id == "p1" }.href)
    }

    @Test
    fun `书脊是文档时取出里面的图`() {
        val pkg = Opf.Package(
            items = listOf(
                Opf.Item("a", "text/a.xhtml", "application/xhtml+xml"),
                Opf.Item("b", "text/b.xhtml", "application/xhtml+xml"),
            ),
            spine = listOf("a", "b"),
        )
        val html = mapOf(
            "OEBPS/text/a.xhtml" to """<html><body><img src="../img/001.jpg"/></body></html>""",
            "OEBPS/text/b.xhtml" to """<html><body><img src="../img/002.jpg"/></body></html>""",
        )
        val pages = EpubPages.resolve(pkg, "OEBPS/content.opf") { html[it] }
        assertEquals(listOf("OEBPS/img/001.jpg", "OEBPS/img/002.jpg"), pages)
    }

    @Test
    fun `书脊直接是图片时原样取出`() {
        val pkg = Opf.Package(
            items = listOf(
                Opf.Item("i1", "images/1.jpg", "image/jpeg"),
                Opf.Item("i2", "images/2.jpg", "image/jpeg"),
            ),
            spine = listOf("i2", "i1"),
        )
        val pages = EpubPages.resolve(pkg, "content.opf") { null }
        assertEquals(listOf("images/2.jpg", "images/1.jpg"), pages)
    }

    @Test
    fun `书脊里没有图时退回按名字排清单里的图片`() {
        val pkg = Opf.Package(
            items = listOf(
                Opf.Item("x", "img/p10.jpg", "image/jpeg"),
                Opf.Item("y", "img/p2.jpg", "image/jpeg"),
                Opf.Item("z", "img/p1.jpg", "image/jpeg"),
                Opf.Item("t", "text.xhtml", "application/xhtml+xml"),
            ),
            spine = listOf("t"),
        )
        val pages = EpubPages.resolve(pkg, "content.opf") { "<html><body>没有图</body></html>" }
        assertEquals(listOf("img/p1.jpg", "img/p2.jpg", "img/p10.jpg"), pages)
    }

    @Test
    fun `svg 里的图也能取到`() {
        val html = """<html><body><svg><image xlink:href="../img/003.png"/></svg></body></html>"""
        assertEquals("../img/003.png", EpubPages.firstImageSrc(html))
    }

    @Test
    fun `路径拼接会消掉点点`() {
        assertEquals("OEBPS/img/a.jpg", EpubPages.join("OEBPS/text", "../img/a.jpg"))
        assertEquals("img/a.jpg", EpubPages.join("", "img/a.jpg"))
        assertEquals("a.jpg", EpubPages.join("OEBPS", "../../a.jpg"))
        assertEquals("OEBPS/text", EpubPages.dirOf("OEBPS/text/a.xhtml"))
        assertEquals("", EpubPages.dirOf("a.xhtml"))
    }
}
