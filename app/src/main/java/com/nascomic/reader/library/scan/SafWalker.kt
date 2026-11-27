package com.nascomic.reader.library.scan

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.nascomic.reader.core.fs.SystemDirs

/**
 * 走系统文件选择器授权进来的目录，列出子项。
 *
 * 用 [DocumentsContract] 直接查，而不是 `DocumentFile`：后者每次取一个属性都要单独跨进程，
 * 一个大目录能列出几分钟；这里一次查回全部列。
 */
class SafWalker internal constructor(
    context: Context,
    private val treeUri: Uri,
    private val queryOverride: ((Uri, Array<String>) -> Cursor?)?,
) {
    constructor(context: Context, treeUri: Uri) : this(context, treeUri, null)

    private val resolver = context.applicationContext.contentResolver

    private fun query(uri: Uri, projection: Array<String>): Cursor? =
        if (queryOverride == null) resolver.query(uri, projection, null, null, null)
        else queryOverride.invoke(uri, projection)

    val rootDocId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    /** 授权目录的 URI 字符串，用于构造子文档 URI */
    val treeUriString: String get() = treeUri.toString()

    /**
     * 列出某个目录的直接子项。
     *
     * 读不到（授权被回收、目录被删或改名、外接盘掉线）返回 null，**不返回空表**——
     * 扫描器要靠这个区别决定「这本来源空了」还是「这次没读到」，
     * 两者混在一起会把整库的书连同进度书签一起删掉。
     * 空目录返回空表，与读失败区分得开。
     *
     * 系统目录（`#recycle`、`@eaDir` 等）在这里就丢掉：扫描、打开目录和检查文件
     * 都走这个方法，只挡扫描器会让另外两条路径仍然看得到回收站里的旧书。
     */
    fun list(parentDocId: String, requireComplete: Boolean = false): List<ScanNode>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<ScanNode>()
        try {
            val cursor = query(childrenUri, PROJECTION)
                ?: return null
            cursor.use { c ->
                if (requireComplete && !c.isCompleteListing()) return null
                val idIdx = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                val mtimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    val id = c.getString(idIdx)
                    val mime = c.getString(mimeIdx)
                    if (requireComplete && (name == null || id == null || mime.isNullOrEmpty())) return null
                    if (name == null || id == null) continue
                    out += ScanNode(
                        docId = id,
                        name = name,
                        isDir = mime == Document.MIME_TYPE_DIR,
                        size = if (c.isNull(sizeIdx)) 0 else c.getLong(sizeIdx),
                        lastModified = if (c.isNull(mtimeIdx)) 0 else c.getLong(mtimeIdx),
                    )
                }
                if (requireComplete && !c.isCompleteListing()) return null
            }
        } catch (_: SecurityException) {
            // 授权被系统回收
            return null
        } catch (_: IllegalArgumentException) {
            // 目录已被删除
            return null
        }
        return SystemDirs.withoutIgnoredDirectories(
            items = out,
            nameOf = { it.name },
            isDirectory = { it.isDir },
        )
    }

    private fun Cursor.isCompleteListing(): Boolean = extras.let {
        !it.getBoolean(DocumentsContract.EXTRA_LOADING) &&
            it.getString(DocumentsContract.EXTRA_ERROR) == null &&
            it.getString(DocumentsContract.EXTRA_INFO) == null
    }

    /** IDs only have path semantics for the platform external storage provider. */
    private fun storagePathTo(docId: String): List<String>? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val root = rootDocId
        if (docId == root) return listOf(root)
        val prefix = if (root.endsWith(':')) root else "$root/"
        if (!docId.startsWith(prefix)) return null
        var current = root
        return listOf(root) + docId.removePrefix(prefix).split('/').map { name ->
            current += if (current.endsWith(':')) name else "/$name"
            current
        }
    }

    internal fun storageParentIds(docId: String): List<String> = storagePathTo(docId)?.dropLast(1).orEmpty()

    fun pathTo(docId: String): List<String>? {
        storagePathTo(docId)?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val path = runCatching {
                DocumentsContract.findDocumentPath(resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))?.path
            }.getOrNull()
            if (path?.firstOrNull() == rootDocId && path.lastOrNull() == docId) return path
        }
        return null
    }

    fun displayName(docId: String): String? = runCatching {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** A null cursor is a query failure. An empty row set or a directory has no file metadata. */
    fun fileMetadata(docId: String): ScanNode? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val cursor = query(uri, PROJECTION)
            ?: throw java.io.IOException("无法核对文件：$docId")
        return cursor.use {
            if (!it.moveToFirst() || it.getString(2) == Document.MIME_TYPE_DIR) null
            else ScanNode(docId, it.getString(1) ?: throw java.io.IOException("文件没有名称：$docId"), false,
                if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4))
        }
    }

    fun versionOf(docId: String): String = fileMetadata(docId)?.let { "${it.size}:${it.lastModified}" }
        ?: throw java.io.FileNotFoundException(docId)

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
