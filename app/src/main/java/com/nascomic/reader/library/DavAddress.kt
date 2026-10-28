package com.nascomic.reader.library

/**
 * WebDAV 地址的拆与拼。
 *
 * 界面上填的是三样东西：协议（HTTP / HTTPS）、服务器地址（主机，后面可以带路径）、端口。
 * 但地址栏里也常被粘进整串（`http://192.168.1.10:5005/dav/`），所以先拆再拼：
 *
 * - 端口：单独填的那个优先，其次取地址里自带的那段，都没有就按协议默认（80 / 443）
 * - 协议：地址里带了 `http://` 或 `https://` 就以它为准，否则用界面上选的那个
 */
object DavAddress {

    const val DEFAULT_HTTP_PORT = 80
    const val DEFAULT_HTTPS_PORT = 443

    /**
     * @param port 实际生效的端口；[portSet] 为 false 表示用户没指定，拼 URL 时省略，交给协议默认
     */
    data class Parts(
        val scheme: String,
        val host: String,
        val port: Int,
        val portSet: Boolean,
        val path: String,
    )

    fun defaultPort(scheme: String): Int =
        if (scheme.equals("http", ignoreCase = true)) DEFAULT_HTTP_PORT else DEFAULT_HTTPS_PORT

    /**
     * 拆。
     *
     * @throws IllegalArgumentException 地址为空、端口不在 1–65535、地址里带了不认识的端口
     */
    fun parse(scheme: String, address: String, portText: String): Parts {
        var rest = address.trim()
        var sch = if (scheme.equals("http", ignoreCase = true)) "http" else "https"

        // 整串 URL 粘进来时，协议以它为准
        when {
            rest.startsWith("http://", ignoreCase = true) -> {
                sch = "http"
                rest = rest.substring(7)
            }
            rest.startsWith("https://", ignoreCase = true) -> {
                sch = "https"
                rest = rest.substring(8)
            }
            rest.startsWith("davs://", ignoreCase = true) -> {
                sch = "https"
                rest = rest.substring(7)
            }
            rest.startsWith("dav://", ignoreCase = true) -> {
                sch = "http"
                rest = rest.substring(6)
            }
        }

        val slash = rest.indexOf('/')
        val hostPart = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash + 1) else ""

        var host = hostPart
        var inlinePort = 0
        if (hostPart.contains(':')) {
            val p = hostPart.substringAfterLast(':').trim()
            if (p.isNotEmpty()) {
                inlinePort = p.toIntOrNull()
                    ?: throw IllegalArgumentException("地址里的端口不对：$p")
                require(inlinePort in 1..65535) { "地址里的端口不对：$p" }
            }
            host = hostPart.substringBeforeLast(':')
        }
        host = host.trim()
        require(host.isNotEmpty()) { "服务器地址不能为空" }

        val typedText = portText.trim()
        val typedPort = if (typedText.isEmpty()) {
            null
        } else {
            val v = typedText.toIntOrNull()
                ?: throw IllegalArgumentException("端口要填数字")
            require(v in 1..65535) { "端口要填 1–65535 之间的数字" }
            v
        }

        val portSet = typedPort != null || inlinePort != 0
        val port = typedPort ?: if (inlinePort != 0) inlinePort else defaultPort(sch)

        return Parts(
            scheme = sch,
            host = host,
            port = port,
            portSet = portSet,
            path = path.trim('/'),
        )
    }

    /** 拼回完整 URL：协议 + 主机（+ 非默认端口）+ 路径，结尾不带斜杠 */
    fun toUrl(parts: Parts): String {
        val sb = StringBuilder(parts.scheme).append("://").append(parts.host)
        if (parts.port != defaultPort(parts.scheme)) sb.append(':').append(parts.port)
        if (parts.path.isNotEmpty()) sb.append('/').append(parts.path)
        return sb.toString()
    }
}
