package com.nascomic.reader.library.scan

import android.content.Context
import android.net.Uri
import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.settings.SecretStore
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
class LibraryScanner(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    private val secrets by lazy { SecretStore(context) }

    data class Result(
        val found: Int,
        val added: Int,
        val updated: Int,
        val removed: Int,
        val chaptered: Int,
    )

    suspend fun scan(source: SourceEntity): Result = withContext(Dispatchers.IO) {
        if (source.kind == SourceEntity.KIND_WEBDAV) return@withContext scanRemote(source)

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

    /**
     * WebDAV 来源：列一层目录，认出能当书的文件。
     * v1 只看一层（子目录先不递归），远端「一本一个文件」的结构最常见。
     */
    private suspend fun scanRemote(source: SourceEntity): Result {
        val endpoint = source.endpoint ?: throw IllegalArgumentException("来源没有地址")
        val client = WebDavClient(source.account, secrets.get(source.secretRef))

        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, System.currentTimeMillis())

        val entries = try {
            client.list(endpoint)
        } catch (e: WebDavException) {
            val code = when (e.status) {
                401, 403 -> SourceEntity.ERROR_AUTH
                else -> SourceEntity.ERROR_UNREACHABLE
            }
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_FAILED, code, System.currentTimeMillis())
            throw e
        } catch (e: Exception) {
            db.sourceDao().setScanState(
                source.id, SourceEntity.STATE_FAILED, SourceEntity.ERROR_UNREACHABLE, System.currentTimeMillis()
            )
            throw e
        }

        val files = entries.filter { !it.isDirectory && ScanRules.isSingleBookFile(it.name) }
        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0

        for (entry in files) {
            val title = ScanRules.titleOf(entry.name)
            val format = formatOf(entry.name)
            val existing = db.bookDao().findByRef(source.id, entry.url)
            if (existing == null) {
                added++
                db.bookDao().insert(
                    BookEntity(
                        sourceId = source.id,
                        docRef = entry.url,
                        title = title,
                        titleSort = NaturalSort.key(title),
                        format = format,
                        byteSize = entry.size.coerceAtLeast(0),
                        chapterTotal = 0,
                        isSingleVolume = 1,
                        addedAt = now,
                        modifiedAt = now,
                    )
                )
            } else {
                updated++
                db.bookDao().upsert(
                    existing.copy(
                        title = title,
                        titleSort = NaturalSort.key(title),
                        byteSize = entry.size.coerceAtLeast(0),
                        modifiedAt = now,
                    )
                )
            }
        }

        val present = files.map { it.url }
        if (present.isEmpty()) {
            db.bookDao().deleteBySource(source.id)
        } else {
            db.bookDao().deleteMissing(source.id, present)
        }
        db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)

        return Result(
            found = files.size,
            added = added,
            updated = updated,
            removed = 0,
            chaptered = 0,
        )
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
