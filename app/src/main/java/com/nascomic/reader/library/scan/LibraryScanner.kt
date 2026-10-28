package com.nascomic.reader.library.scan

import android.content.Context
import android.net.Uri
import com.nascomic.reader.core.text.NaturalSort
import androidx.room.withTransaction
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.settings.SecretStore
import com.nascomic.reader.smb.SmbClient
import com.nascomic.reader.smb.SmbException
import com.nascomic.reader.smb.SmbTarget
import com.nascomic.reader.webdav.WebDavClient
import com.nascomic.reader.webdav.WebDavException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描一个来源，把找到的书写进数据库。
 *
 * 书型判定见 [ScanRules]：目录里直接是图 = 单行本；目录里是子目录或压缩包 = 章回体。
 *
 * 扫描阶段只记名字、大小和章的结构，**不解包、不数页**——几百本的目录如果每本都解包，
 * 首次扫描要几分钟。页数在打开那本书时再算。
 */
private const val DELETE_BATCH = 400

class LibraryScanner(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    private val secrets by lazy { SecretStore(context) }

    /** 扫描过程中的回话口：列目录（不知道要多久）与入库（能报进度）两个阶段 */
    interface Listener {
        fun onListing() {}
        fun onProgress(done: Int, total: Int) {}
    }

    data class Result(
        val found: Int,
        val added: Int,
        val updated: Int,
        val removed: Int,
        val chaptered: Int,
    )

    suspend fun scan(source: SourceEntity, listener: Listener? = null): Result =
        withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceEntity.KIND_WEBDAV -> return@withContext scanWebDav(source, listener)
            SourceEntity.KIND_SMB -> return@withContext scanSmb(source, listener)
        }
        listener?.onListing()

        val treeUri = source.treeUri ?: throw IllegalArgumentException("来源没有目录授权")
        val walker = SafWalker(context, Uri.parse(treeUri))

        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, System.currentTimeMillis())

        val found = ArrayList<Candidate>()
        try {
            val rootChildren = walker.list(walker.rootDocId)
            for (node in rootChildren) {
                when {
                    node.isDir -> classifyFolder(walker, node)?.let { found += it }
                    ScanRules.isArchive(node.name) -> found += archiveAsBook(node)
                    ScanRules.isPdf(node.name) -> found += pdfAsBook(node)
                    ScanRules.isEpub(node.name) -> found += epubAsBook(node)
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_FAILED,
                SourceEntity.ERROR_TREE_PERMISSION_LOST,
                System.currentTimeMillis()
            )
            throw e
        }

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        var chaptered = 0

        for (candidate in found) {
            val existing = db.bookDao().findByRef(source.id, candidate.node.docId)
            val bookId: Long = if (existing == null) {
                added++
                db.bookDao().insert(
                    BookEntity(
                        sourceId = source.id,
                        docRef = candidate.node.docId,
                        title = ScanRules.titleOf(candidate.node),
                        titleSort = NaturalSort.key(ScanRules.titleOf(candidate.node)),
                        format = candidate.format,
                        byteSize = candidate.size,
                        chapterTotal = candidate.chapters.size,
                        isSingleVolume = if (candidate.chapters.isEmpty()) 1 else 0,
                        addedAt = now,
                        modifiedAt = candidate.modifiedAt,
                    )
                )
            } else {
                updated++
                db.bookDao().upsert(
                    existing.copy(
                        title = ScanRules.titleOf(candidate.node),
                        titleSort = NaturalSort.key(ScanRules.titleOf(candidate.node)),
                        byteSize = candidate.size,
                        chapterTotal = candidate.chapters.size,
                        isSingleVolume = if (candidate.chapters.isEmpty()) 1 else 0,
                        modifiedAt = candidate.modifiedAt,
                    )
                )
                existing.id
            }

            if (candidate.chapters.isNotEmpty()) {
                chaptered++
                db.chapterDao().replaceFor(
                    bookId,
                    candidate.chapters.mapIndexed { index, unit ->
                        ChapterEntity(
                            bookId = bookId,
                            ordinal = index,
                            title = ScanRules.titleOf(unit),
                            chapterRef = unit.docId,
                            chapterKind = if (unit.isDir) {
                                ChapterEntity.KIND_FOLDER
                            } else {
                                ChapterEntity.KIND_ARCHIVE
                            },
                        )
                    }
                )
            }
        }

        // 清理磁盘上已经没有的条目
        val present = found.map { it.node.docId }
        val before = db.bookDao().countBySource(source.id)
        if (present.isEmpty()) {
            db.bookDao().deleteBySource(source.id)
        } else {
            db.bookDao().deleteMissing(source.id, present)
        }

        db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)

        Result(
            found = found.size,
            added = added,
            updated = updated,
            removed = (before - present.size).coerceAtLeast(0),
            chaptered = chaptered,
        )
    }

    /** 远端列表里的一条：入库只需要这三样 */
    private class RemoteFile(val docRef: String, val name: String, val size: Long)

    private suspend fun scanWebDav(source: SourceEntity, listener: Listener? = null): Result {
        val endpoint = source.endpoint ?: throw IllegalArgumentException("来源没有地址")
        val client = WebDavClient(source.account, secrets.get(source.secretRef))

        return scanRemote(source, listener) {
            client.list(endpoint)
                .filter { !it.isDirectory && ScanRules.isSingleBookFile(it.name) }
                .map { RemoteFile(it.url, it.name, it.size) }
        }
    }

    private suspend fun scanSmb(source: SourceEntity, listener: Listener? = null): Result {
        val target = SmbTarget.parse(source.endpoint ?: throw IllegalArgumentException("来源没有地址"))
        val client = SmbClient(target, source.account, secrets.get(source.secretRef))

        return scanRemote(source, listener) {
            client.list(target.path)
                .filter { !it.isDirectory && ScanRules.isSingleBookFile(it.name) }
                .map { RemoteFile(it.path, it.name, it.size) }
        }
    }

    /**
     * 远端来源的公共流程：列一层 → 认书 → 入库 → 清掉已消失的。
     * WebDAV 与 SMB 只差「怎么列出文件名」，所以这一段只写一份。
     */
    private suspend fun scanRemote(
        source: SourceEntity,
        listener: Listener? = null,
        listFiles: () -> List<RemoteFile>,
    ): Result {
        val startedAt = System.currentTimeMillis()
        listener?.onListing()
        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, startedAt)

        val files = try {
            listFiles()
        } catch (e: Exception) {
            // 原始报错留在日志里，界面只按类别说话
            android.util.Log.w("Scan", "列目录失败：${source.label}", e)
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_FAILED, errorCodeOf(e), startedAt)
            throw e
        }

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0

        // 一次读全这个来源已有的书，别每本查一遍库：一千多本时那是三千多次查询
        val known = db.bookDao().bySourceNow(source.id).associateBy { it.docRef }
        val fresh = ArrayList<BookEntity>(files.size)
        val touched = ArrayList<BookEntity>()

        for ((index, file) in files.withIndex()) {
            if (index % 25 == 0) listener?.onProgress(index, files.size)
            val title = ScanRules.titleOf(file.name)
            val existing = known[file.docRef]
            if (existing == null) {
                added++
                fresh += BookEntity(
                    sourceId = source.id,
                    docRef = file.docRef,
                    title = title,
                    titleSort = NaturalSort.key(title),
                    format = formatOf(file.name),
                    byteSize = file.size.coerceAtLeast(0),
                    chapterTotal = 0,
                    isSingleVolume = 1,
                    addedAt = now,
                    modifiedAt = now,
                )
            } else if (existing.title != title || existing.byteSize != file.size.coerceAtLeast(0)) {
                updated++
                touched += existing.copy(
                    title = title,
                    titleSort = NaturalSort.key(title),
                    byteSize = file.size.coerceAtLeast(0),
                    modifiedAt = now,
                )
            }
        }

        listener?.onProgress(files.size, files.size)

        // 一个事务写完：逐条提交时每条都要刷盘，一千多本会拖到分钟级
        db.withTransaction {
            if (fresh.isNotEmpty()) db.bookDao().insertAll(fresh)
            if (touched.isNotEmpty()) db.bookDao().upsertAll(touched)

            // 清理远端已经没有的条目：按 id 分段删，避免一次塞几千个参数
            val present = files.mapTo(HashSet(files.size)) { it.docRef }
            val gone = known.values.filter { it.docRef !in present }.map { it.id }
            gone.chunked(DELETE_BATCH).forEach { db.bookDao().deleteByIds(it) }
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)
        }

        return Result(found = files.size, added = added, updated = updated, removed = 0, chaptered = 0)
    }

    /** 账号不对和连不上要分开报：前者提示改密码，后者提示查地址与网络 */
    private fun errorCodeOf(e: Exception): Int = when {
        e is WebDavException && (e.status == 401 || e.status == 403) -> SourceEntity.ERROR_AUTH
        e is SmbException && e.authFailed -> SourceEntity.ERROR_AUTH
        else -> SourceEntity.ERROR_UNREACHABLE
    }

    private fun formatOf(name: String): Int = when {
        ScanRules.isEpub(name) -> BookEntity.FORMAT_EPUB
        ScanRules.isPdf(name) -> BookEntity.FORMAT_PDF
        ScanRules.isArchive(name) -> BookEntity.FORMAT_ZIP
        else -> BookEntity.FORMAT_ZIP
    }

    /** 目录 → 一本书；空目录返回 null */
    private fun classifyFolder(walker: SafWalker, node: ScanNode): Candidate? {
        val children = walker.list(node.docId)
        return when (ScanRules.folderKind(children)) {
            ScanRules.FolderKind.DIRECT_PAGES ->
                Candidate(node, BookEntity.FORMAT_FOLDER, emptyList())

            ScanRules.FolderKind.BY_CHAPTER ->
                Candidate(node, BookEntity.FORMAT_FOLDER, ScanRules.chapterUnitsOf(children))

            ScanRules.FolderKind.EMPTY -> null
        }
    }

    private fun pdfAsBook(node: ScanNode) = Candidate(
        node = node,
        format = BookEntity.FORMAT_PDF,
        chapters = emptyList(),
    )

    private fun epubAsBook(node: ScanNode) = Candidate(
        node = node,
        format = BookEntity.FORMAT_EPUB,
        chapters = emptyList(),
    )

    private fun archiveAsBook(node: ScanNode) = Candidate(node, BookEntity.FORMAT_ZIP, emptyList())

    private data class Candidate(
        val node: ScanNode,
        val format: Int,
        /** 空表 = 单行本；否则每一项是一章（目录或压缩包） */
        val chapters: List<ScanNode>,
    ) {
        val size: Long get() = node.size
        val modifiedAt: Long get() = node.lastModified
    }
}
