package com.nascomic.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTest {

    @Test
    fun `速度随档位单调上升且两端钳位`() {
        val speeds = (AutoScroll.MIN_LEVEL..AutoScroll.MAX_LEVEL).map { AutoScroll.speedDpPerSecond(it) }
        assertTrue(speeds.zipWithNext().all { (a, b) -> b > a })
        assertEquals(AutoScroll.speedDpPerSecond(1), AutoScroll.speedDpPerSecond(-5), 0f)
        assertEquals(AutoScroll.speedDpPerSecond(9), AutoScroll.speedDpPerSecond(99), 0f)
    }

    @Test
    fun `档位赋值越界收进区间`() {
        val auto = AutoScroll()
        auto.level = 0
        assertEquals(AutoScroll.MIN_LEVEL, auto.level)
        auto.level = 100
        assertEquals(AutoScroll.MAX_LEVEL, auto.level)
    }

    @Test
    fun `没开始时推进量为零`() {
        val auto = AutoScroll()
        assertFalse(auto.running)
        assertEquals(0f, auto.tick(0.5f, atEnd = false), 0f)
    }

    @Test
    fun `匀速推进：位移等于速度乘时长`() {
        val auto = AutoScroll()
        auto.level = 4
        auto.start()
        val expected = AutoScroll.speedDpPerSecond(4) * 0.25f
        assertEquals(expected, auto.tick(0.25f, atEnd = false), 0.001f)
        // 同样的时长再来一帧，位移不变——匀速，不随时间加速
        assertEquals(expected, auto.tick(0.25f, atEnd = false), 0.001f)
    }

    @Test
    fun `零或负时长不产生位移`() {
        val auto = AutoScroll()
        auto.start()
        assertEquals(0f, auto.tick(0f, atEnd = false), 0f)
        assertEquals(0f, auto.tick(-0.1f, atEnd = false), 0f)
        assertTrue(auto.running)
    }

    @Test
    fun `内容上一碰就停`() {
        val auto = AutoScroll()
        auto.start()
        auto.onContentTouch()
        assertFalse(auto.running)
        assertEquals(0f, auto.tick(0.5f, atEnd = false), 0f)
    }

    @Test
    fun `到书的尾自停且不再推进`() {
        val auto = AutoScroll()
        auto.start()
        assertEquals(0f, auto.tick(0.5f, atEnd = true), 0f)
        assertFalse(auto.running)
        assertEquals(0f, auto.tick(0.5f, atEnd = false), 0f)
    }

    @Test
    fun `停了还能再开，档位保留`() {
        val auto = AutoScroll()
        auto.level = 7
        auto.start()
        auto.stop()
        auto.start()
        assertTrue(auto.running)
        assertEquals(7, auto.level)
        assertEquals(AutoScroll.speedDpPerSecond(7) * 0.1f, auto.tick(0.1f, atEnd = false), 0.001f)
    }
}