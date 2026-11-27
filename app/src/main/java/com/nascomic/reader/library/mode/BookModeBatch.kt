package com.nascomic.reader.library.mode

import android.content.Context
import androidx.room.withTransaction
import androidx.core.net.toUri
import com.nascomic.reader.library.scan.ScanConfig
import kotlinx.coroutines.CancellationException
import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.LocalCopies
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.mode.BookModePlanner.OldBook
import com.nascomic.reader.library.mode.BookModePlanner.OldBookmark
import com.nascomic.reader.library.mode.BookModePlanner.OldChapter
import com.nascomic.reader.library.mode.BookModePlanner.OldProgress
import com.nascomic.reader.library.mode.BookModePlanner.TargetBook
import com.nascomic.reader.library.mode.BookModePlanner.TargetChapter
import com.nascomic.reader.library.scan.BookDraft
import com.nascomic.reader.library.scan.Collection
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.library.scan.Located
import com.nascomic.reader.ui.reader.DiskPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量改书型：预览 + 按组执行。
 *
 * **组**是会被一起重建的最小单位：拆是这本书自己，并是它父目录下的全部书。
 * 一组一个事务，列目录不全、进度书签封面定位不到、页数数不出，整组不动；
 * 别的组不受影响，失败组可以单独重试。
 *
 * 预览和执行之间会再对一次「目录指纹」（子项引用 + 大小 + 修改时间）：
 * 目录变了就拒绝执行，让用户重新预览——否则预览里承诺的书目和真正落库的会对不上。
 */
