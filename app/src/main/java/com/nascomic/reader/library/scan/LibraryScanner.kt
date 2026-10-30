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

/** 远端最多往下找几层：装书的容器再深就是目录树本身了，不值得无限递归 */
private const val MAX_REMOTE_DEPTH = 4

class LibraryScanner(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    private val secrets by lazy { SecretStore(context) }

    /** 扫描规则：封面取第几页、同名书去重、章的排序 */
    private val prefs by lazy { com.nascomic.reader.settings.AppPrefs(context) }

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
        /** 这次一本都没扫到，原有书库原样保留（授权或挂载出了问题，等用户确认） */
        val keptAll: Boolean = false,
        /** 有几个远端目录这次没读到：它们底下的书没被删，但也没被更新 */
        val unreachableDirs: Int = 0,
    )

    /** 目录列不出来：授权被回收、目录被删或改名、外接盘掉线 */
    private class TreeGoneException : Exception("目录读不到，授权可能已失效")

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
            // 列不出来就抛：这一层失败必须让整次扫描失败，不能退化成「这个目录空了」
            val rootChildren = walker.list(walker.rootDocId) ?: throw TreeGoneException()
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

        // 一次都没扫到、但库里原本有书：多半是授权或挂载出了问题，
        // 这时候删书等于把用户的书架、进度、书签一起清了，所以停下来等用户确认
        val before = db.bookDao().countBySource(source.id)
        if (found.isEmpty() && before > 0) {
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_FAILED,
                SourceEntity.ERROR_EMPTY_SCAN,
                System.currentTimeMillis()
            )
            return@withContext Result(
                found = 0,
                added = 0,
                updated = 0,
                removed = 0,
                chaptered = 0,
                keptAll = true,
            )
        }

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        var chaptered = 0

        val books = found

        // 一个事务写完：逐条提交时每条都要刷盘，一千多本会拖到分钟级；中途失败也不会留下半套书
        db.withTransaction {
            for (candidate in books) {
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
                            coverPage = 0,
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
                                chapterKind = when {
                                    unit.isDir -> ChapterEntity.KIND_FOLDER
                                    ScanRules.isPdf(unit.name) -> ChapterEntity.KIND_PDF
                                    else -> ChapterEntity.KIND_ARCHIVE
                                },
                            )
                        }
                    )
                }
            }

            // 清理磁盘上已经没有的条目。
            // 按 id 分段删，不走 `doc_ref NOT IN (...)`——那条语句的书多过 998 本就会撞
            // SQLite 的变量上限（Android 10 及以下），扫到一半失败
            val present = books.mapTo(HashSet(books.size)) { it.node.docId }
            val gone = db.bookDao().bySourceNow(source.id)
                .filterNot { it.docRef in present }
                .map { it.id }
            gone.chunked(DELETE_BATCH).forEach { db.bookDao().deleteByIds(it) }
        }

        db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)

        Result(
            found = found.size,
            added = added,
            updated = updated,
            removed = (before - books.size).coerceAtLeast(0),
            chaptered = chaptered,
        )
    }

    /** 远端列表里的一条：入库只需要这三样 */
    /** 远端的目录项。目录也算进来，才能认出「一个目录一本书」 */
    private class RemoteEntry(
        val ref: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
    )

    /** 远端扫出来的一本书 */
    private class RemoteBook(
        val docRef: String,
        val title: String,
        val format: Int,
        val chapters: List<RemoteChapter>,
        /** 单文件书才有大小；目录书记 0 */
        val size: Long = 0,
    )

    private class RemoteChapter(val title: String, val ref: String, val kind: Int)

    private suspend fun scanWebDav(source: SourceEntity, listener: Listener? = null): Result {
        val endpoint = source.endpoint ?: throw IllegalArgumentException("来源没有地址")
        val client = WebDavClient(source.account, secrets.get(source.secretRef))
        val lister: (String) -> List<RemoteEntry> = { dir ->
            client.list(dir).map { RemoteEntry(it.url, it.name, it.isDirectory, it.size) }
        }
        return scanRemote(source, endpoint, lister, listener)
    }

    private suspend fun scanSmb(source: SourceEntity, listener: Listener? = null): Result {
        val target = SmbTarget.parse(source.endpoint ?: throw IllegalArgumentException("来源没有地址"))
        val client = SmbClient(target, source.account, secrets.get(source.secretRef))
        val lister: (String) -> List<RemoteEntry> = { dir ->
            client.list(dir).map { RemoteEntry(it.path, it.name, it.isDirectory, it.size) }
        }
        return scanRemote(source, target.path, lister, listener)
    }

    /**
     * 远端来源的公共流程：递归走目录 → 认书 → 入库 → 清掉已消失的。
     * WebDAV 与 SMB 只差「怎么列目录」，所以这一段只写一份。
     *
     * 结构判定与本地一致：根目录是容器，子目录各是一本书；
     * 目录里直接是图片 = 单行本，是子目录 / 压缩包 / PDF = 章回体；都不是就再往下找一层。
     */
    private suspend fun scanRemote(
        source: SourceEntity,
        rootRef: String,
        list: (String) -> List<RemoteEntry>,
        listener: Listener? = null,
    ): Result {
        val startedAt = System.currentTimeMillis()
        listener?.onListing()
        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, startedAt)

        val broken = ArrayList<String>()
        val all = try {
            collectRemoteBooks(rootRef, list, listener, broken)
        } catch (e: Exception) {
            // 原始报错留在日志里，界面只按类别说话
            android.util.Log.w("Scan", "列目录失败：${source.label}", e)
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_FAILED, errorCodeOf(e), startedAt)
            throw e
        }

        // 有目录这次没读到：它底下的书既没被更新也不能算「已删除」，
        // 所以这一轮跳过清理，只把变化写进去
        val skipRemoval = broken.isNotEmpty()
        if (skipRemoval) {
            android.util.Log.w("Scan", "有 ${broken.size} 个目录没读到，本轮不删书：${broken.joinToString()}")
        }

        val books = all

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0

        // 一次读全这个来源已有的书，别每本查一遍库：一千多本时那是三千多次查询
        val known = db.bookDao().bySourceNow(source.id).associateBy { it.docRef }
        val fresh = ArrayList<BookEntity>(books.size)
        val touched = ArrayList<BookEntity>()

        for ((index, book) in books.withIndex()) {
            if (index % 25 == 0) listener?.onProgress(index, books.size)
            val existing = known[book.docRef]
            val singleVolume = if (book.chapters.isEmpty()) 1 else 0
            if (existing == null) {
                added++
                fresh += BookEntity(
                    sourceId = source.id,
                    docRef = book.docRef,
                    title = book.title,
                    titleSort = NaturalSort.key(book.title),
                    format = book.format,
                    byteSize = book.size,
                    chapterTotal = book.chapters.size,
                    isSingleVolume = singleVolume,
                    coverPage = 0,
                    addedAt = now,
                    modifiedAt = now,
                )
            } else if (existing.title != book.title ||
                existing.byteSize != book.size ||
                existing.chapterTotal != book.chapters.size ||
                existing.isSingleVolume != singleVolume
            ) {
                updated++
                touched += existing.copy(
                    title = book.title,
                    titleSort = NaturalSort.key(book.title),
                    byteSize = book.size,
                    chapterTotal = book.chapters.size,
                    isSingleVolume = singleVolume,
                    modifiedAt = now,
                )
            }
        }

        listener?.onProgress(books.size, books.size)

        // 一个事务写完：逐条提交时每条都要刷盘，一千多本会拖到分钟级
        db.withTransaction {
            if (fresh.isNotEmpty()) db.bookDao().insertAll(fresh)
            if (touched.isNotEmpty()) db.bookDao().upsertAll(touched)

            // 清理远端已经没有的条目：按 id 分段删，避免一次塞几千个参数。
            // 本轮有目录没读到就整段跳过——宁可留下几本已删的书，也不把读不到的书连同进度一起清掉
            if (!skipRemoval) {
                val present = books.mapTo(HashSet(books.size)) { it.docRef }
                val gone = known.values.filter { it.docRef !in present }.map { it.id }
                gone.chunked(DELETE_BATCH).forEach { db.bookDao().deleteByIds(it) }
            }
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_DONE,
                if (skipRemoval) SourceEntity.ERROR_PARTIAL else null,
                now,
            )
        }

        // 章要等书有了 id 才写得进去，单独再走一遍
        var chaptered = 0
        val idsByRef = db.bookDao().bySourceNow(source.id).associate { it.docRef to it.id }
        for (book in books.filter { it.chapters.isNotEmpty() }) {
            val bookId = idsByRef[book.docRef] ?: continue
            chaptered++
            db.chapterDao().replaceFor(
                bookId,
                book.chapters.mapIndexed { index, ch ->
                    ChapterEntity(
                        bookId = bookId,
                        ordinal = index,
                        title = ch.title,
                        chapterRef = ch.ref,
                        chapterKind = ch.kind,
                    )
                }
            )
        }

        return Result(
            found = books.size,
            added = added,
            updated = updated,
            removed = if (skipRemoval) 0 else known.size - books.count { it.docRef in known },
            chaptered = chaptered,
            unreachableDirs = broken.size,
        )
    }

    /** 根目录是容器：子目录各是一本书，根下的压缩包 / PDF / EPUB 也各是一本 */
    private fun collectRemoteBooks(
        rootRef: String,
        list: (String) -> List<RemoteEntry>,
        listener: Listener?,
        broken: MutableList<String>,
    ): List<RemoteBook> {
        listener?.onProgress(0, 0)
        val out = ArrayList<RemoteBook>()
        for (entry in list(rootRef)) {
            when {
                entry.isDir -> out += classifyRemoteFolder(entry.ref, entry.name, list, depth = 0, broken = broken)
                ScanRules.isArchive(entry.name) -> out += RemoteBook(
                    docRef = entry.ref,
                    title = ScanRules.titleOf(entry.name),
                    format = BookEntity.FORMAT_ZIP,
                    chapters = emptyList(),
                    size = entry.size,
                )
                ScanRules.isSingleBookFile(entry.name) -> out += RemoteBook(
                    docRef = entry.ref,
                    title = ScanRules.titleOf(entry.name),
                    format = formatOf(entry.name),
                    chapters = emptyList(),
                    size = entry.size,
                )
            }
        }
        return out
    }

    /**
     * 一个远端目录可能是书、章回体，也可能只是装书的容器，所以要往下判。
     * 返回空表表示这个目录里没找到书。
     *
     * 读不到时把目录名记进 [broken]，让调用方这一轮不清理已消失的书——
     * 一个目录超时不该把它的书连同进度书签一起删掉。
     */
    private fun classifyRemoteFolder(
        ref: String,
        name: String,
        list: (String) -> List<RemoteEntry>,
        depth: Int,
        broken: MutableList<String>,
    ): List<RemoteBook> {
        if (depth > MAX_REMOTE_DEPTH) return emptyList()
        val children = try {
            list(ref)
        } catch (e: Exception) {
            android.util.Log.w("Scan", "读子目录失败：$name", e)
            broken += name
            return emptyList()
        }
        if (children.isEmpty()) return emptyList()

        // 目录里直接是图片：这一本就是单行本
        if (children.any { !it.isDir && ScanRules.isImage(it.name) }) {
            return listOf(RemoteBook(ref, name, BookEntity.FORMAT_FOLDER, emptyList()))
        }

        val units = children.filter {
            it.isDir || ScanRules.isArchive(it.name) || ScanRules.isPdf(it.name)
        }
        if (units.isEmpty()) return emptyList()

        // 子项是「章」还是「各自一本」：有文件当章，或某个子目录里已经直接是图片，就按章算
        val chaptered = units.any { !it.isDir } || units.any { unit ->
            unit.isDir && try {
                list(unit.ref).any { !it.isDir && ScanRules.isImage(it.name) }
            } catch (e: Exception) {
                broken += "$name/${unit.name}"
                false
            }
        }

        if (chaptered) {
            return listOf(
                RemoteBook(
                    docRef = ref,
                    title = name,
                    format = BookEntity.FORMAT_FOLDER,
                    chapters = units.map { unit ->
                        RemoteChapter(
                            title = ScanRules.titleOf(unit.name),
                            ref = unit.ref,
                            kind = when {
                                unit.isDir -> ChapterEntity.KIND_FOLDER
                                ScanRules.isPdf(unit.name) -> ChapterEntity.KIND_PDF
                                else -> ChapterEntity.KIND_ARCHIVE
                            },
                        )
                    },
                )
            )
        }

        // 都是「只装书的目录」：再往下走，每个子目录各是一本
        return units.filter { it.isDir }
            .flatMap { classifyRemoteFolder(it.ref, it.name, list, depth + 1, broken) }
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
        // 读不到就抛：一个章节目录读失败，不能让这本书静默消失
        val children = walker.list(node.docId) ?: throw TreeGoneException()
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
