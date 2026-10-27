package com.nascomic.reader.library.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * 走系统文件选择器授权进来的目录，列出子项。
 *
 * 用 [DocumentsContract] 直接查，而不是 `DocumentFile`：后者每次取一个属性都要单独跨进程，
 * 一个大目录能列出几分钟；这里一次查回全部列。
 */
class SafWalker(private val context: Context, private val treeUri: Uri) {

    private val resolver = context.applicationContext.contentResolver

    val rootDocId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    /** 授权目录的 URI 字符串，用于构造子文档 URI */
    val treeUriString: String get() = treeUri.toString()

    /** 列出某个目录的直接子项；目录不存在或没权限时返回空表 */
    fun list(parentDocId: String): List<ScanNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<ScanNode>()
        try {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                val mtimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx).orEmpty()
                    out += ScanNode(
                        docId = c.getString(idIdx) ?: continue,
                        name = name,
                        isDir = mime == Document.MIME_TYPE_DIR,
                        size = if (c.isNull(sizeIdx)) 0 else c.getLong(sizeIdx),
                        lastModified = if (c.isNull(mtimeIdx)) 0 else c.getLong(mtimeIdx),
                    )
                }
            }
        } catch (_: SecurityException) {
            // 授权被系统回收
        } catch (_: IllegalArgumentException) {
            // 目录已被删除
        }
        return out
    }

    /** 打开某个文档的读流 */
    fun open(docId: String): java.io.InputStream? = try {
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
    } catch (_: Exception) {
        null
    }

    /** 文件大小；拿不到返回 0 */
    fun sizeOf(docId: String): Long = try {
        resolver.openAssetFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), "r")
            ?.use { it.length }
            ?: 0L
    } catch (_: Exception) {
        0L
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
