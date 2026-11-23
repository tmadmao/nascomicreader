package com.nascomic.reader.library.health

import com.nascomic.reader.format.zip.ZipFormatException
import com.nascomic.reader.library.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class FileHealthRulesTest {

    @Test
    fun `库里没量过页数时不报差异`() {
        // 0 是「还没量过」，不是「量出来是 0 页」；拿它比会整库误报
        assertNull(FileHealthRules.judge(storedPages = 0, actualPages = 8))
    }

    @Test
    fun `读出零页按没有图片页报`() {
        assertEquals(
            HealthIssue.Kind.NO_IMAGES,
            FileHealthRules.judge(storedPages = 3, actualPages = 0),
        )
        assertEquals(
            HealthIssue.Kind.NO_IMAGES,
            FileHealthRules.judge(storedPages = 0, actualPages = 0),
        )
    }

    @Test
    fun `页数一致不算问题`() {
        assertNull(FileHealthRules.judge(storedPages = 3, actualPages = 3))
    }

    @Test
    fun `页数变了报对不上`() {
        assertEquals(
            HealthIssue.Kind.PAGE_MISMATCH,
            FileHealthRules.judge(storedPages = 3, actualPages = 4),
        )
        assertEquals(
            HealthIssue.Kind.PAGE_MISMATCH,
            FileHealthRules.judge(storedPages = 4, actualPages = 3),
        )
    }

    @Test
    fun `成因按异常类型归，不读英文原文`() {
        assertEquals(HealthIssue.Cause.CORRUPT, FileHealthRules.causeOf(ZipFormatException("坏")))
        assertEquals(HealthIssue.Cause.MISSING, FileHealthRules.causeOf(FileNotFoundException("没了")))
        assertEquals(HealthIssue.Cause.PERMISSION, FileHealthRules.causeOf(SecurityException("没权限")))
        assertEquals(
            HealthIssue.Cause.FORMAT,
            FileHealthRules.causeOf(UnsupportedOperationException("不认识")),
        )
        assertEquals(HealthIssue.Cause.OTHER, FileHealthRules.causeOf(IOException("读不动")))
        assertEquals(HealthIssue.Cause.OTHER, FileHealthRules.causeOf(null))
    }

    @Test
    fun `异常归不出类时按格式兜底`() {
        // 压缩包几个解析器都读不动，就是包坏了；目录书读不动仍算「读取失败」
        assertEquals(
            HealthIssue.Cause.CORRUPT,
            FileHealthRules.causeOf(IOException("read"), BookEntity.FORMAT_ZIP),
        )
        assertEquals(
            HealthIssue.Cause.CORRUPT,
            FileHealthRules.causeOf(IOException("read"), BookEntity.FORMAT_ARCHIVE),
        )
        assertEquals(
            HealthIssue.Cause.FORMAT,
            FileHealthRules.causeOf(IOException("read"), BookEntity.FORMAT_EPUB),
        )
        assertEquals(
            HealthIssue.Cause.OTHER,
            FileHealthRules.causeOf(IOException("read"), BookEntity.FORMAT_FOLDER),
        )
        // 明确的异常类型优先：压缩包里少了文件，不能盖成「结构损坏」
        assertEquals(
            HealthIssue.Cause.MISSING,
            FileHealthRules.causeOf(FileNotFoundException("x"), BookEntity.FORMAT_ZIP),
        )
    }
}
