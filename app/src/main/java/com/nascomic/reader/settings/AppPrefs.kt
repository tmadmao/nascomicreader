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

    /** 上次检查更新的时间戳（毫秒）；0 = 从没查过 */
    var updateLastCheckAt: Long
        get() = sp.getLong(KEY_UPDATE_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_UPDATE_LAST_CHECK, value).apply()

    /** 用户点过「跳过此版本」的构建号；0 = 没跳过 */
    var updateIgnoredVersion: Long
        get() = sp.getLong(KEY_UPDATE_IGNORED, 0L)
        set(value) = sp.edit().putLong(KEY_UPDATE_IGNORED, value).apply()

    /** 自定义清单地址。空 = 用内置源；填了就只认这一个 */
    var updateManifestUrl: String
        get() = sp.getString(KEY_UPDATE_MANIFEST, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_MANIFEST, value.trim()).apply()

    /** 正在下载的任务 id；-1 = 没有。下载完成时要拿它比对广播里的 id */
    var updatePendingDownloadId: Long
        get() = sp.getLong(KEY_UPDATE_PENDING_ID, -1L)
        set(value) = sp.edit().putLong(KEY_UPDATE_PENDING_ID, value).apply()

    var updatePendingVersionCode: Long
        get() = sp.getLong(KEY_UPDATE_PENDING_CODE, 0L)
        set(value) = sp.edit().putLong(KEY_UPDATE_PENDING_CODE, value).apply()

    var updatePendingVersionName: String
        get() = sp.getString(KEY_UPDATE_PENDING_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_PENDING_NAME, value).apply()

    /** 这次下载声明的校验值。下载完成时进程可能被重启过，只能从这里取 */
    var updatePendingSha256: String
        get() = sp.getString(KEY_UPDATE_PENDING_SHA, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_PENDING_SHA, value).apply()

    /** 已下好并验过、等着安装的包路径；空 = 没有 */
    var updateReadyPath: String
        get() = sp.getString(KEY_UPDATE_READY_PATH, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_READY_PATH, value).apply()

    enum class ShelfSort(val value: Int) {
        RECENT(0),
        TITLE_ASC(1),
        TITLE_DESC(2),
        ;

        companion object {
            fun of(value: Int): ShelfSort = entries.firstOrNull { it.value == value } ?: RECENT
        }
    }

    /**
     * 屏幕方向。取值沿用旧库的顺序（0/1/2 不变），反向两档接在后面，
     * 这样升级后老用户存的 2 还是横屏，不会莫名其妙变成反向竖屏。
     */
    enum class Orientation(val value: Int) {
        SYSTEM(0),
        PORTRAIT(1),
        LANDSCAPE(2),
        REVERSE_PORTRAIT(3),
        REVERSE_LANDSCAPE(4),
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

        const val KEY_UPDATE_LAST_CHECK = "update.last_check_at"
        const val KEY_UPDATE_IGNORED = "update.ignored_version"
        const val KEY_UPDATE_MANIFEST = "update.manifest_url"
        const val KEY_UPDATE_PENDING_ID = "update.pending_id"
        const val KEY_UPDATE_PENDING_CODE = "update.pending_code"
        const val KEY_UPDATE_PENDING_NAME = "update.pending_name"
        const val KEY_UPDATE_PENDING_SHA = "update.pending_sha256"
        const val KEY_UPDATE_READY_PATH = "update.ready_path"
    }
}
