package com.nascomic.reader.settings

import android.content.Context

/**
 * 阅读参数。改了就生效，不做「批量编辑 + 保存」——阅读中调参是高频动作。
 *
 * 与 [AppPrefs] 用同一个偏好文件，key 前缀区分：`reader.*` 与 `ui.*`。
 * 参数还会继续增加，等超过十来项或需要按书绑定多套配置时再换 DataStore / 数据库，
 * 届时只改这里，调用方不动。
 */
class ReaderPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    /** 翻页方向：true = 点右侧看下一页（从左往右读）；false = 点左侧看下一页（从右往左读） */
    var flipForwardRight: Boolean
        get() = sp.getBoolean(KEY_FLIP_RIGHT, true)
        set(value) = sp.edit().putBoolean(KEY_FLIP_RIGHT, value).apply()

    /** 点击翻页：关掉之后点屏幕只呼出工具栏，不再翻页 */
    var tapToTurn: Boolean
        get() = sp.getBoolean(KEY_TAP_TURN, true)
        set(value) = sp.edit().putBoolean(KEY_TAP_TURN, value).apply()

    /** 双页并排：两页都是竖版时才并排 */
    var dualPage: Boolean
        get() = sp.getBoolean(KEY_DUAL_PAGE, false)
        set(value) = sp.edit().putBoolean(KEY_DUAL_PAGE, value).apply()

    /** 两侧边距（dp） */
    var sideMarginDp: Int
        get() = sp.getInt(KEY_MARGIN, 0)
        set(value) = sp.edit().putInt(KEY_MARGIN, value.coerceIn(0, 32)).apply()

    /** 画布底色，取值见 [Canvas] */
    var canvas: Int
        get() = sp.getInt(KEY_CANVAS, Canvas.DARK.value)
        set(value) = sp.edit().putInt(KEY_CANVAS, value).apply()

    /** 裁白边：把页面四周的白边切掉，只显示有内容的部分 */
    var trimBorder: Boolean
        get() = sp.getBoolean(KEY_TRIM, false)
        set(value) = sp.edit().putBoolean(KEY_TRIM, value).apply()

    /** 裁白边的白度下限：越低裁得越狠，越高越保守 */
    var trimLevel: Int
        get() = sp.getInt(KEY_TRIM_LEVEL, 236)
        set(value) = sp.edit().putInt(KEY_TRIM_LEVEL, value.coerceIn(200, 250)).apply()

    /** 跳过空白页 */
    var skipBlank: Boolean
        get() = sp.getBoolean(KEY_SKIP_BLANK, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_BLANK, value).apply()

    /** 阅读时屏幕常亮 */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_ON, true)
        set(value) = sp.edit().putBoolean(KEY_KEEP_ON, value).apply()

    /** 跨页拆分：宽图按左右两半分别显示 */
    var splitWide: Boolean
        get() = sp.getBoolean(KEY_SPLIT, false)
        set(value) = sp.edit().putBoolean(KEY_SPLIT, value).apply()

    /** 页面上是否显示页码与章名 */
    var showPageLabel: Boolean
        get() = sp.getBoolean(KEY_PAGE_LABEL, true)
        set(value) = sp.edit().putBoolean(KEY_PAGE_LABEL, value).apply()

    /** 预载页数：阅读时往后预取几页，0 = 不预载 */
    var prefetchPages: Int
        get() = sp.getInt(KEY_PREFETCH, 3)
        set(value) = sp.edit().putInt(KEY_PREFETCH, value.coerceIn(0, 12)).apply()

    /** 内存缓存上限（MB）。0 = 自动，按堆的四分之一 */
    var cacheMb: Int
        get() = sp.getInt(KEY_CACHE_MB, 0)
        set(value) = sp.edit().putInt(KEY_CACHE_MB, value.coerceAtLeast(0)).apply()

    /** 内存缓存实际可用字节数：自动档取堆的四分之一，手选的档位夹在堆的 1/8 到 1/2 之间 */
    fun cacheBudgetBytes(): Long {
        val heap = Runtime.getRuntime().maxMemory()
        val mb = cacheMb
        if (mb <= 0) return heap / 4
        return (mb * 1024L * 1024L).coerceIn(heap / 8, heap / 2)
    }

    /** 翻页时页面淡入，默认关闭 */
    var turnAnimation: Boolean
        get() = sp.getBoolean(KEY_TURN_ANIM, false)
        set(value) = sp.edit().putBoolean(KEY_TURN_ANIM, value).apply()

    enum class Canvas(val value: Int) {
        LIGHT(0),
        DARK(1),
        BLACK(2),
        ;

        companion object {
            fun of(value: Int): Canvas = entries.firstOrNull { it.value == value } ?: DARK
        }
    }

    private companion object {
        const val KEY_FLIP_RIGHT = "reader.flip_forward_right"
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
        const val KEY_CACHE_MB = "reader.cache_mb"
        const val KEY_TURN_ANIM = "reader.turn_animation"
    }
}
