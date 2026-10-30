package com.nascomic.reader.ui.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfGridTest {

    /** 常见屏宽（px）与密度：360dp 手机、411dp 手机、711dp 平板 */
    private val phone360 = 1080 to 3.0f
    private val phone411 = 1080 to 2.625f
    private val pad711 = 1600 to 2.25f

    private fun columnsOf(screen: Pair<Int, Float>): Int {
        val (width, density) = screen
        val available = width - (16 * density).toInt()
        val minCell = (ShelfGrid.MIN_CELL_DP * density).toInt()
        return ShelfGrid.columns(available, minCell)
    }

    @Test
    fun `手机维持三列`() {
        assertEquals(3, columnsOf(phone360))
        assertEquals(3, columnsOf(phone411))
    }

    @Test
    fun `平板一行能放六本`() {
        // 这是小米平板 5 的真实参数：1600px / 2.25
        assertEquals(6, columnsOf(pad711))
    }

    @Test
    fun `极宽屏有上限，极窄屏不塌成两列`() {
        assertEquals(ShelfGrid.MAX_COLUMNS, ShelfGrid.columns(6000, 100))
        assertEquals(3, ShelfGrid.columns(200, 100))
    }

    @Test
    fun `宽度不够时返回可用值而不是零`() {
        assertEquals(0, ShelfGrid.cellWidth(0, 0))
        assertEquals(300, ShelfGrid.cellWidth(900, 3))
    }
}
