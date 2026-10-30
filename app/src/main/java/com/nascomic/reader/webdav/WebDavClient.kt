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
                // 不能 body.bytes()：几百兆的包会直接把进程撑爆，只读到需要的那一段就停
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

    /** 整份下载到本地文件（PDF 与 7z/RAR 需要文件路径） */
    fun download(url: String, out: java.io.File) {
        client.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException(response.code, "下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw WebDavException(response.code, "响应为空")
            out.outputStream().use { sink -> body.byteStream().copyTo(sink) }
        }
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
        if (!account.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(account, password.orEmpty()))
        }
        return builder
    }

    companion object {
        /**
         * 一个进程就一个 HTTP 客户端：连接池与线程池共用。
         * 每本书各建一个的话，一千多本的封面生成会把连接和内存一起耗光（NAS 直接不让连）。
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

        /** 解析 207 Multi-Status。纯字符串处理，便于单测 */
        fun parseMultiStatus(xml: String, baseUrl: String): List<Entry> =
            RESPONSE.findAll(xml).mapNotNull { match ->
                val block = match.value
                val href = HREF.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                val url = absoluteUrl(baseUrl, href)
                val isDir = COLLECTION.containsMatchIn(block)
                val size = LENGTH.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: -1L
                val name = decodeName(url)
                Entry(url = url, name = name, isDirectory = isDir, size = size)
            }.toList()

        /** href 可能是绝对路径也可能是完整 URL，统一拼成完整 URL */
        fun absoluteUrl(baseUrl: String, href: String): String {
            if (href.startsWith("http://") || href.startsWith("https://")) return href
            val origin = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.get(1) ?: return href
            return if (href.startsWith("/")) origin + href else baseUrl.trimEnd('/') + "/" + href
        }

        fun decodeName(url: String): String {
            val raw = url.trimEnd('/').substringAfterLast('/')
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }

        private fun normalize(url: String): String = url.trimEnd('/')
    }
}

class WebDavException(val status: Int, message: String) : Exception(message)
