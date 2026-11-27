package com.nascomic.reader.ui.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBounceTest {

    @Test
    fun `越过边界按阻尼缩小`() {
        assertEquals(3.5f, IndexBounce.offset(10f, 40f), 0.001f)
    }

    @Test
    fun `方向跟着越界方向`() {
        assertTrue(IndexBounce.offset(-10f, 40f) < 0f)
        assertTrue(IndexBounce.offset(10f, 40f) > 0f)
    }

    @Test
    fun `越界再远也封顶`() {
        assertEquals(40f, IndexBounce.offset(400f, 40f), 0.001f)
        assertEquals(-40f, IndexBounce.offset(-400f, 40f), 0.001f)
    }

    @Test
    fun `界内不产生位移`() {
        assertEquals(0f, IndexBounce.offset(0f, 40f), 0f)
    }

    @Test
    fun `上限为零时一动不动`() {
        assertEquals(0f, IndexBounce.offset(30f, 0f), 0f)
    }
}
