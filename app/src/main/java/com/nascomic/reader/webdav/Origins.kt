package com.nascomic.reader.webdav

/**
 * 站点身份：协议 + 主机 + 有效端口。
 *
 * 用来回答一个问题——「这个请求要去的地方，和用户在书源里填的是不是同一个站点」。
 * 只有同一个站点才配拿到凭据；目录项、重定向里冒出来的别的主机一律不带认证头。
 *
 * 端口取协议的默认值时省掉，这样 `https://nas/dav/` 与 `https://nas:443/dav/` 是同一个站点，
 * 也不会因为写法不同把同一批条目的引用改成另一个字符串。
 */
object Origins {

    private val PATTERN = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://(\[[^\]]+\]|[^/:?#]+)(?::(\d+))?""")

    /** 抽成 `scheme://host[:port]`；不是完整地址就返回 null */
    fun of(url: String): String? {
        val m = PATTERN.find(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        val default = defaultPort(scheme)
        val port = m.groupValues[3].toIntOrNull() ?: default
        return if (port == default) "$scheme://$host" else "$scheme://$host:$port"
    }

    fun same(a: String, b: String): Boolean {
        val left = of(a) ?: return false
        return left == of(b)
    }

    /** `https://nas:5006` 的路径部分；没有路径就返回 "/" */
    fun pathOf(url: String): String {
        val rest = PATTERN.replace(url.trim(), "")
        return when {
            rest.isEmpty() -> "/"
            rest.startsWith("/") -> rest
            else -> "/$rest"
        }
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> 0
    }
}
