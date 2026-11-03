package com.nascomic.reader.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.settings.SecretStore

/**
 * 书库来源的增删。
 *
 * 本地目录走系统文件选择器：用户选一个目录，我们拿到一个可长期访问的授权。
 * 这个授权会随应用重装、或用户在系统设置里清除而失效，所以扫描失败时要能提示重选。
 */
class SourceRepository(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    /**
     * 把一个刚授权的目录登记为来源。同一个目录重复添加时返回已有记录。
     */
    suspend fun addLocalFolder(treeUri: Uri, label: String?): Long {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val key = treeUri.toString()
        db.sourceDao().findByTreeUri(SourceEntity.KIND_LOCAL, key)?.let { return it.id }

        val display = label?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "本地目录"

        return db.sourceDao().insert(
            SourceEntity(
                kind = SourceEntity.KIND_LOCAL,
                label = display,
                treeUri = key,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 登记一个 WebDAV 来源。密码不落库：写进应用私有的 SecretStore，
     * 库里只留一个键名（`secretRef`）。
     */
    suspend fun addWebDav(
        endpoint: String,
        account: String?,
        password: String?,
        label: String?,
    ): Long {
        val url = normalizeEndpoint(endpoint)
        val display = label?.takeIf { it.isNotBlank() }
            ?: decodeLabel(url.removePrefix("https://").removePrefix("http://").trimEnd('/'))

        val id = db.sourceDao().insert(
            SourceEntity(
                kind = SourceEntity.KIND_WEBDAV,
                label = display,
                endpoint = url,
                account = account?.takeIf { it.isNotBlank() },
                addedAt = System.currentTimeMillis(),
            )
        )
        if (!password.isNullOrBlank()) {
            val key = "dav_$id"
            SecretStore(context).put(key, password)
            db.sourceDao().get(id)?.let { db.sourceDao().upsert(it.copy(secretRef = key)) }
        }
        return id
    }

    /**
     * 登记一个 SMB 来源。地址在这里就校验一遍——解析不出共享名说明是错的，
     * 早点报比等到扫描时再报清楚。
     */
    suspend fun addSmb(
        endpoint: String,
        account: String?,
        password: String?,
        label: String?,
        port: Int? = null,
    ): Long {
        val target = com.nascomic.reader.smb.SmbTarget.parse(endpoint, port)
        val url = buildString {
            append("smb://").append(target.host)
            if (target.port != com.nascomic.reader.smb.SmbTarget.DEFAULT_PORT) {
                append(':').append(target.port)
            }
            append('/').append(target.share)
            if (target.path.isNotEmpty()) append('/').append(target.path)
        }

        val display = label?.takeIf { it.isNotBlank() }
            ?: decodeLabel(target.share + if (target.path.isEmpty()) "" else "/${target.path}")

        val id = db.sourceDao().insert(
            SourceEntity(
                kind = SourceEntity.KIND_SMB,
                label = display,
                endpoint = url,
                account = account?.takeIf { it.isNotBlank() },
                addedAt = System.currentTimeMillis(),
            )
        )
        if (!password.isNullOrBlank()) {
            val key = "smb_$id"
            SecretStore(context).put(key, password)
            db.sourceDao().get(id)?.let { db.sourceDao().upsert(it.copy(secretRef = key)) }
        }
        return id
    }

    /**
     * 改名。书库名是用户自己起的，默认由地址或文件夹名兜底，随时可改。
     */
    suspend fun rename(sourceId: Long, label: String) {
        val name = label.trim()
        if (name.isEmpty()) return
        val source = db.sourceDao().get(sourceId) ?: return
        db.sourceDao().upsert(source.copy(label = name))
    }

    /**
     * 改远端来源的地址、账号、密码。
     *
     * NAS 换了密码或端口时用这个，而不是删了重加——删来源会把书、进度、书签一起带走。
     * 密码留空表示不改；地址照旧补协议与结尾斜杠。
     */
    suspend fun updateRemote(
        sourceId: Long,
        endpoint: String,
        account: String?,
        password: String?,
        port: Int? = null,
    ) {
        val source = db.sourceDao().get(sourceId) ?: return
        val url = when (source.kind) {
            SourceEntity.KIND_SMB -> {
                val target = com.nascomic.reader.smb.SmbTarget.parse(endpoint, port)
                buildString {
                    append("smb://").append(target.host)
                    if (target.port != com.nascomic.reader.smb.SmbTarget.DEFAULT_PORT) {
                        append(':').append(target.port)
                    }
                    append('/').append(target.share)
                    if (target.path.isNotEmpty()) append('/').append(target.path)
                }
            }
            else -> normalizeEndpoint(endpoint)
        }
        val cleanAccount = account?.takeIf { it.isNotBlank() }
        db.sourceDao().upsert(
            source.copy(endpoint = url, account = cleanAccount, scanState = SourceEntity.STATE_IDLE)
        )
        if (!password.isNullOrBlank()) {
            val key = source.secretRef ?: when (source.kind) {
                SourceEntity.KIND_SMB -> "smb_$sourceId"
                else -> "dav_$sourceId"
            }
            SecretStore(context).put(key, password)
            db.sourceDao().get(sourceId)?.let { db.sourceDao().upsert(it.copy(secretRef = key)) }
        }
    }

    /**
     * 删掉一个来源。书、章、进度、书签由外键级联删除，密码要单独清
     * （它不在库里），本地目录还要把系统授权退回去，否则会一直挂在系统里。
     */
    suspend fun remove(sourceId: Long) {
        val source = db.sourceDao().get(sourceId) ?: return
        SecretStore(context).remove(source.secretRef)
        db.sourceDao().delete(sourceId)

        val tree = source.treeUri
        if (source.kind == SourceEntity.KIND_LOCAL && tree != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /** 地址补全协议与结尾斜杠，避免用户少打一个字符就列不出目录 */
    fun normalizeEndpoint(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return if (url.endsWith("/")) url else "$url/"
    }

    /**
     * 所有来源。
     *
     * 顺带把标签里残留的百分号编码解回来：从远端目录里选进来源时，路径是编码过的
     * （`%E6%BC%AB%E7%94%BB`），照原样显示就是一串乱码。解一次、就地写回，
     * 之后各个页面读到的都是可读的名字。
     */
    suspend fun list(): List<SourceEntity> {
        val all = db.sourceDao().all()
        var healed = false
        val fixed = all.map { source ->
            val decoded = decodeLabel(source.label)
            if (decoded == source.label) {
                source
            } else {
                healed = true
                source.copy(label = decoded)
            }
        }
        if (healed) fixed.forEach { db.sourceDao().upsert(it) }
        return fixed
    }

    /** 百分号编码转可读；没有 % 就原样返回，解不开也原样返回 */
    private fun decodeLabel(label: String): String =
        if (label.contains('%')) runCatching { android.net.Uri.decode(label) }.getOrDefault(label) else label

    suspend fun delete(sourceId: Long) = db.sourceDao().delete(sourceId)
}
