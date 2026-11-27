package com.nascomic.reader.ui.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteUndoTest {

    private val t0 = 1_000_000L

    @Test
    fun `窗口内撤销把整批放回`() {
        val undo = DeleteUndo()
        undo.begin(listOf(7L, 8L), t0)

        val restored = undo.undo(t0 + 3_000L)

        assertEquals(listOf(7L, 8L), restored)
        assertTrue(undo.pending.isEmpty())
    }

    @Test
    fun `窗口一过撤销就失效`() {
        val undo = DeleteUndo()
        undo.begin(listOf(7L), t0)

        assertEquals(emptyList<Long>(), undo.undo(t0 + DeleteUndo.WINDOW_MS))
        assertEquals(listOf(7L), undo.pending)
    }

    @Test
    fun `到点取走待删并清空状态`() {
        val undo = DeleteUndo()
        undo.begin(listOf(1L, 2L), t0)

        assertEquals(emptyList<Long>(), undo.due(t0 + 4_999L))
        assertEquals(listOf(1L, 2L), undo.due(t0 + 5_000L))
        assertTrue(undo.pending.isEmpty())
    }

    @Test
    fun `表提前响时按剩余时间重排而不是丢掉待删`() {
        val undo = DeleteUndo()
        undo.begin(listOf(1L), t0)

        // 定时器与窗口判断可能出现毫秒级错位：due 为空时剩余必须仍可取到
        assertEquals(emptyList<Long>(), undo.due(t0 + 1_000L))
        assertEquals(4_000L, undo.remaining(t0 + 1_000L))
        assertEquals(listOf(1L), undo.pending)
    }

    @Test
    fun `到点之后再 begin 不会带出上一批`() {
        val undo = DeleteUndo()
        undo.begin(listOf(1L), t0)
        undo.due(t0 + DeleteUndo.WINDOW_MS)

        undo.begin(listOf(2L), t0 + 6_000L)

        assertEquals(listOf(2L), undo.pending)
    }

    @Test
    fun `连续删除并进同一个窗口且去重`() {
        val undo = DeleteUndo()
        undo.begin(listOf(1L), t0)
        undo.begin(listOf(2L, 1L), t0 + 2_000L)

        assertEquals(listOf(1L, 2L), undo.pending)
        // 窗口从第二次算起：第一次那一批也跟着延长
        assertEquals(DeleteUndo.WINDOW_MS, undo.remaining(t0 + 2_000L))
    }

    @Test
    fun `离开书架立刻收尾`() {
        val undo = DeleteUndo()
        undo.begin(listOf(5L), t0)

        assertEquals(listOf(5L), undo.flush())
        assertTrue(undo.pending.isEmpty())
        assertEquals(emptyList<Long>(), undo.flush())
    }

    @Test
    fun `没有待删时不倒数`() {
        val undo = DeleteUndo()
        assertEquals(0L, undo.remaining(t0))
        undo.begin(emptyList(), t0)
        assertEquals(0L, undo.remaining(t0))
    }
}