internal class BookModeBatch(
    private val context: Context,
    private val db: ReaderDatabase,
    private val scanner: LibraryScanner,
    private val opener: BookOpener,
    private val thumbs: ThumbnailStore,
) {

    enum class Target { SERIAL, SINGLE, RESTORE }

    data class Incoming(val bookId: Long, val title: String)

    /** 一组待执行的重建；[plan] 是最后一轮规划的结果 */
    data class Group(
        val sourceId: Long,
        val rootRef: String,
        val rootLabel: String,
        val currentMode: Int,
        val targetMode: Int,
        val selectedIds: Set<Long>,
        val oldIds: List<Long>,
        val targets: List<BookDraft>,
        val plan: BookModePlanner.Plan,
        /** 没勾选但会被一起重建的书 */
        val incoming: List<Incoming>,
        /** 预览时这一组「当前书型」下的书目指纹 */
        val current: List<BookDraft>,
        /** 预览时给出过哪些冲突选项；选项变了说明目录变了，得重新预览 */
        val conflicts: List<BookModePlanner.Conflict>,
        /** 预览时数出来的「章引用 → 页序签名」；页换过了就得重新预览 */
        val pageSignatures: Map<String, String>,
        /** true = 恢复跟随来源（删覆盖），false = 明确钉成目标书型 */
        val restore: Boolean,
        val snapshot: Snapshot,
        val scope: Set<String>,
    )

    data class Snapshot(
        val config: ScanConfig,
        val members: Map<String, BookEntity>,
        val books: List<OldBook>,
    )

    private suspend fun snapshot(source: SourceEntity, root: String, refs: Set<String>, scope: Set<String>): Snapshot = db.withTransaction {
        val config = scanner.checkedConfig(source).scoped(root, scope)
        val members = db.bookDao().bySourceNow(source.id).filter { it.docRef in scope }
            .associateBy { it.docRef }
        Snapshot(config, members, loadOld(source.id, refs).sortedBy { it.id })
    }

    private fun ScanConfig.scoped(root: String, scope: Set<String>): ScanConfig {
        val relative = source.kind != SourceEntity.KIND_LOCAL || source.treeUri?.let {
            it.toUri().authority == "com.android.externalstorage.documents"
        } == true
        val normalized = root.trimEnd('/')
        return copy(
            overrides = overrides.filterKeys { key ->
                val ref = key.trimEnd('/')
                !relative || key in scope || normalized.startsWith("$ref/") || normalized.startsWith("$ref:")
            },
            independent = independent.intersect(scope),
        )
    }

    sealed class Preview {
        data class Ready(val groups: List<Group>, val notes: List<String>) : Preview()
        data class Failed(val message: String) : Preview()
    }

    data class GroupResult(val rootLabel: String, val ok: Boolean, val message: String)

    // ── 预览 ───────────────────────────────────────────────

    suspend fun preview(
        bookIds: List<Long>,
        target: Target,
        choice: BookModePlanner.Choice = BookModePlanner.Choice(),
    ): Preview = withContext(Dispatchers.IO) {
        if (bookIds.isEmpty()) return@withContext Preview.Failed("没有选中书")
        val notes = ArrayList<String>()
        val groups = LinkedHashMap<String, MutableList<Long>>() // key -> selected ids
        val roots = HashMap<String, Root>()

        // 合并要把同一父目录下的书逐一归位：父目录只查一次，兄弟书复用同一趟结果
        val locatedCache = HashMap<String, Located?>()
        val walkMemo = HashMap<Long, MutableMap<String, Located>>()
        val locate: suspend (SourceEntity, String) -> Located? = { source, ref ->
            val key = "${source.id}|$ref"
            if (locatedCache.containsKey(key)) {
                locatedCache[key]
            } else {
                val memo = walkMemo.getOrPut(source.id) { HashMap() }
                scanner.locate(source, ref, memo).also { locatedCache[key] = it }
            }
        }

        for (id in bookIds.distinct()) {
            val book = db.bookDao().get(id) ?: continue
            val source = db.sourceDao().get(book.sourceId) ?: continue
            val resolved = resolve(source, book, target, notes) { ref -> locate(source, ref) } ?: continue
            val key = "${source.id}|${resolved.rootRef}"
            groups.getOrPut(key) { ArrayList() } += id
            roots[key] = resolved
        }

        if (groups.isEmpty()) {
            return@withContext Preview.Failed(
                notes.firstOrNull() ?: "这些书不需要改书型"
            )
        }

        val out = ArrayList<Group>()
        for ((key, ids) in groups) {
            val root = roots.getValue(key)
            val source = db.sourceDao().get(root.sourceId) ?: continue
            val config = try { scanner.checkedConfig(source) } catch (_: LibraryScanner.SourceChanged) { continue }
            val current = when (val c = collect(source, root.rootRef, root.rootLabel, root.currentMode, config)) {
                is Collected.Fail -> {
                    notes += "《${root.rootLabel}》：${c.reason}"
                    continue
                }
                is Collected.Ok -> c.collection
            }
            val targetColl = when (val c = collect(source, root.rootRef, root.rootLabel, root.targetMode, config)) {
                is Collected.Fail -> {
                    notes += "《${root.rootLabel}》：${c.reason}"
                    continue
                }
                is Collected.Ok -> c.collection
            }

            val refs = current.books.mapTo(HashSet()) { it.docRef }
            val scope = current.seen + targetColl.seen + refs + targetColl.books.map { it.docRef } + root.rootRef
            val snapshot = try { snapshot(source, root.rootRef, refs, scope) } catch (_: LibraryScanner.SourceChanged) { continue }
            if (snapshot.config != config.scoped(root.rootRef, scope) || targetColl.books.any { it.docRef !in refs && it.docRef in snapshot.members }) {
                notes += "书库目录或目标书目有变化，请重扫后重新预览"
                continue
            }
            val oldBooks = snapshot.books
            if (oldBooks.isEmpty() || !ids.all { id -> oldBooks.any { it.id == id } }) {
                notes += "《${root.rootLabel}》：库里没有对应的书，先重扫这个书库"
                continue
            }

            val planned = when (val p = planGroup(source, oldBooks, targetColl.books, choice)) {
                is Planned.Fail -> {
                    notes += "《${root.rootLabel}》：${p.reason}"
                    continue
                }
                is Planned.Ok -> p
            }
            val selectedHere = ids.toSet()
            out += Group(
                sourceId = source.id,
                rootRef = root.rootRef,
                rootLabel = root.rootLabel,
                currentMode = root.currentMode,
                targetMode = root.targetMode,
                selectedIds = selectedHere,
                oldIds = oldBooks.map { it.id },
                targets = targetColl.books,
                plan = planned.plan,
                incoming = oldBooks.filter { it.id !in selectedHere }
                    .map { Incoming(it.id, it.displayTitle) },
                current = current.books.sortedBy { it.docRef },
                conflicts = planned.plan.conflicts,
                pageSignatures = planned.signatures,
                restore = target == Target.RESTORE,
                snapshot = snapshot,
                scope = scope,
            )
        }

        if (out.isEmpty()) {
            return@withContext Preview.Failed(notes.firstOrNull() ?: "这些书不需要改书型")
        }
        Preview.Ready(out, notes)
    }

    /** 重试要在此刻的库里重新取书：预览之后书可能已经被拆走 */
    internal suspend fun books(ids: List<Long>): List<BookEntity> =
        withContext(Dispatchers.IO) { ids.distinct().mapNotNull { db.bookDao().get(it) } }

    private sealed class Collected {
        data class Ok(val collection: Collection) : Collected()
        data class Fail(val reason: String) : Collected()
    }

    /**
     * 按某一层、某个书型收集一遍。
     *
     * 要求目录读全；「独立文件书不许并成章」这条规则在扫描器里（全库重扫与这里共用一份），
     * 这里只把它们从**书单**里去掉——它们不参与这一组，也不能被当成旧书删掉。
     */
    private suspend fun collect(
        source: SourceEntity,
        rootRef: String,
        rootLabel: String,
        mode: Int,
        config: ScanConfig,
    ): Collected {
        val raw = runCatching {
            scanner.collectUnder(source, rootRef, rootLabel, mode, config)
        }.getOrElse {
            if (it is CancellationException) throw it
            return Collected.Fail("按${modeName(mode)}读目录失败，这组先不动")
        }
        if (!raw.complete) return Collected.Fail("目录没读全，这组先不动")
        val books = raw.books.filterNot { it.docRef in config.independent }
        return Collected.Ok(Collection(books, raw.seen, raw.complete))
    }

    private class Root(
        val sourceId: Long,
        val rootRef: String,
        val rootLabel: String,
        val currentMode: Int,
        val targetMode: Int,
    )

    /**
     * 选中一本书后，它属于哪一组、往哪个书型走。
     *
     * 只有**目录书**能改书型：一个 zip / pdf / epub 本身就是一册，拆不出东西，
     * 按目录去「并」还会把同级的别的书卷进来。入口与这里各挡一次。
     *
     * 拆（改为单行本）：只有「一本带章的书」才有得拆，根就是它自己，不用去找父目录。
     * 并（改为连载）：只有父目录当前按单行本扫描时才是真的并；父目录本来就按连载扫，
     * 再改也不会改变书目，动它只会把不相干的书卷进来。
     * 来源根目录永远只是容器（两条全库扫描都从根的子项开始），在根上并出来的书
     * 下一次重扫就会被拆回去，所以这里直接拒绝。
     * 恢复：找到这一支最近的覆盖（自己或父目录），根落在覆盖所在的那一层。
     */
    private suspend fun resolve(
        source: SourceEntity,
        book: BookEntity,
        target: Target,
        notes: MutableList<String>,
        locate: suspend (String) -> Located?,
    ): Root? {
        if (book.format != BookEntity.FORMAT_FOLDER) {
            notes += "《${book.displayTitle}》是一册独立文件，不用改书型"
            return null
        }
        val overrideHere = db.modeOverrideDao().get(source.id, book.docRef)
        // 章表才是准的：chapter_total 是扫描时写下的，早先的书可能还没同步
        val hasChapters = db.chapterDao().forBook(book.id).isNotEmpty()

        return when (target) {
            Target.SINGLE -> {
                if (!hasChapters) {
                    notes += "《${book.displayTitle}》已经是一本，不用拆"
                    return null
                }
                Root(source.id, book.docRef, book.displayTitle, SourceEntity.MODE_SERIAL, SourceEntity.MODE_SINGLE)
            }
            Target.SERIAL -> {
                if (hasChapters) {
                    notes += "《${book.displayTitle}》已经是章回体，不用并"
                    return null
                }
                val located = locate(book.docRef)
                if (located == null) {
                    notes += "《${book.displayTitle}》：找不到它所在的目录"
                    return null
                }
                if (scanner.rootRefOf(source) == located.parentRef) {
                    notes += "《${book.displayTitle}》：来源根目录不能并成一本书，先把它们放进同一个子目录"
                    return null
                }
                if (located.parentMode != SourceEntity.MODE_SINGLE) {
                    notes += "《${book.displayTitle}》所在的目录按连载扫描，改书型不会改变书目"
                    return null
                }
                Root(
                    sourceId = source.id,
                    rootRef = located.parentRef,
                    rootLabel = located.parentName.ifBlank { book.displayTitle },
                    currentMode = SourceEntity.MODE_SINGLE,
                    targetMode = SourceEntity.MODE_SERIAL,
                )
            }
            Target.RESTORE -> {
                if (overrideHere != null) {
                    Root(
                        source.id, book.docRef, book.displayTitle,
                        currentMode = overrideHere.mode,
                        targetMode = source.bookMode,
                    )
                } else {
                    val located = locate(book.docRef)
                    val parentOverride = located?.let { db.modeOverrideDao().get(source.id, it.parentRef) }
                    if (located == null || parentOverride == null) {
                        notes += "《${book.displayTitle}》没有单独设过书型"
                        return null
                    }
                    Root(
                        source.id, located.parentRef,
                        located.parentName.ifBlank { book.displayTitle },
                        currentMode = parentOverride.mode,
                        targetMode = source.bookMode,
                    )
                }
            }
        }
    }

    private sealed class Planned {
        data class Ok(
            val plan: BookModePlanner.Plan,
            val measured: Map<String, Int>,
            val signatures: Map<String, String>,
        ) : Planned()

        data class Fail(val reason: String) : Planned()
    }

    /** 规划；页数不够就算不出来，先量再算。量不出来整组不动。 */
    private suspend fun planGroup(
        source: SourceEntity,
        oldBooks: List<OldBook>,
        targets: List<BookDraft>,
        choice: BookModePlanner.Choice,
    ): Planned {
        val measured = HashMap<String, Int>()
        val signatures = HashMap<String, String>()
        val targetBooks = targets.toTargets()
        var plan = BookModePlanner.plan(oldBooks, targetBooks, measured, choice)
        while (true) {
            val need = (plan.missingPages + plan.pageChecks).distinct().filterNot { it in measured }
            if (need.isEmpty()) break
            val layouts = measure(source, oldBooks, need)
            if (layouts.size != need.size) return Planned.Fail("有章节数不出页数，这组先不动")
            layouts.forEach { (ref, layout) ->
                measured[ref] = layout.count
                signatures[ref] = layout.signature
            }
            plan = BookModePlanner.plan(oldBooks, targetBooks, measured, choice)
        }
        if (!plan.canExecute) {
            return Planned.Fail(plan.problems.firstOrNull() ?: "有位置对不上，这组先不动")
        }
        return Planned.Ok(plan, measured, signatures)
    }

    private suspend fun measure(
        source: SourceEntity,
        oldBooks: List<OldBook>,
        refs: List<String>,
    ): Map<String, com.nascomic.reader.format.BookOpener.PageLayout> {
        val out = HashMap<String, com.nascomic.reader.format.BookOpener.PageLayout>()
        refs.forEach { ref ->
            val owner = oldBooks.firstOrNull { it.docRef == ref || it.chapters.any { c -> c.ref == ref } }
                ?: return@forEach
            val entity = db.bookDao().get(owner.id) ?: return@forEach
            val chapters = db.chapterDao().forBook(owner.id)
            opener.pageLayout(entity, source, chapters, ref)?.let { out[ref] = it }
        }
        return out
    }

    // ── 执行 ───────────────────────────────────────────────

    suspend fun execute(group: Group, choice: BookModePlanner.Choice = BookModePlanner.Choice()): GroupResult =
        withContext(Dispatchers.IO) {
            val source = db.sourceDao().get(group.sourceId)
                ?: return@withContext GroupResult(group.rootLabel, false, "书库已经不在了")

            // 两次清单都要读全：远端少读一个目录，就只重建出半组书，
            // 原书却被删掉——这一组宁可不动，也不能交出一份缺了几本的结果
            val refs = group.snapshot.books.mapTo(HashSet()) { it.docRef }
            val beforeIo = try { snapshot(source, group.rootRef, refs, group.scope) } catch (_: LibraryScanner.SourceChanged) { null }
            if (beforeIo == null || beforeIo != group.snapshot) {
                return@withContext GroupResult(group.rootLabel, false, "书库在预览之后变了，请重新预览")
            }
            val config = try { scanner.checkedConfig(source) } catch (_: LibraryScanner.SourceChanged) {
                return@withContext GroupResult(group.rootLabel, false, "书库在预览之后变了，请重新预览")
            }
            if (config.scoped(group.rootRef, group.scope) != beforeIo.config) {
                return@withContext GroupResult(group.rootLabel, false, "书库在预览之后变了，请重新预览")
            }
            val current = when (val c = collect(source, group.rootRef, group.rootLabel, group.currentMode, config)) {
                is Collected.Fail -> return@withContext GroupResult(group.rootLabel, false, c.reason)
                is Collected.Ok -> c.collection
            }
            if (current.books.sortedBy { it.docRef } != group.current) {
                return@withContext GroupResult(group.rootLabel, false, "目录内容变了，请重新预览")
            }

            val oldBooks = beforeIo.books
            val targetColl = when (val c = collect(source, group.rootRef, group.rootLabel, group.targetMode, config)) {
                is Collected.Fail -> return@withContext GroupResult(group.rootLabel, false, c.reason)
                is Collected.Ok -> c.collection
            }
            // 按目标书型收出来的书目要和预览里承诺的一模一样，否则「N 本 → M 本」是假的
            if (targetColl.books.sortedBy { it.docRef } != group.targets.sortedBy { it.docRef }) {
                return@withContext GroupResult(group.rootLabel, false, "目录结构变了，请重新预览")
            }

            val planned = when (val p = planGroup(source, oldBooks, targetColl.books, choice)) {
                is Planned.Fail -> return@withContext GroupResult(group.rootLabel, false, p.reason)
                is Planned.Ok -> p
            }
            // 冲突项（系列 / 封面各选哪一本）变了，之前那次选择就不再成立
            if (planned.plan.conflicts.map { it.copy(defaultBookId = 0) } != group.conflicts.map { it.copy(defaultBookId = 0) }) {
                return@withContext GroupResult(group.rootLabel, false, "冲突项变了，请重新预览")
            }
            // 页数没变不等于还是那几页：p1、p2 换成 p2、p3，进度会悄悄指到另一张图上
            if (planned.signatures != group.pageSignatures) {
                return@withContext GroupResult(group.rootLabel, false, "页序变了，请重新预览")
            }
            val plan = planned.plan
            val totals = totalsOf(oldBooks, planned.measured)
            val now = System.currentTimeMillis()

            val idByRef = HashMap<String, Long>()

            // 事务里只写库；缓存与缩略图等提交成功再动
            val committed = runCatching {
            db.withTransaction {
                // 预检到写事务之间，别人可能改了书库或这几本书：事务里再核一次，
                // 对不上就整体回滚，别拿已经过期的结论覆盖刚写进去的数据
                if (snapshot(source, group.rootRef, refs, group.scope) != beforeIo) throw StaleGroup()
                val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
                // 1. 目标书：同 docRef 的更新，新的插入
                val existing = db.bookDao().bySourceNow(source.id).associateBy { it.docRef }
                for (target in targetColl.books) {
                    val carry = plan.carry[target.docRef]
                    val old = existing[target.docRef]
                    if (old == null) {
                        val alias = carry?.alias
                        val titleSort =
                            if (alias.isNullOrBlank()) NaturalSort.key(target.title) else NaturalSort.key(alias)
                        val id = db.bookDao().insert(
                            BookEntity(
                                sourceId = source.id,
                                docRef = target.docRef,
                                title = target.title,
                                titleSort = titleSort,
                                alias = alias,
                                author = carry?.author ?: resolveAuthor(parsedAuthor(target.title), aliases),
                                volume = carry?.volume ?: parsedVolume(target.title),
                                format = target.format,
                                byteSize = target.size,
                                pageTotal = pageTotalOf(target, totals, 0),
                                chapterTotal = target.chapters.size,
                                coverPage = 0,
                                contentVersion = 0,
                                addedAt = carry?.addedAt?.takeIf { it > 0 } ?: now,
                                modifiedAt = target.modifiedAt,
                                isSingleVolume = if (target.chapters.isEmpty()) 1 else 0,
                                scanState = BookEntity.STATE_READABLE,
                            )
                        )
                        idByRef[target.docRef] = id
                    } else {
                        db.bookDao().upsert(
                            old.copy(
                                title = target.title,
                                titleSort = if (old.alias.isNullOrBlank()) NaturalSort.key(target.title) else old.titleSort,
                                format = target.format,
                                byteSize = target.size,
                                pageTotal = pageTotalOf(target, totals, old.pageTotal),
                                chapterTotal = target.chapters.size,
                                modifiedAt = target.modifiedAt,
                                isSingleVolume = if (target.chapters.isEmpty()) 1 else 0,
                                contentVersion = old.contentVersion + 1,
                            )
                        )
                        idByRef[target.docRef] = old.id
                    }
                }

                // 2. 章表整批重建，页数按引用带过来；用户改过的章名留在目录里
                for (target in targetColl.books) {
                    val bookId = idByRef[target.docRef] ?: continue
                    val drafts = target.chapters.mapIndexed { index, ch ->
                        ChapterEntity(
                            bookId = bookId,
                            ordinal = index,
                            title = plan.chapterTitles[ch.ref] ?: ch.title,
                            chapterRef = ch.ref,
                            chapterKind = ch.kind,
                            pageTotal = totals[ch.ref] ?: 0,
                        )
                    }
                    db.chapterDao().replaceFor(bookId, drafts)
                }

                // 3. 旧书上的进度与书签全部清空，再按新落点写回。
                //    先清后写，书签的 (书, 章, 页) 唯一约束不会互相撞。
                oldBooks.forEach { old ->
                    db.progressDao().delete(old.id)
                    db.bookmarkDao().deleteForBook(old.id)
                }

                // 4. 删掉不再存在的旧书（章、归属、进度、书签跟着级联走）
                val gone = oldBooks.filter { it.docRef !in idByRef.keys }.map { it.id }
                gone.chunked(400).forEach { db.bookDao().deleteByIds(it) }

                // 5. 进度与书签落到新位置
                plan.progress.forEach { move ->
                    val bookId = idByRef[move.targetRef] ?: return@forEach
                    db.progressDao().upsert(
                        ProgressEntity(
                            bookId = bookId,
                            chapterOrdinal = move.chapterOrdinal,
                            pageInChapter = move.pageInChapter,
                            pageGlobal = move.pageGlobal,
                            finished = move.finished,
                            updatedAt = move.updatedAt,
                        )
                    )
                }
                plan.bookmarks.forEach { move ->
                    val bookId = idByRef[move.targetRef] ?: return@forEach
                    db.bookmarkDao().insert(
                        BookmarkEntity(
                            bookId = bookId,
                            chapterOrdinal = move.chapterOrdinal,
                            pageInChapter = move.pageInChapter,
                            note = move.note,
                            createdAt = move.createdAt,
                        )
                    )
                }

                // 6. 系列归属：并组里让用户选的那一项落库。
                //    书已经在同一个系列里就不动它那一行——删了再追加会把它挪到队尾
                plan.series.forEach { move ->
                    val bookId = idByRef[move.targetRef] ?: return@forEach
                    val current = db.seriesDao().membershipOf(bookId)
                    if (current != null && current.seriesId == move.seriesId && current.ordinal == move.ordinal) {
                        return@forEach
                    }
                    db.seriesDao().removeMember(bookId)
                    db.seriesDao().insertAt(move.seriesId, bookId, move.ordinal, now)
                }

                // 7. 封面：先清回自动，再按原页面落
                plan.clearCovers.forEach { ref ->
                    idByRef[ref]?.let { db.bookDao().updateCoverPage(it, 0) }
                }
                plan.covers.forEach { move ->
                    idByRef[move.targetRef]?.let { db.bookDao().updateCoverPage(it, move.page) }
                }

                // 8. 覆盖：恢复就删掉这一层；明确改书型时**总是写**目标书型。
                //    不能因为「目标 = 来源书型」就删——父目录可能是靠上层的覆盖才按单行本扫的，
                //    删掉这一层不等于回到连载，上层覆盖会把它再拆回去。
                if (group.restore) {
                    db.modeOverrideDao().remove(source.id, group.rootRef)
                } else {
                    db.modeOverrideDao().put(
                        com.nascomic.reader.library.data.BookModeOverrideEntity(
                            sourceId = source.id,
                            docRef = group.rootRef,
                            mode = group.targetMode,
                            createdAt = now,
                        )
                    )
                }
            }
            }
            committed.exceptionOrNull()?.let { e ->
                if (e is StaleGroup || e is LibraryScanner.SourceChanged) {
                    return@withContext GroupResult(group.rootLabel, false, "书库在预览之后变了，请重新预览")
                }
                throw e
            }

            // 提交成功之后才动缓存：事务回滚时缓存不该被清掉。
            // 清缓存失败不能报成「这一组失败」——库已经写完了，重跑一遍只会把书再拆并一次
            runCatching { invalidate(oldBooks.map { it.id }, idByRef.values.toList()) }
                .onFailure { android.util.Log.w("BookMode", "清缓存失败：${group.rootLabel}", it) }

            val before = group.oldIds.size
            val after = targetColl.books.size
            val message = if (before == after) "重扫完成：$after 本" else "$before 本 → $after 本"
            GroupResult(group.rootLabel, true, message)
        }

    /** 预检之后书库或这几本书被改过；事务里发现就整体回滚 */
    private class StaleGroup : Exception("书库在预览之后变了")

    private fun invalidate(oldIds: List<Long>, newIds: List<Long>) {
        val cache = DiskPageCache(context)
        (oldIds + newIds).distinct().forEach { id ->
            thumbs.invalidate(id)
            cache.clearBook(id)
        }
        runCatching { LocalCopies.evictBooks(context, oldIds) }
    }

    // ── 读旧书 ─────────────────────────────────────────────

    private suspend fun loadOld(sourceId: Long, refs: Set<String>): List<OldBook> {
        val books = db.bookDao().bySourceNow(sourceId).filter { it.docRef in refs }
        return books.map { book ->
            val chapters = db.chapterDao().forBook(book.id)
            val progress = db.progressDao().forBook(book.id)
            OldBook(
                id = book.id,
                docRef = book.docRef,
                title = book.title,
                alias = book.alias,
                author = book.author,
                volume = book.volume,
                addedAt = book.addedAt,
                pageTotal = book.pageTotal,
                coverPage = book.coverPage,
                chapters = chapters.sortedBy { it.ordinal }.map { OldChapter(it.chapterRef, it.title, it.pageTotal, it.ordinal, it.chapterKind) },
                seriesId = db.seriesDao().membershipOf(book.id)?.seriesId,
                seriesOrdinal = db.seriesDao().membershipOf(book.id)?.ordinal,
                progress = progress?.let {
                    OldProgress(it.chapterOrdinal, it.pageInChapter, it.pageGlobal, it.finished, it.updatedAt)
                },
                bookmarks = db.bookmarkDao().forBookNow(book.id)
                    .map { OldBookmark(it.chapterOrdinal, it.pageInChapter, it.createdAt, it.note) },
            )
        }
    }

    /** 重建章表用的页数：库里记过的 + 规划时补量的 */
    private fun totalsOf(oldBooks: List<OldBook>, measured: Map<String, Int>): Map<String, Int> {
        val totals = HashMap<String, Int>()
        oldBooks.forEach { b ->
            if (b.pageTotal > 0) totals[b.docRef] = b.pageTotal
            b.chapters.forEach { c -> if (c.pageTotal > 0) totals[c.ref] = c.pageTotal }
        }
        measured.forEach { (ref, pages) -> if (pages > 0) totals[ref] = pages }
        return totals
    }

    /**
     * 目标书的页数：带章的按各章页数求和，整本一章的取这一本自己的页数。
     * 有一个数不出来就不写死，交给打开时再量。
     */
    private fun pageTotalOf(target: BookDraft, totals: Map<String, Int>, fallback: Int): Int {
        if (target.chapters.isEmpty()) return totals[target.docRef] ?: fallback
        var sum = 0
        target.chapters.forEach { ch ->
            val pages = totals[ch.ref] ?: return fallback
            if (pages <= 0) return fallback
            sum += pages
        }
        return sum
    }

    private fun List<BookDraft>.toTargets(): List<TargetBook> = map { b ->
        TargetBook(
            docRef = b.docRef,
            title = b.title,
            format = b.format,
            chapters = b.chapters.map { TargetChapter(it.ref, it.title, it.kind) },
        )
    }

    private fun modeName(mode: Int): String =
        if (mode == SourceEntity.MODE_SINGLE) "单行本" else "连载"

    private fun parsedAuthor(title: String): String? =
        com.nascomic.reader.library.series.VolumeNameParser.parse(title).author

    private fun parsedVolume(title: String): String? =
        com.nascomic.reader.library.series.VolumeNameParser.parse(title).volume?.toString()

    private fun resolveAuthor(name: String?, aliases: Map<String, String>): String? =
        name?.let { com.nascomic.reader.library.author.AuthorAliases.resolve(it, aliases) }
            ?.takeIf { it.isNotBlank() }
}
