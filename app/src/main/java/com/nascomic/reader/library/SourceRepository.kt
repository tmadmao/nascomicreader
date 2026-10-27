package com.nascomic.reader.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity

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

    suspend fun list(): List<SourceEntity> = db.sourceDao().all()

    suspend fun delete(sourceId: Long) = db.sourceDao().delete(sourceId)
}
