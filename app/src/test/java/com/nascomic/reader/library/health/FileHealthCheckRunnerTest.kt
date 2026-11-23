package com.nascomic.reader.library.health

import com.nascomic.reader.library.data.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 取消必须在一本检查完、最后一本结束之后也生效。
 *
 * 真机上的毛病：最后一本检查期间按取消，检查仍返回完整报告，界面当成「检查完成」。
 * 这里用假 inspect 把取消发生在「一本刚检查完」的时刻，恰好是最后一本。
 */
class FileHealthCheckRunnerTest {

    @Test
    fun `最后一本检查完才按取消也必须中止`() {
        val books = listOf(book(1), book(2))
        var cancel = false

        val failure = assertThrows(FileHealthCheck.Cancelled::class.java) {
            runBlocking {
                runHealthCheck(
                    books = books,
                    listener = null,
                    isCancelled = { cancel },
                    inspect = { b ->
                        if (b.id == 2L) cancel = true
                        FileHealthCheck.Outcome.Clean
                    },
                    probeQuiet = { true },
                )
            }
        }
        assertEquals("检查已取消", failure.message)
    }

    @Test
    fun `取消后不再继续下一本`() {
        val books = listOf(book(1), book(2))
        var cancel = false
        val visited = ArrayList<Long>()

        assertThrows(FileHealthCheck.Cancelled::class.java) {
            runBlocking {
                runHealthCheck(
                    books = books,
                    listener = null,
                    isCancelled = { cancel },
                    inspect = { b ->
                        visited += b.id
                        cancel = true
                        FileHealthCheck.Outcome.Clean
                    },
                    probeQuiet = { true },
                )
            }
        }
        assertEquals(listOf(1L), visited)
    }

    @Test
    fun `没有取消时照常返回报告`() {
        val books = listOf(book(1), book(2), book(3))
        val report = runBlocking {
            runHealthCheck(
                books = books,
                listener = null,
                isCancelled = { false },
                inspect = { FileHealthCheck.Outcome.Clean },
                probeQuiet = { true },
            )
        }
        assertEquals(3, report.total)
        assertEquals(3, report.checked)
        assertEquals(0, report.skipped)
        assertEquals(0, report.issues.size)
    }

    private fun book(id: Long) = BookEntity(
        id = id,
        sourceId = 1,
        docRef = "book$id.zip",
        title = "book$id",
        titleSort = "book$id",
        format = BookEntity.FORMAT_ZIP,
        addedAt = 1000,
    )
}
