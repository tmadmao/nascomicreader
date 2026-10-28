package com.nascomic.reader.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「多久以前」的分档。只算差值与档位，具体文案交给界面按资源拼——
 * 这样不用跟着系统语言跑，界面是中文就一直是中文。
 */
object RelativeTime {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    enum class Kind {
        /** 一分钟内，或时间戳在未来（服务器时间偏了也会走到这） */
        JUST_NOW,
        MINUTES,
        HOURS,
        DAYS,

        /** 超过一周就给日期，不再说「几天前」 */
        DATE,
    }

    data class Shown(val kind: Kind, val amount: Long = 0)

    fun of(now: Long, at: Long): Shown {
        val diff = now - at
        return when {
            diff < MINUTE -> Shown(Kind.JUST_NOW)
            diff < HOUR -> Shown(Kind.MINUTES, diff / MINUTE)
            diff < DAY -> Shown(Kind.HOURS, diff / HOUR)
            diff < 7 * DAY -> Shown(Kind.DAYS, diff / DAY)
            else -> Shown(Kind.DATE)
        }
    }

    /** 归档日期，`2026-10-26` */
    fun dateOf(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(at))
}
