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
    }
}
