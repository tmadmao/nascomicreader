package com.nascomic.reader.ui.remote

import android.content.Context
import com.nascomic.reader.R
import com.nascomic.reader.smb.SmbException
import com.nascomic.reader.webdav.WebDavException

/**
 * 远程失败的人话。库的原始报错是英文（okhttp / smbj / native），
 * 常见几种就地翻成人能对上号的说法，其余原样给出。
 */
fun remoteErrorText(context: Context, e: Throwable): String = when {
    e is WebDavException && (e.status == 401 || e.status == 403) ->
        context.getString(R.string.remote_error_auth)
    e is SmbException && e.authFailed -> context.getString(R.string.remote_error_auth)
    e is javax.net.ssl.SSLException || e is java.security.cert.CertificateException ->
        context.getString(R.string.remote_error_tls)
    e is java.net.UnknownHostException -> context.getString(R.string.remote_error_host)
    e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
        context.getString(R.string.remote_error_connect)
    // 指到普通网站而不是 WebDAV 时，okhttp 会抛「不是 HTTP 响应」这类话
    e.message?.contains("Unexpected status line") == true ->
        context.getString(R.string.remote_error_not_dav)
    else -> e.message ?: ""
}
