package com.nascomic.reader.settings

import android.content.Context

/**
 * 密码这类不该明文进数据库的东西。
 *
 * 用应用私有的 SharedPreferences：文件只对应用自己可读（未 root 的设备上）。
 * v1 先到这里；真要更强的保护得上 EncryptedSharedPreferences 或 Keystore，
 * 那时只需换本类实现，调用方不动。
 */
class SecretStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun put(key: String, value: String?) {
        sp.edit().apply {
            if (value.isNullOrEmpty()) remove(key) else putString(key, value)
        }.apply()
    }

    fun get(key: String?): String? = if (key.isNullOrEmpty()) null else sp.getString(key, null)

    fun remove(key: String?) {
        if (!key.isNullOrEmpty()) sp.edit().remove(key).apply()
    }
}
