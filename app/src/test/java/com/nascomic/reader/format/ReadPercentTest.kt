package com.nascomic.reader.format

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadPercentTest {

    @Test
    fun `按页号换算`() {
        // 停在第几页就算读了几页，第 1 页是 1 / 3
        assertEquals(33, readPercent(pageGlobal = 0, total = 3, finished = false))
        assertEquals(66, readPercent(pageGlobal = 1, total = 3, finished = false))
        assertEquals(100, readPercent(pageGlobal = 2, total = 3, finished = false))
    }

    @Test
    fun `读到书的尾就是一百`() {
        // 双页并排：最后一屏停在倒数第二页，页号换算只有 66%
        assertEquals(100, readPercent(pageGlobal = 1, total = 3, finished = true))
    }

    @Test
    fun `页数未知时不报错`() {
        assertEquals(0, readPercent(pageGlobal = 0, total = 0, finished = false))
        assertEquals(100, readPercent(pageGlobal = 0, total = 0, finished = true))
    }
}
