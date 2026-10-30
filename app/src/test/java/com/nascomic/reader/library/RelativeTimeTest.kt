package com.nascomic.reader.library

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `一分钟内算刚刚`() {
        assertEquals(RelativeTime.Kind.JUST_NOW, RelativeTime.of(now, now).kind)
        assertEquals(RelativeTime.Kind.JUST_NOW, RelativeTime.of(now, now - 59_000).kind)
    }

    @Test
    fun `分钟与小时按整数取整`() {
        val m = RelativeTime.of(now, now - 5 * 60_000L)
        assertEquals(RelativeTime.Kind.MINUTES, m.kind)
        assertEquals(5L, m.amount)

        val h = RelativeTime.of(now, now - 3 * 3_600_000L)
        assertEquals(RelativeTime.Kind.HOURS, h.kind)
        assertEquals(3L, h.amount)
    }

    @Test
    fun `一周内说几天前，超过就给日期`() {
        val d = RelativeTime.of(now, now - 2 * 86_400_000L)
        assertEquals(RelativeTime.Kind.DAYS, d.kind)
        assertEquals(2L, d.amount)

        assertEquals(RelativeTime.Kind.DATE, RelativeTime.of(now, now - 8 * 86_400_000L).kind)
    }

    @Test
    fun `时间戳在未来时不出现负数`() {
        // 服务器时钟比本机快时会出现这种值，界面不能显示「-3 分钟前」
        assertEquals(RelativeTime.Kind.JUST_NOW, RelativeTime.of(now, now + 90_000).kind)
    }
}
