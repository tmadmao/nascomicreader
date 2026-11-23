package com.nascomic.reader.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 最小可用的 WebDAV 客户端：只做两件事——列目录（PROPFIND）与按字节区间取数据（Range GET）。
 *
 * 没有引第三方 WebDAV 库：列目录就是一个 PROPFIND 加一段 XML，
 * 取数据就是一个带 Range 的 GET。自研比拉一个只发在 JitPack 上的依赖更可控。
 */
class WebDavClient(
    private val account: String?,
    private val password: String?,
    /**
     * 书源配置里的地址。凭据只发给这一个站点；
     * 传 null 表示「还不知道站点」（例如手动浏览远端目录时），这时不额外限制。
     */
    private val origin: String? = null,
) {

    data class Entry(
        /** 完整 URL */
        val url: String,
        /** 显示名（路径最后一段，已解码） */
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    private val client: OkHttpClient get() = shared

    /** 列一层。返回结果里不含被查询目录自身 */
    fun list(dirUrl: String): List<Entry> {
        // 集合地址补上结尾斜杠：不少服务端对 /dav 直接 404，对 /dav/ 才认
        val target = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = request(target)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "列目录失败：HTTP ${response.code}")
            }
            val xml = response.body?.string().orEmpty()
            // 一个 <response> 都没有：这是「响应不是 WebDAV 列表」，不是「目录是空的」。
            // 两者混为一谈，调用方就会把整个书库当成已被删除。
            if (RESPONSE.find(xml) == null) {
                throw WebDavException(response.code, "列目录的响应不是 WebDAV 列表")
            }
            return parseMultiStatus(xml, target).filter { normalize(it.url) != normalize(target) }
        }
    }

    /** 文件大小；服务器没给 Content-Length 时返回 -1 */
    fun size(url: String): Long {
        val request = request(url).head().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "取文件信息失败：HTTP ${response.code}")
            }
            return response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    /** 取 [offset] 起的 [length] 个字节；服务器不支持 Range 时返回 null */
    fun readRange(url: String, offset: Long, length: Int): ByteArray? {
        val request = request(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 206) {
                return response.body?.bytes()
            }
            if (response.code == 200) {
                // 服务器忽略了 Range，把整份都发来了。
                // 不能 body.bytes()：大包整块读进内存会撑爆进程，只读到需要的那一段就停
                val body = response.body ?: return null
                if (offset > 0L) return null
                return body.byteStream().use { input ->
                    val out = ByteArray(length)
                    var filled = 0
                    while (filled < length) {
                        val n = input.read(out, filled, length - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    out.copyOf(filled)
                }
            }
            if (response.code == 416) return ByteArray(0)
            throw WebDavException(response.code, "读取失败：HTTP ${response.code}")
        }
    }

    /**
     * 整份下载到本地文件（PDF 与 7z/RAR 需要文件路径）。
     *
     * 先写同目录的 `.part`，长度核对通过再改名到最终路径。
     * 直写最终文件的话，一次中断就会留下半份，而打开端只判「存在且非空」——
     * 那本书从此再也打不开，网络恢复也不会重下。
     *
     * [isCancelled] 为真时由看门线程调 `Call.cancel()`，把阻塞中的 socket 读直接断开；
     * 只靠拷贝循环里查标志挡不住服务器不发数据的情况。
     */
    fun download(
        url: String,
        out: java.io.File,
        isCancelled: () -> Boolean = { false },
    ) {
        val call = client.newCall(request(url).build())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread({
            while (!finished.get()) {
                if (isCancelled()) {
                    call.cancel()
                    return@Thread
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "dav-download-cancel").apply {
            isDaemon = true
            start()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavException(response.code, "下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw WebDavException(response.code, "响应为空")
                val expected = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val tmp = java.io.File(out.parentFile, out.name + ".part")
                runCatching { tmp.delete() }
                val written = try {
                    tmp.outputStream().use { sink ->
                        body.byteStream().use { input ->
                            copyCheckingCancel(input, sink, isCancelled)
                        }
                    }
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    if (isCancelled()) throw java.io.InterruptedIOException("已取消")
                    if (e is WebDavException) throw e
                    // 连接中途断了：换成自家异常，界面才好按「网络问题」说话
                    throw WebDavException(0, "下载中断：${e.message ?: e.javaClass.simpleName}")
                }
                if (isCancelled()) {
                    runCatching { tmp.delete() }
                    throw java.io.InterruptedIOException("已取消")
                }
                if (expected >= 0 && written != expected) {
                    runCatching { tmp.delete() }
                    throw WebDavException(response.code, "下载不完整：$written / $expected 字节")
                }
                publish(tmp, out)
            }
        } catch (e: WebDavException) {
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            throw e
        } catch (e: Exception) {
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            throw e
        } finally {
            finished.set(true)
            watcher.interrupt()
        }
    }

    /** 边拷边看取消标志：服务器慢慢发数据时，至少每个缓冲块都有一次机会 */
    private fun copyCheckingCancel(
        input: java.io.InputStream,
        sink: java.io.OutputStream,
        isCancelled: () -> Boolean,
    ): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            val n = input.read(buffer)
            if (n < 0) return total
            sink.write(buffer, 0, n)
            total += n
        }
    }

    /** 落成最终文件。改名失败就退化成拷贝，别把已经下好的这份丢掉 */
    private fun publish(tmp: java.io.File, out: java.io.File) {
        if (out.exists()) runCatching { out.delete() }
        if (tmp.renameTo(out)) return
        tmp.copyTo(out, overwrite = true)
        runCatching { tmp.delete() }
    }

    /**
     * 按引用取字节流，目录书逐页读用。
     * 关流时把响应一起关掉，否则连接池会被占满。
     */
    fun openStream(url: String): java.io.InputStream {
        val response = client.newCall(request(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw WebDavException(response.code, "读取失败：HTTP ${response.code}")
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw WebDavException(response.code, "响应为空")
        }
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                response.close()
            }
        }
    }

    private fun request(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        // 凭据只发给配置的那个站点：目录项里冒出别的主机（被改过的清单、代理、重定向）时，
        // 宁可不带认证头让请求被拒，也不能把账号密码送到配置以外的地方
        if (!account.isNullOrEmpty() && sameOrigin(url)) {
            builder.header("Authorization", Credentials.basic(account, password.orEmpty()))
        }
        return builder
    }

    /** 不知道站点（origin 为 null）时不作限制；知道就必须同源 */
    private fun sameOrigin(url: String): Boolean {
        val base = origin ?: return true
        return Origins.same(base, url)
    }

    companion object {
        /**
         * 一个进程就一个 HTTP 客户端：连接池与线程池共用。
         * 每本书各建一个的话，连接与内存会随书库规模一起涨，最终把 NAS 和进程都拖垮。
         */
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(6, 30, TimeUnit.SECONDS))
                .build()
        }

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/><getcontentlength/></prop></propfind>"""

        private val RESPONSE = Regex("""<(?:[A-Za-z0-9]+:)?response\b.*?</(?:[A-Za-z0-9]+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("""<(?:[A-Za-z0-9]+:)?href\s*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
        private val LENGTH = Regex("""<(?:[A-Za-z0-9]+:)?getcontentlength\s*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
        private val COLLECTION = Regex("""<(?:[A-Za-z0-9]+:)?collection\b""")

        /** `&amp;` `&#38;` 这类实体：名字或数字 */
        private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);""")

        /** 解析 207 Multi-Status。纯字符串处理，便于单测 */
        fun parseMultiStatus(xml: String, baseUrl: String): List<Entry> =
            RESPONSE.findAll(xml).mapNotNull { match ->
                val block = match.value
                val href = HREF.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                // href 是 XML 文本，`&` `<` 这些会被转义成实体；不还原就把 `&amp;` 当成了文件名的一部分
                val url = absoluteUrl(baseUrl, unescapeXml(href))
                val isDir = COLLECTION.containsMatchIn(block)
                val size = LENGTH.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: -1L
                val name = decodeName(url)
                Entry(url = url, name = name, isDirectory = isDir, size = size)
            }.toList()

        /**
         * 还原 XML 文本里的字符实体。
         *
         * 文件名带 `&` 时（`[组A&amp;组B].zip`）服务端不会把 `&` 做百分号编码，
         * 而是按 XML 规则转义。原样拿去请求就是另一个路径，服务端回 404。
         */
        internal fun unescapeXml(text: String): String {
            if (text.indexOf('&') < 0) return text
            return ENTITY.replace(text) { m ->
                when (val name = m.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else ->
                        // 数字实体：`&#38;` / `&#x26;`
                        if (name.startsWith("#x") || name.startsWith("#X")) {
                            name.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                        } else if (name.startsWith("#")) {
                            name.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                        } else {
                            m.value
                        }
                }
            }
        }

        private fun codePoint(value: Int): String? =
            if (value in 0..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value))
            else null

        /** href 可能是绝对路径也可能是完整 URL，统一拼成完整 URL */
        /**
         * href 可能是绝对路径，也可能是完整 URL，统一拼成完整 URL。
         *
         * 完整 URL 也只取「路径 + 查询」，主机一律换成配置的那个：
         * 有些服务端回的 href 是自己认定的主机名（用户填的是 IP），
         * 换回配置站点既不会连不上，也不会把请求引到别处。
         */
        fun absoluteUrl(baseUrl: String, href: String): String {
            val origin = Origins.of(baseUrl) ?: return href
            if (href.startsWith("http://") || href.startsWith("https://")) {
                return origin + Origins.pathOf(href)
            }
            return if (href.startsWith("/")) origin + href else baseUrl.trimEnd('/') + "/" + href
        }

        fun decodeName(url: String): String {
            val raw = url.trimEnd('/').substringAfterLast('/')
            // URLDecoder 是给表单用的：会把 + 解成空格，文件名里的加号就丢了。
            // 路径里的 + 是字面量，先把保护起来再按百分号解码
            return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(raw)
        }

        private fun normalize(url: String): String = url.trimEnd('/')
    }
}

class WebDavException(val status: Int, message: String) : Exception(message)
