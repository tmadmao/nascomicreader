package com.nascomic.reader.update

import org.json.JSONObject

/**
 * 更新清单：一个 JSON 说清「最新一版是哪个、装哪个文件、怎么确认没被换过」。
 *
 * 不拿平台接口查版本：Gitee 匿名接口 60 次/小时，按出口 IP 算，同一个 NAT 后面几个人
 * 就把额度分完了；而且接口改字段客户端就得跟着发版。静态文件可以被缓存、可以换源、
 * 也可以随时换一个托管平台，代价只是一行地址。
 *
 * 字段只增不改：老客户端读到不认识的键会忽略，新客户端遇到缺字段会用默认值兜住。
 */
data class UpdateManifest(
    /** 构建号。比大小一律用它，`versionName` 只负责显示 */
    val versionCode: Long,
    val versionName: String,
    /** 安装包地址，只接受 https */
    val apkUrl: String,
    /** 字节数；拿不到就填 0，只影响界面上的显示 */
    val apkSize: Long,
    /** 小写十六进制 SHA-256 */
    val sha256: String,
    /** 更新说明，原样显示 */
    val notes: String,
    /** 低于这个构建号的必须升级；0 表示不强制 */
    val minVersionCode: Long,
    /** 发布页。安装包取不到时给用户一个落脚处 */
    val pageUrl: String,
) {

    companion object {

        private const val HEX = "0123456789abcdef"

        /**
         * 解析。任何一种不合格都返回 null——清单是外来数据，宁可当成「没有更新」，
         * 也不要拿着半截字段去下载安装。
         */
        fun parse(text: String): UpdateManifest? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val apk = root.optJSONObject("apk") ?: return null

            val code = root.optLong("versionCode", 0L)
            if (code <= 0L) return null

            // 只认 https：清单本身可能被中间人替换，明文地址等于把这一层放弃掉
            val url = apk.optString("url").trim()
            if (!url.startsWith("https://")) return null

            // 校验值格式先卡死：长度不对的 sha256 只会让后面每次下载都白跑一遍
            val sha = apk.optString("sha256").trim().lowercase()
            if (sha.length != 64 || sha.any { it !in HEX }) return null

            return UpdateManifest(
                versionCode = code,
                versionName = root.optString("versionName").trim().ifBlank { code.toString() },
                apkUrl = url,
                apkSize = apk.optLong("size", 0L),
                sha256 = sha,
                notes = root.optString("notes").trim(),
                minVersionCode = root.optLong("minVersionCode", 0L),
                pageUrl = root.optString("pageUrl").trim(),
            )
        }
    }
}
