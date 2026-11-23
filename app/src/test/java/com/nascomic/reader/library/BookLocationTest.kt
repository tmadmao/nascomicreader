package com.nascomic.reader.library

import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BookLocationTest {

    @Test
    fun `本地书去掉卷前缀，保留选的那层目录`() {
        assertEquals(
            "Download/漫画/示例漫画01卷.zip",
            BookLocation.displayPath(
                "primary:Download/漫画/示例漫画01卷.zip",
                BookEntity.FORMAT_ZIP,
                SourceEntity.KIND_LOCAL,
            ),
        )
    }

    @Test
    fun `目录书带尾斜杠`() {
        assertEquals(
            "Download/漫画/示例漫画/",
            BookLocation.displayPath(
                "primary:Download/漫画/示例漫画",
                BookEntity.FORMAT_FOLDER,
                SourceEntity.KIND_LOCAL,
            ),
        )
        // 本来就是尾斜杠的别再加一条
        assertEquals(
            "Download/漫画/示例漫画/",
            BookLocation.displayPath(
                "primary:Download/漫画/示例漫画/",
                BookEntity.FORMAT_FOLDER,
                SourceEntity.KIND_LOCAL,
            ),
        )
    }

    @Test
    fun `SMB 的共享内路径原样显示`() {
        assertEquals(
            "test/hc_remote/Bad.zip",
            BookLocation.displayPath(
                "test/hc_remote/Bad.zip",
                BookEntity.FORMAT_ZIP,
                SourceEntity.KIND_SMB,
            ),
        )
    }

    @Test
    fun `WebDAV 去掉协议主机并还原百分号编码`() {
        assertEquals(
            "test/My Book.zip",
            BookLocation.displayPath(
                "http://192.168.5.18:5005/test/My%20Book.zip",
                BookEntity.FORMAT_ZIP,
                SourceEntity.KIND_WEBDAV,
            ),
        )
        assertEquals(
            "test/漫画/弘如篇.zip",
            BookLocation.displayPath(
                "http://192.168.5.18:5005/test/%E6%BC%AB%E7%94%BB/%E5%BC%98%E5%A6%82%E7%AF%87.zip",
                BookEntity.FORMAT_ZIP,
                SourceEntity.KIND_WEBDAV,
            ),
        )
    }

    @Test
    fun `WebDAV 目录不因尾斜杠变成双斜杠`() {
        assertEquals(
            "test/hc_remote/BookA/",
            BookLocation.displayPath(
                "http://192.168.5.18:5005/test/hc_remote/BookA/",
                BookEntity.FORMAT_FOLDER,
                SourceEntity.KIND_WEBDAV,
            ),
        )
    }

    @Test
    fun `没有卷前缀的本地路径原样显示`() {
        assertEquals(
            "Download/foo.zip",
            BookLocation.displayPath(
                "Download/foo.zip",
                BookEntity.FORMAT_ZIP,
                SourceEntity.KIND_LOCAL,
            ),
        )
    }

    @Test
    fun `来源缺失时按 docRef 形态判断`() {
        assertEquals(
            "test/Book.zip",
            BookLocation.displayPath(
                "http://host:5005/test/Book.zip",
                BookEntity.FORMAT_ZIP,
                null,
            ),
        )
        assertEquals(
            "Download/Book.zip",
            BookLocation.displayPath(
                "primary:Download/Book.zip",
                BookEntity.FORMAT_ZIP,
                null,
            ),
        )
    }

    @Test
    fun `空路径不显示`() {
        assertEquals("", BookLocation.displayPath("", BookEntity.FORMAT_ZIP, SourceEntity.KIND_LOCAL))
        assertEquals("", BookLocation.displayPath("   ", BookEntity.FORMAT_ZIP, SourceEntity.KIND_SMB))
    }
}
