package com.nascomic.reader.library

import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.SourceEntity

/**
 * 书在来源里的真实位置，详情页用它回答「这本来得到底来自哪个文件」。
 *
 * 三种来源的 `doc_ref` 口径不同：
 * - 本地：SAF 文档 id，形如 `primary:Download/漫画/海贼王01卷.zip`
 * - WebDAV：完整 URL
 * - SMB：共享内相对路径
 *
 * 这里统一成「从存储卷 / 共享根算起」的路径，用户选的那层目录保留在路径里，
 * 所以 `漫画/海贼王01卷.zip` 能直接对到文件管理器里看到的层级。
 */
object BookLocation {

    fun displayPath(book: BookEntity, source: SourceEntity?): String =
        displayPath(book.docRef, book.format, source?.kind)

    fun displayPath(docRef: String, format: Int, sourceKind: Int?): String {
        if (docRef.isBlank()) return ""
        var path = when {
            sourceKind == SourceEntity.KIND_WEBDAV -> webDavPath(docRef)
            sourceKind == SourceEntity.KIND_SMB -> docRef
            docRef.startsWith("http://") || docRef.startsWith("https://") -> webDavPath(docRef)
            else -> localPath(docRef)
        }
        path = path.replace('\\', '/')
        while (path.startsWith("/")) path = path.substring(1)
        path = path.trim()
        if (path.isEmpty()) return ""
        // 目录书给个尾斜杠：一眼能分出是文件夹还是压缩包
        if (format == BookEntity.FORMAT_FOLDER && !path.endsWith("/")) path += "/"
        return path
    }

    /** WebDAV 的 docRef 是整个 URL；去掉协议与主机，只留服务端路径 */
    private fun webDavPath(raw: String): String {
        // 用 java.net.URI 而不是 android.net.Uri：前者在 JVM 单测里也能跑，
        // 而且 `path` 会把 %E6%B5%B7 这类百分号编码还原成中文
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return raw
        return uri.path ?: raw
    }

    /** 本地 SAF 的 docId 形如「卷:路径」；没有卷前缀的原样返回 */
    private fun localPath(raw: String): String {
        val colon = raw.indexOf(':')
        val looksVolume = colon in 1..24 && raw.substring(0, colon).none { it == '/' }
        return if (looksVolume) raw.substring(colon + 1) else raw
    }
}
