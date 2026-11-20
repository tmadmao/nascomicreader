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
 * 扫描阶段只记名字、大小和章的结构，**不解包、不数页**——逐本解包会让首次扫描随库规模线性变慢，
 * 页数在打开那本书时再算。
 */
private const val DELETE_BATCH = 400

/** 远端最多往下找几层：装书的容器再深就是目录树本身了，不值得无限递归 */
private const val MAX_REMOTE_DEPTH = 4

class LibraryScanner(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    private val secrets by lazy { SecretStore(context) }

    private val prefs by lazy { com.nascomic.reader.settings.AppPrefs(context) }

    /** 写章：重建章表、带回量过的页数、把进度与书签搬到新的章号上 */
    private val chapters by lazy { ChapterWriter(db) }

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

    /**
     * 扫描被用户取消。
     *
     * 只在「还没往库里写」的阶段抛，所以取消等于这次什么都没发生——书架、进度、书签原样不动。
     */
    class Cancelled : Exception("扫描已取消")

    suspend fun scan(
        source: SourceEntity,
        listener: Listener? = null,
        /** 每列一批目录问一次；true 就停下 */
        isCancelled: () -> Boolean = { false },
    ): Result =
        withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceEntity.KIND_WEBDAV -> return@withContext scanWebDav(source, listener, isCancelled)
            SourceEntity.KIND_SMB -> return@withContext scanSmb(source, listener, isCancelled)
        }
        listener?.onListing()

        val treeUri = source.treeUri ?: throw IllegalArgumentException("来源没有目录授权")
        val walker = SafWalker(context, Uri.parse(treeUri))

        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, System.currentTimeMillis())

        val found = ArrayList<Candidate>()
        try {
            // 列不出来就抛：这一层失败必须让整次扫描失败，不能退化成「这个目录空了」
            val rootChildren = walker.list(walker.rootDocId) ?: throw TreeGoneException()
            val single = source.bookMode == SourceEntity.MODE_SINGLE
            for (node in rootChildren) {
                if (isCancelled()) throw Cancelled()
                collectLocal(walker, node, single, found)
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

        if (isCancelled()) throw Cancelled()

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        var chaptered = 0

        val books = found

        // 一个事务写完：逐条提交会随库规模反复刷盘；中途失败也不会留下半套书
        db.withTransaction {
            for (candidate in books) {
                val existing = db.bookDao().findByRef(source.id, candidate.node.docId)
                val title = ScanRules.titleOf(candidate.node)
                val bookId: Long = if (existing == null) {
                    added++
                    db.bookDao().insert(
                        BookEntity(
                            sourceId = source.id,
                            docRef = candidate.node.docId,
                            title = title,
                            titleSort = NaturalSort.key(title),
                            author = parsedAuthor(title),
                            volume = parsedVolume(title),
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
                            // 用户改过名就沿用他的排序键，别被文件名顶回去
                            titleSort = sortKeyOf(existing, ScanRules.titleOf(candidate.node)),
                            byteSize = candidate.size,
                            chapterTotal = candidate.chapters.size,
                            isSingleVolume = if (candidate.chapters.isEmpty()) 1 else 0,
                            modifiedAt = candidate.modifiedAt,
                        )
                    )
                    existing.id
                }

                // 章表为空也要写一次：这本书可能刚从章回体变成散图，
                // 不写的话旧章会一直留在库里，阅读页还会照着旧章去打开
                if (candidate.chapters.isNotEmpty()) chaptered++
                chapters.write(
                    bookId,
                    candidate.chapters.map { unit ->
                        ChapterWriter.Draft(
                            title = ScanRules.titleOf(unit),
                            ref = unit.docId,
                            kind = when {
                                unit.isDir -> ChapterEntity.KIND_FOLDER
                                ScanRules.isPdf(unit.name) -> ChapterEntity.KIND_PDF
                                else -> ChapterEntity.KIND_ARCHIVE
                            },
                        )
                    },
                )
            }

            // 清理磁盘上已经没有的条目。
            // 按 id 分段删，不走 `doc_ref NOT IN (...)`——单条 SQL 的绑定参数有上限，
            // 书多到一定数量就会撞上（Android 10 及以下更低），扫到一半失败
            val present = books.mapTo(HashSet(books.size)) { it.node.docId }
            val gone = db.bookDao().bySourceNow(source.id)
                .filterNot { it.docRef in present }
                .map { it.id }
            gone.chunked(DELETE_BATCH).forEach { db.bookDao().deleteByIds(it) }

            // 完成状态与书本写在同一个事务里：中途失败就整轮回滚，不会留下「已完成」的假象
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)
        }

        // 扫完就把这个来源的落盘副本全部作废：同一路径的文件被换掉、大小和章数都没变时，
        // 扫描看不出差异，副本名也不会变，留着就会一直放旧内容
        com.nascomic.reader.format.LocalCopies.evictBooks(
            context,
            db.bookDao().idsBySource(source.id),
        )

        Result(
            found = found.size,
            added = added,
            updated = updated,
            removed = (before - books.size).coerceAtLeast(0),
            chaptered = chaptered,
        )
    }

    /** 远端的目录项。目录也算进来，才能认出「一个目录一本书」 */
    internal class RemoteEntry(
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

    private suspend fun scanWebDav(
        source: SourceEntity,
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        val endpoint = source.endpoint ?: throw IllegalArgumentException("来源没有地址")
        val client = WebDavClient(source.account, secrets.get(source.secretRef), source.endpoint)
        val lister: (String) -> List<RemoteEntry> = { dir ->
            client.list(dir).map { RemoteEntry(it.url, it.name, it.isDirectory, it.size) }
        }
        return scanRemote(source, endpoint, lister, listener, isCancelled)
    }

    private suspend fun scanSmb(
        source: SourceEntity,
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        val target = SmbTarget.parse(source.endpoint ?: throw IllegalArgumentException("来源没有地址"))
        val client = SmbClient(target, source.account, secrets.get(source.secretRef))
        val lister: (String) -> List<RemoteEntry> = { dir ->
            client.list(dir).map { RemoteEntry(it.path, it.name, it.isDirectory, it.size) }
        }
        return scanRemote(source, target.path, lister, listener, isCancelled)
    }

    /**
     * 设备测试入口：喂一个假的列目录函数，其余流程与真远端完全一样，不碰网络。
     *
     * 「空清单时保住书库」这条只有走到这里才验得到——真 NAS 很难按需变空。
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun scanRemoteForTest(
        source: SourceEntity,
        rootRef: String,
        list: (String) -> List<RemoteEntry>,
    ): Result = scanRemote(source, rootRef, list, null) { false }

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
        isCancelled: () -> Boolean = { false },
    ): Result {
        val startedAt = System.currentTimeMillis()
        listener?.onListing()
        db.sourceDao().setScanState(source.id, SourceEntity.STATE_SCANNING, null, startedAt)

        val broken = ArrayList<String>()
        val all = try {
            collectRemoteBooks(
                rootRef, list, listener, broken, isCancelled,
                singleBook = source.bookMode == SourceEntity.MODE_SINGLE,
            )
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            // 原始报错留在日志里，界面只按类别说话
            android.util.Log.w("Scan", "列目录失败：${source.label}", e)
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_FAILED, errorCodeOf(e), startedAt)
            throw e
        }

        val now = System.currentTimeMillis()

        // 一次都没扫到、但库里原本有书：远端比本地更容易出现这种情况——
        // 挂载暂时不可见时枚举会「正常结束」并给出空表，照着空表清理等于把这个书库的
        // 书、进度、书签、系列归属一起删掉。停下来等用户确认，和本地同一条规则。
        val before = db.bookDao().countBySource(source.id)
        if (all.isEmpty() && before > 0) {
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_FAILED,
                SourceEntity.ERROR_EMPTY_SCAN,
                now,
            )
            return Result(0, 0, 0, 0, 0, keptAll = true)
        }

        // 有目录这次没读到：它底下的书既没被更新也不能算「已删除」，
        // 所以这一轮跳过清理，只把变化写进去
        val skipRemoval = broken.isNotEmpty()
        if (skipRemoval) {
            android.util.Log.w("Scan", "有 ${broken.size} 个目录没读到，本轮不删书：${broken.joinToString()}")
        }

        val books = all

        if (isCancelled()) throw Cancelled()

        var added = 0
        var updated = 0
        var chaptered = 0

        // 一次读全这个来源已有的书，别每本查一遍库：逐本查会随库规模累积成大量查询
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
                    author = parsedAuthor(book.title),
                    volume = parsedVolume(book.title),
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
                    // 用户改过名就沿用他的排序键
                    titleSort = sortKeyOf(existing, book.title),
                    byteSize = book.size,
                    chapterTotal = book.chapters.size,
                    isSingleVolume = singleVolume,
                    modifiedAt = now,
                )
            }
        }

        listener?.onProgress(books.size, books.size)

        // 一个事务写完：逐条提交会随库规模反复刷盘
        db.withTransaction {
            if (fresh.isNotEmpty()) db.bookDao().insertAll(fresh)
            if (touched.isNotEmpty()) db.bookDao().upsertAll(touched)

            // 清理远端已经没有的条目：按 id 分段删，避免单条 SQL 绑定过多参数。
            // 本轮有目录没读到就整段跳过——宁可留下几本已删的书，也不把读不到的书连同进度一起清掉
            if (!skipRemoval) {
                val present = books.mapTo(HashSet(books.size)) { it.docRef }
                val gone = known.values.filter { it.docRef !in present }.map { it.id }
                gone.chunked(DELETE_BATCH).forEach { db.bookDao().deleteByIds(it) }
            }
            // 章要等书有了 id 才写得进去。与书、与完成状态放在同一个事务里：
            // 否则进程在中间退出时，状态已经是「完成」，章表却还是上一次扫描的
            val idsByRef = db.bookDao().bySourceNow(source.id).associate { it.docRef to it.id }
            for (book in books) {
                val bookId = idsByRef[book.docRef] ?: continue
                if (book.chapters.isNotEmpty()) chaptered++
                chapters.write(
                    bookId,
                    book.chapters.map { ch -> ChapterWriter.Draft(ch.title, ch.ref, ch.kind) },
                )
            }

            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_DONE,
                if (skipRemoval) SourceEntity.ERROR_PARTIAL else null,
                now,
            )
        }

        // 理由同上：扫描之后，这个来源的旧副本都不该再被当成当前内容
        com.nascomic.reader.format.LocalCopies.evictBooks(
            context,
            db.bookDao().idsBySource(source.id),
        )

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
        isCancelled: () -> Boolean = { false },
        singleBook: Boolean = false,
    ): List<RemoteBook> {
        listener?.onProgress(0, 0)
        val out = ArrayList<RemoteBook>()
        for (entry in list(rootRef)) {
            if (isCancelled()) throw Cancelled()
            when {
                entry.isDir -> out += classifyRemoteFolder(
                    entry.ref, entry.name, list, depth = 0, broken = broken,
                    isCancelled = isCancelled, singleBook = singleBook,
                )
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
        isCancelled: () -> Boolean = { false },
        singleBook: Boolean = false,
    ): List<RemoteBook> {
        if (isCancelled()) throw Cancelled()
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

        // 单行本：这一层不合并——子目录继续下钻，压缩包 / PDF 各自成书
        if (singleBook) {
            val books = ArrayList<RemoteBook>()
            for (child in children) {
                when {
                    child.isDir -> books += classifyRemoteFolder(
                        child.ref, child.name, list, depth + 1, broken,
                        isCancelled = isCancelled, singleBook = true,
                    )
                    ScanRules.isArchive(child.name) -> books += RemoteBook(
                        docRef = child.ref,
                        title = ScanRules.titleOf(child.name),
                        format = BookEntity.FORMAT_ZIP,
                        chapters = emptyList(),
                        size = child.size,
                    )
                    ScanRules.isPdf(child.name) -> books += RemoteBook(
                        docRef = child.ref,
                        title = ScanRules.titleOf(child.name),
                        format = BookEntity.FORMAT_PDF,
                        chapters = emptyList(),
                        size = child.size,
                    )
                    else -> Unit
                }
            }
            return books
        }

        // 服务端给什么顺序就是什么顺序，章会乱：这里自己按章的先后排一遍
        val units = ScanRules.sortUnits(
            children.filter { it.isDir || ScanRules.isArchive(it.name) || ScanRules.isPdf(it.name) },
        ) { if (it.isDir) it.name else ScanRules.titleOf(it.name) }
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
            .flatMap {
                classifyRemoteFolder(
                    it.ref, it.name, list, depth + 1, broken,
                    isCancelled = isCancelled, singleBook = singleBook,
                )
            }
    }

    /** 账号不对和连不上要分开报：前者提示改密码，后者提示查地址与网络 */
    private fun errorCodeOf(e: Exception): Int = when {
        e is WebDavException && (e.status == 401 || e.status == 403) -> SourceEntity.ERROR_AUTH
        e is SmbException && e.authFailed -> SourceEntity.ERROR_AUTH
        else -> SourceEntity.ERROR_UNREACHABLE
    }

    /** 书名里的作者：只从文件名取，读不出来就留空，不去猜 */
    private fun parsedAuthor(title: String): String? =
        com.nascomic.reader.library.series.VolumeNameParser.parse(title).author

    /** 卷号存成字符串：库里是 TEXT，空的就留空 */
    private fun parsedVolume(title: String): String? =
        com.nascomic.reader.library.series.VolumeNameParser.parse(title).volume?.toString()

    /** 用户改过名（alias 非空）就沿用他排好的顺序，否则按来源标题现算 */
    private fun sortKeyOf(existing: BookEntity, title: String): String =
        if (existing.alias.isNullOrBlank()) NaturalSort.key(title) else existing.titleSort

    private fun formatOf(name: String): Int = when {
        ScanRules.isEpub(name) -> BookEntity.FORMAT_EPUB
        ScanRules.isPdf(name) -> BookEntity.FORMAT_PDF
        ScanRules.isArchive(name) -> BookEntity.FORMAT_ZIP
        else -> BookEntity.FORMAT_ZIP
    }

    /**
     * 把一个子项变成书，必要时继续下钻。
     *
     * 连载模式只看这一层；单行本模式下，遇到「没有图片、只有子目录」的文件夹继续往下走，
     * 每个直接含图的目录各自成书——图集库就是这种存法。
     */
    private fun collectLocal(
        walker: SafWalker,
        node: ScanNode,
        single: Boolean,
        out: MutableList<Candidate>,
    ) {
        if (!node.isDir) {
            when {
                ScanRules.isArchive(node.name) -> out += archiveAsBook(node)
                ScanRules.isPdf(node.name) -> out += pdfAsBook(node)
                ScanRules.isEpub(node.name) -> out += epubAsBook(node)
            }
            return
        }
        // 读不到就抛：一个子目录读失败，不能让这本书静默消失
        val children = walker.list(node.docId) ?: throw TreeGoneException()
        when (ScanRules.folderKind(children, single)) {
            ScanRules.FolderKind.DIRECT_PAGES ->
                out += Candidate(node, BookEntity.FORMAT_FOLDER, emptyList())

            ScanRules.FolderKind.BY_CHAPTER ->
                out += Candidate(node, BookEntity.FORMAT_FOLDER, ScanRules.chapterUnitsOf(children))

            ScanRules.FolderKind.CONTAINER ->
                children.forEach { child -> collectLocal(walker, child, single, out) }

            ScanRules.FolderKind.EMPTY -> Unit
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
