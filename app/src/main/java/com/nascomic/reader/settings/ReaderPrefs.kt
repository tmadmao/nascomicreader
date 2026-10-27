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

    /** 页面上是否显示页码与章名 */
    var showPageLabel: Boolean
        get() = sp.getBoolean(KEY_PAGE_LABEL, true)
        set(value) = sp.edit().putBoolean(KEY_PAGE_LABEL, value).apply()

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
        const val KEY_DUAL_PAGE = "reader.dual_page"
        const val KEY_MARGIN = "reader.side_margin_dp"
        const val KEY_CANVAS = "reader.canvas"
        const val KEY_PAGE_LABEL = "reader.show_page_label"
    }
}
