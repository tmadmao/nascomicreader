package com.nascomic.reader.settings

import android.content.Context

/**
 * 应用级偏好。与具体书无关、也不属于阅读参数的那些开关放这里。
 *
 * 用 SharedPreferences 而不是 DataStore：当前只有个位数开关，引入 DataStore 的依赖与样板代码
 * 不划算。等阅读参数（几十项、要类型安全、要迁移）进来时再换成 DataStore，届时这里做一层适配即可。
 *
 * key 命名见《数据模型与命名规范》§4：`<域>.<语义>`。
 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    /** 跳过详情页，点书直接继续阅读 */
    var skipDetail: Boolean
        get() = sp.getBoolean(KEY_SKIP_DETAIL, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_DETAIL, value).apply()

    /** 屏幕方向，取值见 [Orientation] */
    var orientation: Int
        get() = sp.getInt(KEY_ORIENTATION, Orientation.SYSTEM.value)
        set(value) = sp.edit().putInt(KEY_ORIENTATION, value).apply()

    /** 详情页目录倒序：章多的书常从最新一话看起 */
    var tocDescending: Boolean
        get() = sp.getBoolean(KEY_TOC_DESCENDING, false)
        set(value) = sp.edit().putBoolean(KEY_TOC_DESCENDING, value).apply()

    /** 书架排序：0 最近添加 / 1 名称升 / 2 名称降 */
    var shelfSort: Int
        get() = sp.getInt(KEY_SHELF_SORT, 0)
        set(value) = sp.edit().putInt(KEY_SHELF_SORT, value.coerceIn(0, 2)).apply()

    /** 书架列数：0 = 按屏宽自动，其余为固定列数 */
    var shelfColumns: Int
        get() = sp.getInt(KEY_SHELF_COLUMNS, 0)
        set(value) = sp.edit().putInt(KEY_SHELF_COLUMNS, value.coerceAtLeast(0)).apply()

    enum class ShelfSort(val value: Int) {
        RECENT(0),
        TITLE_ASC(1),
        TITLE_DESC(2),
        ;

        companion object {
            fun of(value: Int): ShelfSort = entries.firstOrNull { it.value == value } ?: RECENT
        }
    }

    enum class Orientation(val value: Int) {
        SYSTEM(0),
        PORTRAIT(1),
        LANDSCAPE(2),
        ;

        companion object {
            fun of(value: Int): Orientation = entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }

    private companion object {
        const val KEY_SKIP_DETAIL = "ui.skip_detail"
        const val KEY_ORIENTATION = "ui.orientation"
        const val KEY_TOC_DESCENDING = "detail.toc_descending"
        const val KEY_SHELF_SORT = "ui.shelf_sort"
        const val KEY_SHELF_COLUMNS = "ui.shelf_columns"
    }
}
