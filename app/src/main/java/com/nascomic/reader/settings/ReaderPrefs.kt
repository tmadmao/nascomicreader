package com.nascomic.reader.settings

import android.content.Context

/**
 * 阅读参数。改了就生效，不做「批量编辑 + 保存」——阅读中调参是高频动作。
 *
 * 与 [AppPrefs] 用同一个偏好文件，key 前缀区分：`reader.*` 与 `ui.*`。
 *
 * 传了 [bookId] 就是「这本书的配置」：读的时候先找 `book.<id>.<key>`，
 * 没有才回落到全局值；写的时候只写这本书的那一份。不传就是全局默认。
 */
class ReaderPrefs(context: Context, private val bookId: Long = 0L) {

    private val sp = context.applicationContext
        .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    /** 这本书是否单独调过参数 */
    fun hasBookOverrides(): Boolean =
        bookId > 0 && sp.all.keys.any { it.startsWith(bookPrefix) }

    /** 清掉这本书的单独设置，回到全局默认 */
    fun clearBookOverrides() {
        if (bookId <= 0) return
        val edit = sp.edit()
        sp.all.keys.filter { it.startsWith(bookPrefix) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private val bookPrefix: String get() = "book.$bookId."

    private fun key(base: String): String = if (bookId > 0) bookPrefix + base else base

    private fun bool(base: String, def: Boolean): Boolean =
        sp.getBoolean(key(base), sp.getBoolean(base, def))

    private fun putBool(base: String, value: Boolean) =
        sp.edit().putBoolean(key(base), value).apply()

    private fun int(base: String, def: Int): Int =
        sp.getInt(key(base), sp.getInt(base, def))

    private fun putInt(base: String, value: Int) =
        sp.edit().putInt(key(base), value).apply()

    private fun float(base: String, def: Float): Float =
        sp.getFloat(key(base), sp.getFloat(base, def))

    private fun putFloat(base: String, value: Float) =
        sp.edit().putFloat(key(base), value).apply()

    /**
     * 阅读方向：0 竖向连贯 / 1 横向·点右侧下一页 / 2 横向·点左侧下一页。
     * 横向两种是同一件事的两个方向，「分区互换」不再单列一个开关。
     */
    var scrollMode: Int
        get() = int(KEY_SCROLL_MODE, 1)
        set(value) = putInt(KEY_SCROLL_MODE, value.coerceIn(0, 2))

    /** 竖向连贯滚动：不吃翻页手势，靠上下滑 */
    val vertical: Boolean get() = scrollMode == 0

    /** 横向时点右侧是下一页；竖向模式返回 true 只为了上滑语义统一 */
    val forwardRight: Boolean get() = scrollMode != 2

    /** 点击翻页：关掉之后点屏幕只呼出工具栏，不再翻页 */
    var tapToTurn: Boolean
        get() = bool(KEY_TAP_TURN, true)
        set(value) = putBool(KEY_TAP_TURN, value)

    /** 双页并排：两页都是竖版时才并排 */
    var dualPage: Boolean
        get() = bool(KEY_DUAL_PAGE, false)
        set(value) = putBool(KEY_DUAL_PAGE, value)

    /** 两侧边距（dp） */
    var sideMarginDp: Int
        get() = int(KEY_MARGIN, 0)
        set(value) = putInt(KEY_MARGIN, value.coerceIn(0, 25))

    /** 画布底色，取值见 [Canvas] */
    var canvas: Int
        get() = int(KEY_CANVAS, Canvas.DARK.value)
        set(value) = putInt(KEY_CANVAS, value)

    /** 裁白边：把页面四周的白边切掉，只显示有内容的部分 */
    var trimBorder: Boolean
        get() = bool(KEY_TRIM, false)
        set(value) = putBool(KEY_TRIM, value)

    /** 裁白边的白度下限：越低裁得越狠，越高越保守 */
    var trimLevel: Int
        get() = int(KEY_TRIM_LEVEL, 236)
        set(value) = putInt(KEY_TRIM_LEVEL, value.coerceIn(200, 250))

    /** 跳过空白页 */
    var skipBlank: Boolean
        get() = bool(KEY_SKIP_BLANK, false)
        set(value) = putBool(KEY_SKIP_BLANK, value)

    /** 阅读时屏幕常亮 */
    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_ON, true)
        set(value) = putBool(KEY_KEEP_ON, value)

    /** 跨页拆分：宽图按左右两半分别显示 */
    var splitWide: Boolean
        get() = bool(KEY_SPLIT, false)
        set(value) = putBool(KEY_SPLIT, value)

    /** 页面上是否显示页码与章名 */
    var showPageLabel: Boolean
        get() = bool(KEY_PAGE_LABEL, true)
        set(value) = putBool(KEY_PAGE_LABEL, value)

    /**
     * 往后预载几页：翻过去之前先解好，翻页不用等。
     * 竖排也用它决定窗口往后接几张。
     */
    var prefetchNext: Int
        get() = int(KEY_PREFETCH, 3)
        set(value) = putInt(KEY_PREFETCH, value.coerceIn(0, 12))

    /** 往前预载几页：回翻不用重解。默认 0——回翻比正翻少得多，留着占内存不值 */
    var prefetchPrev: Int
        get() = int(KEY_PREFETCH_PREV, 0)
        set(value) = putInt(KEY_PREFETCH_PREV, value.coerceIn(0, 12))

    /** 内存缓存上限（MB）。0 = 自动，按堆的四分之一 */
    var cacheMb: Int
        get() = int(KEY_CACHE_MB, 0)
        set(value) = putInt(KEY_CACHE_MB, value.coerceAtLeast(0))

    /** 磁盘页缓存上限（GB）。0 = 自动，按可用空间的二十分之一，夹在 1–5 GB */
    var cacheGb: Int
        get() = int(KEY_CACHE_GB, 0)
        set(value) = putInt(KEY_CACHE_GB, value.coerceIn(0, 5))

    /** 内存缓存实际可用字节数：自动档取堆的四分之一，手选的档位夹在堆的 1/8 到 1/2 之间 */
    fun cacheBudgetBytes(): Long {
        val heap = Runtime.getRuntime().maxMemory()
        val mb = cacheMb
        if (mb <= 0) return heap / 4
        return (mb * 1024L * 1024L).coerceIn(heap / 8, heap / 2)
    }

    /**
     * 磁盘页缓存的上限。
     *
     * 自动档按可用空间的二十分之一给，夹在 1–5 GB：手机剩 100 GB 时给 5 GB，
     * 剩 20 GB 时给 1 GB。一页按 0.4 MB 算，5 GB 约等于一万多页——够放十几本书的整本，
     * 隔一周回来重读还在。
     */
    fun diskBudgetBytes(available: Long = 0L): Long {
        val gb = cacheGb
        if (gb > 0) return gb * 1024L * 1024L * 1024L
        if (available <= 0L) return AUTO_DISK_BYTES
        return (available / 20).coerceIn(MIN_DISK_BYTES, AUTO_DISK_BYTES)
    }

    /** 32 位颜色解码：更细腻，但一张图多占一倍内存 */
    var argb8888: Boolean
        get() = bool(KEY_ARGB, false)
        set(value) = putBool(KEY_ARGB, value)

    /** 翻页时页面淡入，默认关闭 */
    var turnAnimation: Boolean
        get() = bool(KEY_TURN_ANIM, false)
        set(value) = putBool(KEY_TURN_ANIM, value)

    /**
     * 双击放大。只在横向阅读时生效——竖向连贯滚动里双击只切工具栏，
     * 否则「下滑看后面」会被缩放吞掉。
     */
    var doubleZoom: Boolean
        get() = bool(KEY_DOUBLE_ZOOM, true)
        set(value) = putBool(KEY_DOUBLE_ZOOM, value)

    /** 双击放大的硬倍率 */
    var doubleZoomFactor: Float
        get() = float(KEY_DOUBLE_ZOOM_FACTOR, 1.5f)
        set(value) = putFloat(KEY_DOUBLE_ZOOM_FACTOR, value.coerceIn(1.2f, 3f))

    /** 双击放大后的水平焦点：0 点击点 / 1 点击点移到屏幕中心 / 2 页面中心 / 3 左上 / 4 右上 */
    var doubleZoomFocusH: Int
        get() = int(KEY_DOUBLE_ZOOM_FOCUS_H, 0)
        set(value) = putInt(KEY_DOUBLE_ZOOM_FOCUS_H, value.coerceIn(0, 4))

    /** 双击放大后的垂直焦点，枚举同水平 */
    var doubleZoomFocusV: Int
        get() = int(KEY_DOUBLE_ZOOM_FOCUS_V, 0)
        set(value) = putInt(KEY_DOUBLE_ZOOM_FOCUS_V, value.coerceIn(0, 4))

    /** 长按放大，默认关闭。关掉时长按不产生任何缩放 */
    var holdZoom: Boolean
        get() = bool(KEY_HOLD_ZOOM, false)
        set(value) = putBool(KEY_HOLD_ZOOM, value)

    /** 长按放大后的焦点，枚举同双击 */
    var holdZoomFocus: Int
        get() = int(KEY_HOLD_ZOOM_FOCUS, 0)
        set(value) = putInt(KEY_HOLD_ZOOM_FOCUS, value.coerceIn(0, 4))

    /** 长按放大的倍率 */
    var holdZoomFactor: Float
        get() = float(KEY_HOLD_ZOOM_FACTOR, 1.5f)
        set(value) = putFloat(KEY_HOLD_ZOOM_FACTOR, value.coerceIn(1.2f, 3f))

    /** 长按放大态下拖动位移的倍数：手指移 1px，内容移 N px */
    var holdZoomScrollFactor: Int
        get() = int(KEY_HOLD_ZOOM_SCROLL, 4)
        set(value) = putInt(KEY_HOLD_ZOOM_SCROLL, value.coerceIn(1, 9))

    /** 双指缩放整页 */
    var pinchZoom: Boolean
        get() = bool(KEY_PINCH_ZOOM, true)
        set(value) = putBool(KEY_PINCH_ZOOM, value)

    /** 页面适应：true = 按宽度铺满（长图上下可拖），false = 整页可见 */
    var fitWidth: Boolean
        get() = bool(KEY_FIT_WIDTH, true)
        set(value) = putBool(KEY_FIT_WIDTH, value)

    /** 原尺寸解码：开 → 解码不按屏幕降采样，放大后更清晰；代价是内存 */
    var nativeDecode: Boolean
        get() = bool(KEY_NATIVE_DECODE, false)
        set(value) = putBool(KEY_NATIVE_DECODE, value)

    /**
     * 跳进距离：0 = 一屏内（长图先滚一屏再看下一页）、1 = 半屏、2 = 整屏。
     * 只有页面高于屏幕时才看得出区别，普通单页一屏装得下就直接翻页。
     */
    var flipStep: Int
        get() = int(KEY_FLIP_STEP, 0)
        set(value) = putInt(KEY_FLIP_STEP, value.coerceIn(0, 2))

    /** 音量键翻页。关掉就还给系统音量 */
    var keyFlip: Boolean
        get() = bool(KEY_KEY_FLIP, false)
        set(value) = putBool(KEY_KEY_FLIP, value)

    /** 自定义「上一页」键，默认音量上 */
    var keyPrevCode: Int
        get() = int(KEY_KEY_PREV, DEFAULT_KEY_PREV)
        set(value) = putInt(KEY_KEY_PREV, value)

    /** 自定义「下一页」键，默认音量下 */
    var keyNextCode: Int
        get() = int(KEY_KEY_NEXT, DEFAULT_KEY_NEXT)
        set(value) = putInt(KEY_KEY_NEXT, value)

    /** 鼠标滚轮映射：0 关 / 1 向上=上一页 / 2 向上=下一页 */
    var wheelMode: Int
        get() = int(KEY_WHEEL, 0)
        set(value) = putInt(KEY_WHEEL, value.coerceIn(0, 2))

    /** 进阅读时标出左右分区的分界，约 2.5 秒后消失 */
    var zoneHint: Boolean
        get() = bool(KEY_ZONE_HINT, false)
        set(value) = putBool(KEY_ZONE_HINT, value)

    enum class Canvas(val value: Int) {
        SYSTEM(3),
        LIGHT(0),
        DARK(1),
        BLACK(2),
        ;

        companion object {
            fun of(value: Int): Canvas = entries.firstOrNull { it.value == value } ?: DARK
        }
    }

    private companion object {
        const val KEY_SCROLL_MODE = "reader.scroll_mode"
        const val KEY_TAP_TURN = "reader.tap_to_turn"
        const val KEY_DUAL_PAGE = "reader.dual_page"
        const val KEY_MARGIN = "reader.side_margin_dp"
        const val KEY_CANVAS = "reader.canvas"
        const val KEY_PAGE_LABEL = "reader.show_page_label"
        const val KEY_TRIM = "reader.trim_border"
        const val KEY_SPLIT = "reader.split_wide"
        const val KEY_TRIM_LEVEL = "reader.trim_level"
        const val KEY_SKIP_BLANK = "reader.skip_blank"
        const val KEY_KEEP_ON = "reader.keep_screen_on"
        const val KEY_PREFETCH = "reader.prefetch_pages"
        const val KEY_PREFETCH_PREV = "reader.prefetch_prev"
        const val KEY_CACHE_MB = "reader.cache_mb"
        const val KEY_CACHE_GB = "reader.cache_gb"
        const val KEY_ARGB = "reader.argb_8888"
        const val KEY_TURN_ANIM = "reader.turn_animation"
        const val KEY_DOUBLE_ZOOM = "reader.double_zoom"
        const val KEY_DOUBLE_ZOOM_FACTOR = "reader.double_zoom_factor"
        const val KEY_DOUBLE_ZOOM_FOCUS_H = "reader.double_zoom_focus_h"
        const val KEY_DOUBLE_ZOOM_FOCUS_V = "reader.double_zoom_focus_v"
        const val KEY_HOLD_ZOOM = "reader.hold_zoom"
        const val KEY_HOLD_ZOOM_FOCUS = "reader.hold_zoom_focus"
        const val KEY_HOLD_ZOOM_FACTOR = "reader.hold_zoom_factor"
        const val KEY_HOLD_ZOOM_SCROLL = "reader.hold_zoom_scroll"
        const val KEY_PINCH_ZOOM = "reader.pinch_zoom"
        const val KEY_FIT_WIDTH = "reader.fit_width"
        const val KEY_NATIVE_DECODE = "reader.native_decode"
        const val KEY_FLIP_STEP = "reader.flip_step"
        const val KEY_KEY_FLIP = "reader.key_flip"
        const val KEY_KEY_PREV = "reader.key_prev_code"
        const val KEY_KEY_NEXT = "reader.key_next_code"
        const val KEY_WHEEL = "reader.wheel_mode"
        const val KEY_ZONE_HINT = "reader.zone_hint"

        /** 默认「上一页 / 下一页」= 音量上 / 音量下 */
        const val DEFAULT_KEY_PREV = 24
        const val DEFAULT_KEY_NEXT = 25

        /** 磁盘缓存的自动档：可用空间的二十分之一，夹在 1–5 GB */
        private const val MIN_DISK_BYTES = 1L * 1024 * 1024 * 1024
        const val AUTO_DISK_BYTES = 5L * 1024 * 1024 * 1024
    }
}
