package com.nascomic.reader.smb

/**
 * 一个 SMB 地址拆出来的四段：主机（含端口）、共享名、共享内的路径。
 *
 * 支持的写法（用户从 NAS 后台抄地址时形态各异，都收）：
 * ```
 * smb://192.168.1.10/漫画/单行本        ← 最常见
 * smb://192.168.1.10:1445/漫画
 * //192.168.1.10/漫画/单行本            ← 资源管理器里复制来的
 * 192.168.1.10/漫画                     ← 少打协议头
 * ```
 * 只认 SMB2/3（默认 445 端口）。
 */
data class SmbTarget(
    val host: String,
    val port: Int,
    val share: String,
    /** 共享内的路径，无前导斜杠；共享根为空串 */
    val path: String,
) {
    /** 共享内的完整路径，SMB 这边要的是不带前导斜杠的形式 */
    fun pathOf(relative: String): String =
        if (path.isEmpty()) relative else "$path/$relative"

    companion object {
        const val DEFAULT_PORT = 445

        fun parse(raw: String): SmbTarget {
            var s = raw.trim()
            if (s.isEmpty()) throw IllegalArgumentException("地址是空的")

            // 协议头与 UNC 前缀都剥掉，剩下 host/share/path
            s = s.removePrefix("smb://").removePrefix("SMB://")
            s = s.removePrefix("smb:").removePrefix("SMB:")
            s = s.replace('\\', '/')
            while (s.startsWith("/")) s = s.substring(1)

            val parts = s.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) {
                throw IllegalArgumentException("地址里要带上共享名，例如 smb://192.168.1.10/漫画")
            }

            val hostPart = parts[0]
            val port = if (hostPart.contains(':')) {
                val p = hostPart.substringAfterLast(':')
                p.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("端口不对：$p")
            } else {
                DEFAULT_PORT
            }
            val host = hostPart.substringBeforeLast(':').ifEmpty { hostPart }

            return SmbTarget(
                host = host,
                port = port,
                share = parts[1],
                path = parts.drop(2).joinToString("/"),
            )
        }

        /** 界面上单独填了端口时以它为准，地址里原本那段让位 */
        fun parse(raw: String, portOverride: Int?): SmbTarget {
            val target = parse(raw)
            if (portOverride == null) return target
            require(portOverride in 1..65535) { "端口要填 1–65535 之间的数字" }
            return target.copy(port = portOverride)
        }
    }
}
