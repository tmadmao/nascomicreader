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

    /**
     * 双页组屏：0 = 关；1 = 首张单页（第 1 页单独占右格）；2 = 末张单页；3 = 每章首张单页。
     * 只有 [dualPage] 开着时才有意义。
     */
    var spreadOrder: Int
        get() = int(KEY_SPREAD_ORDER, 0)
        set(value) = putInt(KEY_SPREAD_ORDER, value.coerceIn(0, 3))

    /** 双页左右顺序：0 左→右，1 右→左（左右互换，占位格不换） */
    var spreadDirection: Int
        get() = int(KEY_SPREAD_DIRECTION, 0)
        set(value) = putInt(KEY_SPREAD_DIRECTION, value.coerceIn(0, 1))

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

    /** 裁边算法：0 快（隔行采样）/ 1 精（逐行） */
    var trimAlgo: Int
        get() = int(KEY_TRIM_ALGO, 0)
        set(value) = putInt(KEY_TRIM_ALGO, value.coerceIn(0, 1))

    /** 只裁上下、不动左右——条漫裁完两侧常对不齐 */
    var trimIgnoreSides: Boolean
        get() = bool(KEY_TRIM_IGNORE_SIDES, false)
        set(value) = putBool(KEY_TRIM_IGNORE_SIDES, value)

    /** 识别前四边各忽略多少像素（对付扫描件的细黑边） */
    var trimIgnorePx: Int
        get() = int(KEY_TRIM_IGNORE_PX, 0)
        set(value) = putInt(KEY_TRIM_IGNORE_PX, value.coerceIn(0, 30))

    /** 单边裁剪量不超过边长 10% 的保护，默认开 */
    var trimProtect: Boolean
        get() = bool(KEY_TRIM_PROTECT, true)
        set(value) = putBool(KEY_TRIM_PROTECT, value)

    /**
     * 跳过空白页：0 = 关；N > 0 = 从第 N 张空白页起，之后每隔一张跳过。
     *
     * 旧版是「一直往后跳」，清屏页多的书会一下跳过好几页。现在按规格做成间隔一张。
     */
    var skipBlankFrom: Int
        get() = int(KEY_SKIP_BLANK_FROM, if (bool(KEY_SKIP_BLANK, false)) 1 else 0)
        set(value) = putInt(KEY_SKIP_BLANK_FROM, value.coerceIn(0, 50))

    val skipBlank: Boolean get() = skipBlankFrom > 0

    /** 阅读时屏幕常亮 */
    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_ON, true)
        set(value) = putBool(KEY_KEEP_ON, value)

    /**
     * 跨页拆分：0 = 关（整页），1 = 拆开后先看左半，2 = 先看右半。
     * 右开本的双页大图从右往左读，所以方向得能选。
     */
    var splitMode: Int
        get() = int(KEY_SPLIT_MODE, if (bool(KEY_SPLIT, false)) 1 else 0)
        set(value) = putInt(KEY_SPLIT_MODE, value.coerceIn(0, 2))

    /** 拆分后的第一半：0 左 / 1 右 */
    val splitFirstHalf: Int get() = if (splitMode == 2) 1 else 0

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

    /** 为打开格式落下的本地副本上限（GB）：7z / RAR / 远程 PDF 整份落地用 */
    var copyCacheGb: Int
        get() = int(KEY_COPY_GB, 2)
        set(value) = putInt(KEY_COPY_GB, value.coerceIn(1, 8))

    /** 32 位颜色解码：更细腻，但一张图多占一倍内存 */
    var argb8888: Boolean
        get() = bool(KEY_ARGB, false)
        set(value) = putBool(KEY_ARGB, value)

    /** 翻页动画：默认关闭（翻页即刻到位）。打开后按阅读方向做位移过渡 */
    var turnAnimation: Boolean
        get() = bool(KEY_TURN_ANIM, false)
        set(value) = putBool(KEY_TURN_ANIM, value)

    /** 翻页动画时长（ms）。0 = 用内置默认 160 */
    var flipAnimMs: Int
        get() = int(KEY_FLIP_ANIM_MS, 0)
        set(value) = putInt(KEY_FLIP_ANIM_MS, value.coerceIn(0, 600))

    /** 滑动翻页的方向容差（0–9）：越大越要求手势接近水平 */
    var flingResistance: Int
        get() = int(KEY_FLING_RESISTANCE, 0)
        set(value) = putInt(KEY_FLING_RESISTANCE, value.coerceIn(0, 9))

    /** 惯性动量（0–9）：越大同样力度滑得越远，但一次 fling 最多翻一页 */
    var flingMomentum: Int
        get() = int(KEY_FLING_MOMENTUM, 1)
        set(value) = putInt(KEY_FLING_MOMENTUM, value.coerceIn(0, 9))

    /** 分区点击：0 关 / 1 左右两区 / 2 对角（左下↔右上）/ 3 对角（左上↔右下） */
    var tapFlipMode: Int
        get() = int(KEY_TAP_FLIP_MODE, if (bool(KEY_TAP_TURN, true)) 1 else 0)
        set(value) = putInt(KEY_TAP_FLIP_MODE, value.coerceIn(0, 3))

    /** 左右两区动作互换（分区线不动，只是左右干的活对调） */
    var tapFlipSwap: Boolean
        get() = bool(KEY_TAP_FLIP_SWAP, false)
        set(value) = putBool(KEY_TAP_FLIP_SWAP, value)

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

    /** EPUB 快速解析：只按书脊取页；关掉会扫全部图片兜底（不规范 EPUB 用得上，代价是慢） */
    var fastEpub: Boolean
        get() = bool(KEY_FAST_EPUB, true)
        set(value) = putBool(KEY_FAST_EPUB, value)

    /** PDF 渲染质量：目标宽 = 屏宽 × 值 / 8，0–30。值越大越清晰，也越吃内存 */
    var pdfRenderQuality: Int
        get() = int(KEY_PDF_QUALITY, 8)
        set(value) = putInt(KEY_PDF_QUALITY, value.coerceIn(0, 30))

    /** 压缩包内有子目录时是否全加载：关掉只读根目录那层图片 */
    var loadAllInArchive: Boolean
        get() = bool(KEY_LOAD_ALL_IN_ARCHIVE, true)
        set(value) = putBool(KEY_LOAD_ALL_IN_ARCHIVE, value)

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
        const val KEY_FLIP_ANIM_MS = "reader.flip_anim_ms"
        const val KEY_FLING_RESISTANCE = "reader.fling_resistance"
        const val KEY_FLING_MOMENTUM = "reader.fling_momentum"
        const val KEY_TAP_FLIP_MODE = "reader.tap_flip_mode"
        const val KEY_TAP_FLIP_SWAP = "reader.tap_flip_swap"
        const val KEY_SPREAD_ORDER = "reader.spread_order"
        const val KEY_SPREAD_DIRECTION = "reader.spread_direction"
        const val KEY_SPLIT_MODE = "reader.split_mode"
        const val KEY_TRIM_ALGO = "reader.trim_algo"
        const val KEY_TRIM_IGNORE_SIDES = "reader.trim_ignore_sides"
        const val KEY_TRIM_IGNORE_PX = "reader.trim_ignore_px"
        const val KEY_TRIM_PROTECT = "reader.trim_protect"
        const val KEY_SKIP_BLANK_FROM = "reader.skip_blank_from"
        const val KEY_COPY_GB = "reader.copy_cache_gb"
        const val KEY_FAST_EPUB = "reader.fast_epub"
        const val KEY_PDF_QUALITY = "reader.pdf_quality"
        const val KEY_LOAD_ALL_IN_ARCHIVE = "reader.load_all_in_archive"

        /** 默认「上一页 / 下一页」= 音量上 / 音量下 */
        const val DEFAULT_KEY_PREV = 24
        const val DEFAULT_KEY_NEXT = 25

        /** 磁盘缓存的自动档：可用空间的二十分之一，夹在 1–5 GB */
        private const val MIN_DISK_BYTES = 1L * 1024 * 1024 * 1024
        const val AUTO_DISK_BYTES = 5L * 1024 * 1024 * 1024
    }
}
