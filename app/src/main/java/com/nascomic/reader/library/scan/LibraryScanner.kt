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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

internal data class ScanConfig(
    val source: SourceEntity,
    val overrides: Map<String, Int>,
    val independent: Set<String>,
)

private fun SourceEntity.scanIdentity() = copy(label = "", lastScanAt = null, scanState = 0, errorCode = null)

private fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw LibraryScanner.Cancelled()
}

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

    /** 列目录、可撤销的事务写入、不可取消的提交。 */
    interface Listener {
        fun onListing() {}
        fun onProgress(done: Int, total: Int) {}
        /** 在主线程同步调用；此时关闭取消入口，扫描器紧接着检查已受理的取消。 */
        fun onCommitting() {}
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
    private class TreeGoneException : Exception("无法完整读取目录，请检查存储是否可用及目录授权")

    /**
     * 扫描被用户取消。
     *
     * 在提交前抛；若事务已开始，整轮回滚，书架、进度、书签原样不动。
     */
    class Cancelled : Exception("扫描已取消")

    /**
     * 扫描进行到写库这一步时，来源的地址已经变了。
     *
     * 与 [Cancelled] 分开：取消是用户主动点的，不该打扰；这个却是「这次扫的是旧地址，
     * 继续写会把旧地址下的书当成已消失全删掉」，必须停下来让用户重扫。
     */
    class SourceChanged : Exception("来源地址已变")

    internal suspend fun checkedConfig(source: SourceEntity): ScanConfig = db.withTransaction {
        val actual = db.sourceDao().get(source.id)?.scanIdentity()
        if (actual != source.scanIdentity()) throw SourceChanged()
        ScanConfig(source.scanIdentity(), db.modeOverrideDao().forSource(source.id)
            .associate { it.docRef to it.mode }, independentFileRefs(source.id))
    }

    private suspend fun checkBeforeWrite(source: SourceEntity, expected: ScanConfig) {
        if (checkedConfig(source) != expected) throw SourceChanged()
    }

    private suspend fun trackScan(
        source: SourceEntity,
        failureCode: (Exception) -> Int,
        block: suspend (ScanConfig) -> Result,
    ): Result {
        val startedAt = System.currentTimeMillis()
        val dao = db.sourceDao()
        val (previous, config) = db.withTransaction {
            val config = checkedConfig(source)
            val previous = dao.get(source.id) ?: throw SourceChanged()
            dao.setScanState(source.id, SourceEntity.STATE_SCANNING, null, startedAt)
            previous to config
        }
        try {
            return block(config)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val scanning = SourceEntity.STATE_SCANNING
                when (e) {
                    is Cancelled, is CancellationException -> dao.finishScanIfCurrent(
                        source.id, scanning, startedAt, previous.scanState, previous.errorCode, previous.lastScanAt,
                    )
                    is SourceChanged -> dao.finishScanIfCurrent(
                        source.id, scanning, startedAt, SourceEntity.STATE_IDLE, null, startedAt,
                    )
                    else -> dao.finishScanIfCurrent(
                        source.id, scanning, startedAt, SourceEntity.STATE_FAILED, failureCode(e), startedAt,
                    )
                }
            }
            throw e
        }
    }

    private suspend fun commitBoundary(listener: Listener?, isCancelled: () -> Boolean) {
        if (listener == null) {
            checkCancelled(isCancelled)
        } else {
            // 与点击取消串行，避免入口还亮着时事务已经越过最后一个检查点。
            withContext(Dispatchers.Main.immediate) {
                checkCancelled(isCancelled)
                listener.onCommitting()
                checkCancelled(isCancelled)
            }
        }
    }

    suspend fun scan(
        source: SourceEntity,
        listener: Listener? = null,
        /** 可从列目录到事务提交前请求取消。回调在主线程与扫描线程间读取。 */
        isCancelled: () -> Boolean = { false },
    ): Result = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceEntity.KIND_WEBDAV -> scanWebDav(source, listener, isCancelled)
            SourceEntity.KIND_SMB -> scanSmb(source, listener, isCancelled)
            else -> scanLocal(source, listener, isCancelled) {
                val uri = source.treeUri ?: throw IllegalArgumentException("来源没有目录授权")
                SafWalker(context, Uri.parse(uri))
            }
        }
    }

    internal suspend fun scanLocal(
        source: SourceEntity,
        listener: Listener?,
        isCancelled: () -> Boolean,
        openWalker: () -> SafWalker,
    ): Result = trackScan(source, { SourceEntity.ERROR_TREE_PERMISSION_LOST }) { config ->
        val walker = openWalker()
        listener?.onListing()

        val found = ArrayList<Candidate>()
        // 单本书型覆盖：按节点记，扫描时逐节点取「覆盖 ?: 来源书型」
        val overrides = config.overrides
        // 这次真正见到的节点（含被列出但没下钻的）：清覆盖时只删没再见到的
        val seenRefs = HashSet<String>()
        val independent = config.independent
        val listings = LocalScanListings(walker, seenRefs, isCancelled, independent)
        val single = source.bookMode == SourceEntity.MODE_SINGLE
        for (node in listings.list(walker.rootDocId)) {
            checkCancelled(isCancelled)
            collectLocal(listings::list, node, single, overrides, found, independent)
        }
        // 分类会在图片目录或章节处停止；补认这些子树中已经独立入库的文件。
        val emitted = found.mapTo(HashSet()) { it.node.docId }
        for (ref in independent - emitted) {
            checkCancelled(isCancelled)
            val file = listings.recheckFile(ref) ?: continue
            collectLocal(listings::list, file, single, overrides, found, independent)
        }
        checkCancelled(isCancelled)

        // 一次都没扫到、但库里原本有书：多半是授权或挂载出了问题，
        // 这时候删书等于把用户的书架、进度、书签一起清了，所以停下来等用户确认
        val before = db.bookDao().countBySource(source.id)
        checkCancelled(isCancelled)
        if (found.isEmpty() && before > 0) {
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_FAILED,
                SourceEntity.ERROR_EMPTY_SCAN,
                System.currentTimeMillis()
            )
            return@trackScan Result(
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
        var removed = 0
        var chaptered = 0

        val books = found

        // 一个事务写完：逐条提交会随库规模反复刷盘；中途失败也不会留下半套书
        db.withTransaction {
            checkCancelled(isCancelled)
            checkBeforeWrite(source, config)
            // 别名表在读的同一次事务里取：先读表、再去写书，中间被别人合并了，
            // 就会按已经删掉的旧别名把书写回旧名字
            val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
            for ((index, candidate) in books.withIndex()) {
                if (index % 25 == 0) listener?.onProgress(index, books.size)
                checkCancelled(isCancelled)
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
                            author = resolveAuthor(parsedAuthor(title), aliases),
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
                if (candidate.format == BookEntity.FORMAT_FOLDER) {
                    db.bookDao().bumpContentVersion(bookId)
                }
            }
            listener?.onProgress(books.size, books.size)
            checkCancelled(isCancelled)

            // 清理磁盘上已经没有的条目。
            // 按 id 分段删，不走 `doc_ref NOT IN (...)`——单条 SQL 的绑定参数有上限，
            // 书多到一定数量就会撞上（Android 10 及以下更低），扫到一半失败
            val present = books.mapTo(HashSet(books.size)) { it.node.docId }
            val gone = db.bookDao().bySourceNow(source.id)
                .filterNot { it.docRef in present }
                .map { it.id }
            removed = gone.size
            gone.chunked(DELETE_BATCH).forEach {
                checkCancelled(isCancelled)
                db.bookDao().deleteByIds(it)
            }

            // 书没了，挂在它身上的覆盖也该走；完整扫描才清，能确认「这次真的没见到」
            pruneOverrides(source.id, seenRefs)

            // 完成状态与书本写在同一个事务里：中途失败就整轮回滚，不会留下「已完成」的假象
            db.sourceDao().setScanState(source.id, SourceEntity.STATE_DONE, null, now)
            commitBoundary(listener, isCancelled)
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
            removed = removed,
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
        return scanRemote(source, endpoint, webDavLister(source), listener, isCancelled)
    }

    private suspend fun scanSmb(
        source: SourceEntity,
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
    ): Result {
        val target = SmbTarget.parse(source.endpoint ?: throw IllegalArgumentException("来源没有地址"))
        return SmbClient(target, source.account, secrets.get(source.secretRef)).use { client ->
            scanRemote(source, target.path, smbLister(client), listener, isCancelled)
        }
    }

    /** WebDAV 列目录。扫描与改书型的单点重建共用，避免两处各写一份 */
    private fun webDavLister(source: SourceEntity): (String) -> List<RemoteEntry> {
        val client = WebDavClient(source.account, secrets.get(source.secretRef), source.endpoint)
        return { dir -> client.list(dir).map { RemoteEntry(it.url, it.name, it.isDirectory, it.size) } }
    }

    private fun smbLister(client: SmbClient): (String) -> List<RemoteEntry> =
        { dir -> client.list(dir).map { RemoteEntry(it.path, it.name, it.isDirectory, it.size) } }

    /**
     * 设备测试入口：喂一个假的列目录函数，其余流程与真远端完全一样，不碰网络。
     *
     * 「空清单时保住书库」这条只有走到这里才验得到——真 NAS 很难按需变空。
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun scanRemoteForTest(
        source: SourceEntity,
        rootRef: String,
        listener: Listener? = null,
        isCancelled: () -> Boolean = { false },
        list: (String) -> List<RemoteEntry>,
    ): Result = scanRemote(source, rootRef, list, listener, isCancelled)

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
    ): Result = trackScan(source, ::errorCodeOf) { config ->
        listener?.onListing()

        val broken = ArrayList<String>()
        // 覆盖与本地同一套：按节点取「覆盖 ?: 来源书型」，扫描成功后清掉消失节点的覆盖
        val overrides = config.overrides
        val seenRefs = HashSet<String>()
        val all = try {
            collectRemoteBooks(
                rootRef, { ref ->
                    checkCancelled(isCancelled)
                    try {
                        list(ref)
                    } finally {
                        checkCancelled(isCancelled)
                    }
                }, listener, broken, isCancelled,
                inheritedSingle = source.bookMode == SourceEntity.MODE_SINGLE,
                overrides = overrides,
                seen = seenRefs,
                independent = config.independent,
            )
        } catch (e: Exception) {
            // 原始报错留在日志里，界面只按类别说话
            if (e !is Cancelled && e !is CancellationException) {
                android.util.Log.w("Scan", "列目录失败：${source.label}", e)
            }
            throw e
        }
        checkCancelled(isCancelled)

        val now = System.currentTimeMillis()

        // 一次都没扫到、但库里原本有书：远端比本地更容易出现这种情况——
        // 挂载暂时不可见时枚举会「正常结束」并给出空表，照着空表清理等于把这个书库的
        // 书、进度、书签、系列归属一起删掉。停下来等用户确认，和本地同一条规则。
        val before = db.bookDao().countBySource(source.id)
        checkCancelled(isCancelled)
        if (all.isEmpty() && before > 0) {
            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_FAILED,
                SourceEntity.ERROR_EMPTY_SCAN,
                now,
            )
            return@trackScan Result(0, 0, 0, 0, 0, keptAll = true)
        }

        // 有目录这次没读到：它底下的书既没被更新也不能算「已删除」，
        // 所以这一轮跳过清理，只把变化写进去
        val skipRemoval = broken.isNotEmpty()
        if (skipRemoval) {
            android.util.Log.w("Scan", "有 ${broken.size} 个目录没读到，本轮不删书：${broken.joinToString()}")
        }

        val books = all

        var added = 0
        var updated = 0
        var chaptered = 0

        // 一次读全这个来源已有的书，别每本查一遍库：逐本查会随库规模累积成大量查询
        var removed = 0
        val fresh = ArrayList<BookEntity>(books.size)
        val touched = ArrayList<BookEntity>()

        // 一个事务写完：逐条提交会随库规模反复刷盘
        db.withTransaction {
            checkCancelled(isCancelled)
            checkBeforeWrite(source, config)
            val known = db.bookDao().bySourceNow(source.id).associateBy { it.docRef }

            // 别名表与写入同一次事务：读完到写完之间有人合并了作者，这一轮就会按旧别名落库
            val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
            for ((index, book) in books.withIndex()) {
                if (index % 25 == 0) listener?.onProgress(index, books.size)
                checkCancelled(isCancelled)
                val existing = known[book.docRef]
                val singleVolume = if (book.chapters.isEmpty()) 1 else 0
                if (existing == null) {
                    added++
                    fresh += BookEntity(
                        sourceId = source.id,
                        docRef = book.docRef,
                        title = book.title,
                        titleSort = NaturalSort.key(book.title),
                        author = resolveAuthor(parsedAuthor(book.title), aliases),
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
            checkCancelled(isCancelled)

            if (fresh.isNotEmpty()) db.bookDao().insertAll(fresh)
            if (touched.isNotEmpty()) db.bookDao().upsertAll(touched)

            // 清理远端已经没有的条目：按 id 分段删，避免单条 SQL 绑定过多参数。
            // 本轮有目录没读到就整段跳过——宁可留下几本已删的书，也不把读不到的书连同进度一起清掉
            if (!skipRemoval) {
                val present = books.mapTo(HashSet(books.size)) { it.docRef }
                val gone = known.values.filter { it.docRef !in present }.map { it.id }
                removed = gone.size
                gone.chunked(DELETE_BATCH).forEach {
                    checkCancelled(isCancelled)
                    db.bookDao().deleteByIds(it)
                }
                pruneOverrides(source.id, seenRefs)
            }
            // 章要等书有了 id 才写得进去。与书、与完成状态放在同一个事务里：
            // 否则进程在中间退出时，状态已经是「完成」，章表却还是上一次扫描的
            val idsByRef = db.bookDao().bySourceNow(source.id).associate { it.docRef to it.id }
            for (book in books) {
                checkCancelled(isCancelled)
                val bookId = idsByRef[book.docRef] ?: continue
                if (book.chapters.isNotEmpty()) chaptered++
                chapters.write(
                    bookId,
                    book.chapters.map { ch -> ChapterWriter.Draft(ch.title, ch.ref, ch.kind) },
                )
                // 目录书换图后字节数仍为 0，页缓存键不变；重扫成功即视为内容可能变了
                if (book.format == BookEntity.FORMAT_FOLDER) {
                    db.bookDao().bumpContentVersion(bookId)
                }
            }

            db.sourceDao().setScanState(
                source.id,
                SourceEntity.STATE_DONE,
                if (skipRemoval) SourceEntity.ERROR_PARTIAL else null,
                now,
            )
            commitBoundary(listener, isCancelled)
        }

        // 理由同上：扫描之后，这个来源的旧副本都不该再被当成当前内容
        com.nascomic.reader.format.LocalCopies.evictBooks(
            context,
            db.bookDao().idsBySource(source.id),
        )

        Result(
            found = books.size,
            added = added,
            updated = updated,
            removed = removed,
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
        inheritedSingle: Boolean = false,
        overrides: Map<String, Int> = emptyMap(),
        seen: MutableSet<String> = HashSet(),
        independent: Set<String> = emptySet(),
    ): List<RemoteBook> {
        listener?.onProgress(0, 0)
        val out = ArrayList<RemoteBook>()
        val rootEntries = list(rootRef)
        rootEntries.forEach { seen += it.ref }
        for (entry in rootEntries) {
            if (isCancelled()) throw Cancelled()
            when {
                entry.isDir -> out += classifyRemoteFolder(
                    entry.ref, entry.name, list, depth = 0, broken = broken,
                    isCancelled = isCancelled, inheritedSingle = inheritedSingle,
                    overrides = overrides, seen = seen, independent = independent,
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
        inheritedSingle: Boolean = false,
        overrides: Map<String, Int> = emptyMap(),
        seen: MutableSet<String> = HashSet(),
        independent: Set<String> = emptySet(),
    ): List<RemoteBook> {
        if (isCancelled()) throw Cancelled()
        if (depth > MAX_REMOTE_DEPTH) return emptyList()
        val children = try {
            list(ref)
        } catch (e: Exception) {
            if (e is Cancelled || e is CancellationException) throw e
            android.util.Log.w("Scan", "读子目录失败：$name", e)
            broken += name
            return emptyList()
        }
        seen += ref
        children.forEach { seen += it.ref }
        if (children.isEmpty()) return emptyList()

        // 覆盖作用于该节点以下的扫描，遇到更近的覆盖就换那个
        val single = when (overrides[ref]) {
            SourceEntity.MODE_SINGLE -> true
            SourceEntity.MODE_SERIAL -> false
            else -> inheritedSingle
        }

        fun protectedAt(nodes: List<RemoteEntry>) = nodes.filter { it.ref in independent }.mapNotNull(::remoteFileBook)
        val protected = protectedAt(children)
        val listed = HashMap<String, List<RemoteEntry>>()
        fun readChild(child: RemoteEntry): List<RemoteEntry> = listed.getOrPut(child.ref) {
            if (isCancelled()) throw Cancelled()
            try {
                list(child.ref).also { entries ->
                    seen += child.ref
                    entries.forEach { seen += it.ref }
                }
            } catch (e: Exception) {
                if (e is Cancelled || e is CancellationException) throw e
                broken += "$name/${child.name}"
                emptyList()
            }
        }
        fun preserved(parent: String, nodes: List<RemoteEntry>): List<RemoteBook> = nodes.filter { child ->
            val ref = child.ref.trimEnd('/')
            child.isDir && ref.startsWith(parent.trimEnd('/') + "/") &&
                independent.any { it.startsWith("$ref/") }
        }.flatMap { child ->
            val entries = readChild(child)
            protectedAt(entries) + preserved(child.ref, entries)
        }

        if (children.any { !it.isDir && ScanRules.isImage(it.name) }) {
            return protected + preserved(ref, children) + RemoteBook(ref, name, BookEntity.FORMAT_FOLDER, emptyList())
        }
        if (single) {
            return children.flatMap { child ->
                if (child.isDir) classifyRemoteFolder(
                    child.ref, child.name, list, depth + 1, broken,
                    isCancelled = isCancelled, inheritedSingle = true,
                    overrides = overrides, seen = seen, independent = independent,
                ) else listOfNotNull(remoteFileBook(child))
            }
        }

        // 服务端给什么顺序就是什么顺序，章会乱：这里自己按章的先后排一遍
        val units = ScanRules.sortUnits(
            children.filter { it.isDir || ScanRules.isArchive(it.name) || ScanRules.isPdf(it.name) },
        ) { if (it.isDir) it.name else ScanRules.titleOf(it.name) }
        if (units.isEmpty()) return protected

        // 子项是「章」还是「各自一本」：有文件当章，或某个子目录里已经直接是图片，就按章算
        val chaptered = units.any { !it.isDir } || units.any { unit ->
            unit.isDir && readChild(unit).any { !it.isDir && ScanRules.isImage(it.name) }
        }

        if (chaptered) {
            val kept = units.filterNot { !it.isDir && it.ref in independent }
            if (kept.isEmpty()) return protected
            return protected + preserved(ref, children) + listOf(
                RemoteBook(
                    docRef = ref,
                    title = name,
                    format = BookEntity.FORMAT_FOLDER,
                    chapters = kept.map { unit ->
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
        return protected + units.filter { it.isDir }
            .flatMap {
                classifyRemoteFolder(
                    it.ref, it.name, list, depth + 1, broken,
                    isCancelled = isCancelled, inheritedSingle = single,
                    overrides = overrides, seen = seen, independent = independent,
                )
            }
    }

    private fun remoteFileBook(child: RemoteEntry): RemoteBook? =
        if (!child.isDir && (ScanRules.isArchive(child.name) || ScanRules.isSingleBookFile(child.name))) {
            RemoteBook(child.ref, ScanRules.titleOf(child.name), formatOf(child.name), emptyList(), child.size)
        } else null

    /**
     * 改书型用：按指定书型把某个节点（含它自己）重新收集一遍。
     *
     * 与全库扫描共用同一套判定，所以「拆开 / 并起来」之后的全库重扫能得到同一批书；
     * 覆盖表在 root 上被强制写成 [mode]，其余节点仍按各自的覆盖走。
     */
    internal suspend fun collectUnder(
        source: SourceEntity,
        rootRef: String,
        rootName: String,
        mode: Int,
        config: ScanConfig,
    ): Collection = withContext(Dispatchers.IO) {
        val overrides = config.overrides.toMutableMap()
        overrides[rootRef] = mode
        // 与全库扫描共用同一条保护：独立文件书在任何一层都不被并成章
        val independent = config.independent

        when (source.kind) {
            SourceEntity.KIND_WEBDAV, SourceEntity.KIND_SMB -> {
                val seen = HashSet<String>()
                val broken = ArrayList<String>()
                fun collect(list: (String) -> List<RemoteEntry>) = classifyRemoteFolder(
                    ref = rootRef, name = rootName, list = list, depth = 0, broken = broken,
                    inheritedSingle = mode == SourceEntity.MODE_SINGLE, overrides = overrides,
                    seen = seen, independent = independent,
                )
                val books = if (source.kind == SourceEntity.KIND_SMB) {
                    val target = SmbTarget.parse(source.endpoint ?: error("来源没有地址"))
                    SmbClient(target, source.account, secrets.get(source.secretRef)).use { collect(smbLister(it)) }
                } else collect(webDavLister(source))
                Collection(books.map { it.toDraft() }, seen, broken.isEmpty())
            }
            else -> {
                val walker = SafWalker(context, Uri.parse(source.treeUri ?: throw IllegalArgumentException("来源没有目录授权")))
                val out = ArrayList<Candidate>()
                val seen = HashSet<String>()
                val listings = LocalScanListings(walker, seen)
                collectLocal(
                    listings::list,
                    ScanNode(docId = rootRef, name = rootName, isDir = true),
                    inheritedSingle = mode == SourceEntity.MODE_SINGLE,
                    overrides = overrides,
                    out = out,
                    independent = independent,
                )
                Collection(out.map { it.toDraft() }, seen, complete = true)
            }
        }
    }

    private class LocalScanListings(
        private val walker: SafWalker,
        private val seen: MutableSet<String>,
        private val isCancelled: () -> Boolean = { false },
        independent: Set<String> = emptySet(),
    ) {
        private val listings = HashMap<String, List<ScanNode>>()
        private val byId = HashMap<String, Map<String, ScanNode>>()
        private val retainedParents = independent.flatMapTo(HashSet(), walker::storageParentIds)

        fun list(parent: String): List<ScanNode> {
            checkCancelled(isCancelled)
            listings[parent]?.let { return it }
            val children = walker.list(parent, requireComplete = true)
            checkCancelled(isCancelled)
            if (children == null) throw TreeGoneException()
            seen += parent
            children.forEach { seen += it.docId }
            if (parent in retainedParents) listings[parent] = children
            return children
        }

        fun recheckFile(ref: String): ScanNode? {
            val path = walker.pathTo(ref)
            checkCancelled(isCancelled)
            if (path != null && path.size >= 2) {
                // 仅以完整的目录清单确认消失。系统 SAF 对已删文件的单项查询可能直接返回 null Cursor。
                for (index in 1..path.lastIndex) {
                    val parent = path[index - 1]
                    val children = byId.getOrPut(parent) { list(parent).associateBy { it.docId } }
                    val child = children[path[index]] ?: return null
                    if (index == path.lastIndex) return child.takeUnless { it.isDir }
                    if (!child.isDir) return null
                }
            }
            // 不透明 ID 且提供方没有给出路径时，单项查询只能证明存在，不能证明已从父目录删除。
            val file = walker.fileMetadata(ref)
            checkCancelled(isCancelled)
            return file?.also { seen += it.docId } ?: throw TreeGoneException()
        }
    }

    /**
     * 找一本书的父目录与父目录的有效书型。
     *
     * 本地与远端的做法不一样，原因不同：
     * - **远端**：引用就是 URL / 共享路径，父目录直接从字符串切出来，再沿祖先链把覆盖叠上去。
     *   不用从根遍历——同一层合并几本书时，遍历会把每一个无关分支重复走一遍。
     * - **本地**：系统文档 id 不保证是路径，只能从根逐层列目录找；找到的每个节点都记进
     *   [memo]，同一父目录下的兄弟书复用同一趟结果。
     */
    internal suspend fun locate(
        source: SourceEntity,
        targetRef: String,
        memo: MutableMap<String, Located>? = null,
    ): Located? = withContext(Dispatchers.IO) {
        val overrides = db.modeOverrideDao().forSource(source.id).associate { it.docRef to it.mode }
        if (source.kind == SourceEntity.KIND_WEBDAV || source.kind == SourceEntity.KIND_SMB) {
            return@withContext locateByPath(source, targetRef, overrides)
        }
        memo?.get(targetRef)?.let { return@withContext it }

        var steps = 0
        val limit = 50_000
        val walker = SafWalker(context, Uri.parse(source.treeUri ?: return@withContext null))
        walker.pathTo(targetRef)?.takeIf { it.size >= 2 }?.let { chain ->
            val parent = chain[chain.lastIndex - 1]
            var mode = source.bookMode
            chain.drop(1).dropLast(1).forEach { mode = overrides[it] ?: mode }
            val parentName = if (parent == walker.rootDocId) "" else walker.displayName(parent).orEmpty()
            val children = walker.list(parent) ?: return@withContext null
            children.forEach { child -> memo?.put(child.docId, Located(parent, parentName, mode, child.name, child.isDir)) }
            return@withContext children.firstOrNull { it.docId == targetRef }
                ?.let { Located(parent, parentName, mode, it.name, it.isDir) }
        }
        val queue = ArrayDeque<LocalPending>()
        fun queueChildren(children: List<ScanNode>, parentRef: String, parentName: String, parentMode: Int): Located? {
            var found: Located? = null
            for (node in children) {
                val position = Located(parentRef, parentName, parentMode, node.name, node.isDir)
                memo?.put(node.docId, position)
                if (node.docId == targetRef) found = position
                if (node.isDir) queue += LocalPending(node, parentMode)
            }
            return found
        }
        val root = walker.list(walker.rootDocId) ?: return@withContext null
        queueChildren(root, walker.rootDocId, "", source.bookMode)?.let { return@withContext it }
        val expanded = HashSet<String>()
        while (queue.isNotEmpty() && steps++ < limit) {
            val pending = queue.removeFirst()
            if (!expanded.add(pending.node.docId)) continue
            val mode = overrides[pending.node.docId] ?: pending.parentMode
            val children = walker.list(pending.node.docId) ?: continue
            queueChildren(children, pending.node.docId, pending.node.name, mode)?.let { return@withContext it }
        }
        null
    }

    /**
     * 远端按路径推父级：只读数据库里的覆盖，不发一个请求。
     *
     * 父级书型 = 从来源根往下逐层叠覆盖，与扫描时「覆盖 ?: 上一层」是同一条规则。
     */
    private fun locateByPath(
        source: SourceEntity,
        targetRef: String,
        overrides: Map<String, Int>,
    ): Located? {
        val root = rootRefOf(source) ?: return null
        val dav = source.kind == SourceEntity.KIND_WEBDAV
        val ref = targetRef.trimEnd('/')
        val rootTrim = root.trimEnd('/')
        val below = if (rootTrim.isEmpty()) ref else {
            val prefix = "$rootTrim/"
            if (!ref.startsWith(prefix)) return null
            ref.removePrefix(prefix)
        }
        val parts = below.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (parts.size == 1) return Located(root, "", source.bookMode, nameOfRef(source, ref), true)
        var parent = rootTrim
        var mode = source.bookMode
        for (segment in parts.dropLast(1)) {
            parent = if (parent.isEmpty()) segment else "$parent/$segment"
            mode = overrides[parent] ?: (if (dav) overrides["$parent/"] else null) ?: mode
        }
        val parentRef = when {
            !dav -> parent
            "$parent/" in overrides -> "$parent/"
            parent in overrides -> parent
            targetRef.endsWith('/') -> "$parent/"
            else -> parent
        }
        return Located(parentRef, nameOfRef(source, parent), mode, nameOfRef(source, ref), true)
    }

    /** 引用最后一段的显示名；WebDAV 的引用是百分号编码的，要还原 */
    private fun nameOfRef(source: SourceEntity, ref: String): String {
        val seg = ref.trimEnd('/').substringAfterLast('/')
        if (source.kind != SourceEntity.KIND_WEBDAV) return seg
        return Uri.decode(seg)
    }

    /**
     * 库里已经以单个文件独立成书的引用（zip / pdf / epub）。
     *
     * 这些节点**不再被并成章**：局部改书型与全库重扫共用这一条，
     * 否则合并出来的连载书在下次全扫时会把旁边那本独立的压缩包吞掉，
     * 连带删掉它的进度与书签。想把它并进去，先删掉那条书。
     */
    internal suspend fun independentFileRefs(sourceId: Long): Set<String> =
        db.bookDao().bySourceNow(sourceId)
            .filter { it.format != BookEntity.FORMAT_FOLDER }
            .mapTo(HashSet()) { it.docRef }

    /**
     * 来源的根节点引用。
     *
     * 两条全库扫描路径都从根的子项开始，根自己**永远**只是容器——不入书、不套覆盖。
     * 改书型那边要按同一条规则判断「这一组是不是落在根上」，所以这里把根引用摆出来。
     */
    internal fun rootRefOf(source: SourceEntity): String? = when (source.kind) {
        SourceEntity.KIND_WEBDAV -> source.endpoint
        SourceEntity.KIND_SMB -> runCatching { SmbTarget.parse(source.endpoint ?: "") }.getOrNull()?.path
        else -> source.treeUri?.let { runCatching { SafWalker(context, Uri.parse(it)).rootDocId }.getOrNull() }
    }

    private class LocalPending(val node: ScanNode, val parentMode: Int)

    private class RemotePending(
        val entry: RemoteEntry,
        val parentRef: String,
        val parentName: String,
        val parentMode: Int,
    )

    private fun Candidate.toDraft() = BookDraft(
        docRef = node.docId,
        title = ScanRules.titleOf(node),
        format = format,
        chapters = chapters.map { ch ->
            ChapterDraft(
                ref = ch.docId,
                title = ScanRules.titleOf(ch),
                kind = when {
                    ch.isDir -> ChapterEntity.KIND_FOLDER
                    ScanRules.isPdf(ch.name) -> ChapterEntity.KIND_PDF
                    else -> ChapterEntity.KIND_ARCHIVE
                },
            )
        },
        size = node.size,
        modifiedAt = node.lastModified,
    )

    private fun RemoteBook.toDraft() = BookDraft(
        docRef = docRef,
        title = title,
        format = format,
        chapters = chapters.map { ChapterDraft(it.ref, it.title, it.kind) },
        size = size,
        modifiedAt = 0,
    )

    /** 账号不对和连不上要分开报：前者提示改密码，后者提示查地址与网络 */
    private fun errorCodeOf(e: Exception): Int = when {
        e is WebDavException && (e.status == 401 || e.status == 403) -> SourceEntity.ERROR_AUTH
        e is SmbException && e.authFailed -> SourceEntity.ERROR_AUTH
        else -> SourceEntity.ERROR_UNREACHABLE
    }

    /** 书名里的作者：只从文件名取，读不出来就留空，不去猜 */
    private fun parsedAuthor(title: String): String? =
        com.nascomic.reader.library.series.VolumeNameParser.parse(title).author

    /** 解析出的旧写法先过别名表，落库的永远是正名 */
    private fun resolveAuthor(name: String?, aliases: Map<String, String>): String? =
        name?.let { com.nascomic.reader.library.author.AuthorAliases.resolve(it, aliases) }
            ?.takeIf { it.isNotBlank() }

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
     * 有效书型逐节点取「覆盖 ?: 上一层」：单行本模式下，遇到「没有图片、只有子目录」的文件夹
     * 继续往下走，每个直接含图的目录各自成书——图集库就是这种存法。
     * 覆盖按节点记，所以拆开的父目录本身不是书，也能继续作用于下面的扫描。
     *
     */
    private fun collectLocal(
        list: (String) -> List<ScanNode>,
        node: ScanNode,
        inheritedSingle: Boolean,
        overrides: Map<String, Int>,
        out: MutableList<Candidate>,
        independent: Set<String> = emptySet(),
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
        val children = list(node.docId)

        val single = when (overrides[node.docId]) {
            SourceEntity.MODE_SINGLE -> true
            SourceEntity.MODE_SERIAL -> false
            else -> inheritedSingle
        }
        when (ScanRules.folderKind(children, single)) {
            ScanRules.FolderKind.DIRECT_PAGES -> {
                out += Candidate(node, BookEntity.FORMAT_FOLDER, emptyList())
                children.filter { !it.isDir && it.docId in independent }.forEach {
                    collectLocal(list, it, single, overrides, out, independent)
                }
            }

            ScanRules.FolderKind.BY_CHAPTER -> {
                val all = ScanRules.chapterUnitsOf(children)
                val units = all.filterNot { !it.isDir && it.docId in independent }
                if (units.isEmpty()) {
                    // 这一层只剩独立文件书：不能把它们并成章，退回到「子项各自成书」
                    children.forEach { collectLocal(list, it, true, overrides, out, independent) }
                } else {
                    out += Candidate(node, BookEntity.FORMAT_FOLDER, units)
                    // 被挡下来的独立文件书仍然是一本书：不写出来，这一轮清理就会把它删掉
                    children.filter { !it.isDir && it.docId in independent }.forEach {
                        collectLocal(list, it, single, overrides, out, independent)
                    }
                }
            }

            ScanRules.FolderKind.CONTAINER ->
                children.forEach { child -> collectLocal(list, child, single, overrides, out, independent) }

            ScanRules.FolderKind.EMPTY -> children.filter { !it.isDir && it.docId in independent }.forEach {
                collectLocal(list, it, single, overrides, out, independent)
            }
        }
    }

    /** 完整扫描里没再见到的节点，覆盖行一并清掉；空表不进 SQL，避免拼出 `IN ()` */
    private suspend fun pruneOverrides(sourceId: Long, seen: Set<String>) {
        val stale = db.modeOverrideDao().refsOf(sourceId).filterNot { it in seen }
        if (stale.isEmpty()) return
        stale.chunked(DELETE_BATCH).forEach { db.modeOverrideDao().deleteRefs(sourceId, it) }
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

/** 一次局部收集的结果：书单、见到的节点（清覆盖用）、这一支是否读全 */
internal class Collection(
    val books: List<BookDraft>,
    val seen: Set<String>,
    val complete: Boolean,
)

/** 收集出来的书，与扫描器的内部候选解耦，方便改书型那边复用 */
internal data class BookDraft(
    val docRef: String,
    val title: String,
    val format: Int,
    val chapters: List<ChapterDraft>,
    val size: Long,
    val modifiedAt: Long,
)

internal data class ChapterDraft(val ref: String, val title: String, val kind: Int)

/** 一个节点在树里的位置：父目录、父目录的有效书型 */
internal data class Located(
    val parentRef: String,
    val parentName: String,
    val parentMode: Int,
    val name: String,
    val isDir: Boolean,
)
