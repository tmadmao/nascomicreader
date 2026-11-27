package com.nascomic.reader.library.mode

/**
 * 改书型时「旧书目 → 新书目」的账。
 *
 * 拆并会删掉书行、重建章表，进度与书签记的章号会全部失效。这里只做纯计算：
 * 拿组内旧书与按目标书型重新收集出来的书单，算出哪些书留、哪些删、哪些建，
 * 以及每一处进度 / 书签 / 系列 / 自选封面落在新书的哪一页。
 *
 * 三件事刻意做成硬约束：
 * 1. **用「章引用 + 章内页」定位**，不拿旧全局页号直接搬——拆并后页号全变，引用才稳定。
 * 2. **算不出就是算不出**：前面有章没量过页数时全局页号无从得知，返回待量清单，
 *    由调用方去数；不拿 0 糊过去，否则进度会跳到书头。
 * 3. **定位不到整组不动**：任何一处进度、书签、封面找不到落点，[Plan.problems] 非空，
 *    调用方必须放弃整组，不能删一半留一半。
 */
object BookModePlanner {

    data class OldChapter(val ref: String, val title: String, val pageTotal: Int, val ordinal: Int = 0, val kind: Int = 0)

    /** 组内的一本旧书；章表为空表示整本一章 */
    data class OldBook(
        val id: Long,
        val docRef: String,
        val title: String,
        val alias: String?,
        val author: String?,
        val volume: String?,
        val addedAt: Long,
        val pageTotal: Int,
        /** 全书页号；0 表示没自选过封面 */
        val coverPage: Int,
        val chapters: List<OldChapter>,
        val seriesId: Long?,
        /** 原系列里的位置；挪书时照这个位置插回去，别一律排到队尾 */
        val seriesOrdinal: Int? = null,
        val progress: OldProgress?,
        val bookmarks: List<OldBookmark>,
    ) {
        val displayTitle: String get() = alias?.takeIf { it.isNotBlank() } ?: title
    }

    data class OldProgress(
        val chapterOrdinal: Int,
        val pageInChapter: Int,
        val pageGlobal: Int,
        val finished: Boolean,
        val updatedAt: Long,
    )

    data class OldBookmark(
        val chapterOrdinal: Int,
        val pageInChapter: Int,
        val createdAt: Long,
        val note: String? = null,
    )

    data class TargetChapter(val ref: String, val title: String, val kind: Int)

    /** 按目标书型重新收集出来的一本书 */
    data class TargetBook(
        val docRef: String,
        val title: String,
        val format: Int,
        val chapters: List<TargetChapter>,
    )

    /** 冲突时用户的选择；null 表示用默认（读过的那本，没有则第一本） */
    data class Choice(val seriesFromBookId: Long? = null, val coverFromBookId: Long? = null)

    data class ProgressMove(
        val targetRef: String,
        val chapterOrdinal: Int,
        val pageInChapter: Int,
        val pageGlobal: Int,
        val finished: Boolean,
        val updatedAt: Long,
    )

    data class BookmarkMove(
        val targetRef: String,
        val chapterOrdinal: Int,
        val pageInChapter: Int,
        val createdAt: Long,
        val note: String? = null,
    )

    /** [ordinal] 是这个成员原来在系列里的位置；null 表示旧记录没记过 */
    data class SeriesMove(val targetRef: String, val seriesId: Long, val ordinal: Int? = null)

    data class CoverMove(val targetRef: String, val page: Int)

    /** 新建目标书时要带过来的用户数据；主本才有别名与卷号 */
    data class Carry(
        val alias: String? = null,
        val author: String? = null,
        val volume: String? = null,
        val addedAt: Long = 0,
    )

    enum class ConflictKind { SERIES, COVER }

    data class ConflictOption(val bookId: Long, val label: String, val page: Int? = null)

    /** 一条「保留哪一项」的提示；[defaultBookId] 是不选时保留的那本 */
    data class Conflict(
        val kind: ConflictKind,
        val targetRef: String,
        val options: List<ConflictOption>,
        val defaultBookId: Long,
    )

    data class Plan(
        /** 仍在原 docRef 上、章结构没变 */
        val kept: List<Long>,
        /** 仍在原 docRef 上、章结构变了 */
        val updated: List<Long>,
        val removed: List<Long>,
        val created: List<TargetBook>,
        val carry: Map<String, Carry>,
        val progress: List<ProgressMove>,
        val bookmarks: List<BookmarkMove>,
        val series: List<SeriesMove>,
        val covers: List<CoverMove>,
        /** 全局页号算不出来时，需要先量页数的引用 */
        val missingPages: List<String>,
        /**
         * 进度 / 书签 / 封面落在这些章上，它们的页数要重新数一遍。
         * 库里的章页数是上次扫描时记的，作者删了几页它不会跟着变——
         * 不核对就把页号搬过去，会写出一条指向不存在页的进度。
         */
        val pageChecks: List<String>,
        /** 定位不到 / 结构对不上；非空就整组不动 */
        val problems: List<String>,
        val conflicts: List<Conflict>,
        /** 目标书先把自选封面清回 0，再按 [covers] 落 */
        val clearCovers: List<String>,
        /** 合并时旧书改过的书名要留在目录里：章引用 → 显示名 */
        val chapterTitles: Map<String, String>,
    ) {
        val canExecute: Boolean get() = problems.isEmpty() && missingPages.isEmpty()
    }

    /**
     * @param pageTotals 调用方量到的页数（键是书或章的引用），补在旧记录之上
     */
    fun plan(
        oldBooks: List<OldBook>,
        targetBooks: List<TargetBook>,
        pageTotals: Map<String, Int> = emptyMap(),
        choice: Choice = Choice(),
    ): Plan {
        val problems = ArrayList<String>()
        val missing = LinkedHashSet<String>()
        val checks = LinkedHashSet<String>()
        val conflicts = ArrayList<Conflict>()

        val totals = HashMap<String, Int>()
        oldBooks.forEach { book ->
            if (book.pageTotal > 0) totals[book.docRef] = book.pageTotal
            book.chapters.forEach { ch -> if (ch.pageTotal > 0) totals[ch.ref] = ch.pageTotal }
        }
        pageTotals.forEach { (ref, pages) -> if (pages > 0) totals[ref] = pages }

        val targets = targetBooks.distinctBy { it.docRef }
        val targetByRef = HashMap<String, TargetBook>(targets.size * 2)
        targets.forEach { t ->
            targetByRef[t.docRef] = t
            t.chapters.forEach { c -> targetByRef[c.ref] = t }
        }

        val oldByRef = oldBooks.associateBy { it.docRef }
        val oldRefs = oldByRef.keys
        val targetRefs = targets.mapTo(HashSet()) { it.docRef }

        // 每本旧书能落到哪些新书：同一个 docRef、它的章被拆成书、或它自己成了别人的章
        val targetsOf = HashMap<Long, List<TargetBook>>()
        oldBooks.forEach { old ->
            targetsOf[old.id] = targets.filter { t ->
                t.docRef == old.docRef ||
                    old.chapters.any { it.ref == t.docRef } ||
                    t.chapters.any { it.ref == old.docRef }
            }
        }

        // 每本旧书的主落点：进度在哪本新书上就归哪本，没有进度取第一个派生出来的
        val mainTargetOfOld = HashMap<Long, String>()
        oldBooks.forEach { old ->
            val derived = targetsOf[old.id].orEmpty()
            if (derived.isEmpty()) {
                problems += "《${old.displayTitle}》在目标书型里没有对应书或章节"
                return@forEach
            }
            val atProgress = old.progress?.let { p ->
                (locate(old, p.chapterOrdinal, p.pageInChapter, totals) as? Locate.Ok)?.let { loc ->
                    derived.firstOrNull { t -> owns(t, loc.ref) }
                }
            }
            mainTargetOfOld[old.id] = (atProgress ?: derived.first()).docRef
        }

        val kept = ArrayList<Long>()
        val updated = ArrayList<Long>()
        val removed = ArrayList<Long>()
        oldBooks.forEach { old ->
            val target = targetByRef[old.docRef]
            when {
                old.docRef !in targetRefs -> removed += old.id
                target != null && old.chapters.map { it.ref } == target.chapters.map { it.ref } ->
                    kept += old.id
                else -> updated += old.id
            }
        }
        val created = targets.filter { it.docRef !in oldRefs }

        // ── 进度：同一本新书只留最近读的一条 ─────────────
        val progressByTarget = LinkedHashMap<String, ProgressMove>()
        oldBooks.forEach { old ->
            val p = old.progress ?: return@forEach
            val loc = when (val l = locate(old, p.chapterOrdinal, p.pageInChapter, totals)) {
                is Locate.Bad -> {
                    problems += "《${old.displayTitle}》的章节记录对不上"
                    return@forEach
                }
                is Locate.OutOfRange -> {
                    problems += "《${old.displayTitle}》的进度超出了这一章的页数"
                    return@forEach
                }
                is Locate.Ok -> l
            }
            checks += loc.ref
            val target = targetByRef[loc.ref] ?: run {
                problems += "《${old.displayTitle}》的进度找不到落点"
                return@forEach
            }
            val pos = position(target, loc.ref, loc.page, totals, missing, checks)
            if (pos == null) {
                if (!prefixUnknown(target, loc.ref, totals)) {
                    problems += "《${old.displayTitle}》的进度找不到落点"
                }
                return@forEach
            }
            val move = ProgressMove(
                targetRef = target.docRef,
                chapterOrdinal = pos.ordinal,
                pageInChapter = pos.page,
                pageGlobal = pos.global,
                finished = p.finished,
                updatedAt = p.updatedAt,
            )
            val prev = progressByTarget[target.docRef]
            if (prev == null || move.updatedAt > prev.updatedAt) progressByTarget[target.docRef] = move
        }

        // ── 书签：映射后按 (新书, 章, 页) 去重，留最早的一条 ──
        val bookmarkAt = LinkedHashMap<Triple<String, Int, Int>, BookmarkMove>()
        oldBooks.forEach { old ->
            old.bookmarks.forEach { mark ->
                val loc = when (val l = locate(old, mark.chapterOrdinal, mark.pageInChapter, totals)) {
                    is Locate.Bad -> {
                        problems += "《${old.displayTitle}》的章节记录对不上"
                        return@forEach
                    }
                    is Locate.OutOfRange -> {
                        problems += "《${old.displayTitle}》的书签超出了这一章的页数"
                        return@forEach
                    }
                    is Locate.Ok -> l
                }
                checks += loc.ref
                val target = targetByRef[loc.ref] ?: run {
                    problems += "《${old.displayTitle}》的书签找不到落点"
                    return@forEach
                }
                val pos = position(target, loc.ref, loc.page, totals, missing, checks)
                if (pos == null) {
                    if (!prefixUnknown(target, loc.ref, totals)) {
                        problems += "《${old.displayTitle}》的书签找不到落点"
                    }
                    return@forEach
                }
                val key = Triple(target.docRef, pos.ordinal, pos.page)
                val prior = bookmarkAt[key]
                bookmarkAt[key] = when {
                    prior == null -> BookmarkMove(target.docRef, pos.ordinal, pos.page, mark.createdAt, mark.note)
                    // 同一格上有两条时留最早那条，但备注不能因为「这条更早」就丢掉
                    mark.createdAt < prior.createdAt ->
                        BookmarkMove(target.docRef, pos.ordinal, pos.page, mark.createdAt, mark.note ?: prior.note)
                    else -> prior.copy(note = prior.note ?: mark.note)
                }
            }
        }

        // ── 系列：跟主本走；同一本新书被塞进两个系列时给选择 ──
        val seriesCandidates = LinkedHashMap<String, MutableList<OldBook>>()
        oldBooks.filter { it.seriesId != null }.forEach { old ->
            val targetRef = mainTargetOfOld[old.id] ?: return@forEach
            seriesCandidates.getOrPut(targetRef) { ArrayList() } += old
        }
        val series = ArrayList<SeriesMove>()
        seriesCandidates.forEach { (targetRef, olds) ->
            val distinct = olds.mapNotNull { it.seriesId }.distinct()
            if (distinct.isEmpty()) return@forEach
            val picked = when {
                distinct.size == 1 -> olds.first { it.seriesId == distinct.first() }
                choice.seriesFromBookId != null -> olds.firstOrNull { it.id == choice.seriesFromBookId }
                else -> olds.maxByOrNull { it.progress?.updatedAt ?: 0 } ?: olds.first()
            } ?: return@forEach
            if (distinct.size > 1) {
                conflicts += Conflict(
                    kind = ConflictKind.SERIES,
                    targetRef = targetRef,
                    options = olds.distinctBy { it.seriesId }.map {
                        ConflictOption(it.id, "《${it.displayTitle}》所在的系列")
                    },
                    defaultBookId = picked.id,
                )
            }
            picked.seriesId?.let { series += SeriesMove(targetRef, it, picked.seriesOrdinal) }
        }

        // ── 自选封面：随原页面迁移；同一本新书收到多张时给选择 ──
        val coverCandidates = LinkedHashMap<String, MutableList<CoverCandidate>>()
        oldBooks.filter { it.coverPage > 0 }.forEach { old ->
            val needed = ArrayList<String>()
            val loc = when (val l = locateCover(old, totals, checks) { needed += it }) {
                is Locate.Bad -> {
                    // 缺页数的记待量；位置本身超界的才算定位失败
                    if (needed.isEmpty()) {
                        problems += "《${old.displayTitle}》的自选封面找不到落点"
                    } else {
                        missing += needed
                    }
                    return@forEach
                }
                is Locate.OutOfRange -> {
                    problems += "《${old.displayTitle}》的自选封面超出了这本书的页数"
                    return@forEach
                }
                is Locate.Ok -> l
            }
            checks += loc.ref
            val target = targetByRef[loc.ref] ?: run {
                problems += "《${old.displayTitle}》的自选封面找不到落点"
                return@forEach
            }
            val pos = position(target, loc.ref, loc.page, totals, missing, checks) ?: return@forEach
            coverCandidates.getOrPut(target.docRef) { ArrayList() } +=
                CoverCandidate(
                    bookId = old.id,
                    label = "《${old.displayTitle}》第 ${old.coverPage + 1} 页",
                    page = pos.global,
                )
        }
        val covers = ArrayList<CoverMove>()
        coverCandidates.forEach { (targetRef, list) ->
            val distinct = list.distinctBy { it.page }
            val defaultBook = oldBooks
                .filter { mainTargetOfOld[it.id] == targetRef && it.progress != null }
                .maxByOrNull { it.progress!!.updatedAt }?.id
            val picked = when {
                distinct.size == 1 -> distinct.first()
                choice.coverFromBookId != null ->
                    list.firstOrNull { it.bookId == choice.coverFromBookId } ?: distinct.first()
                else -> list.firstOrNull { it.bookId == defaultBook } ?: list.first()
            }
            if (distinct.size > 1) {
                conflicts += Conflict(
                    kind = ConflictKind.COVER,
                    targetRef = targetRef,
                    options = list.map { ConflictOption(it.bookId, it.label, it.page) },
                    defaultBookId = picked.bookId,
                )
            }
            covers += CoverMove(targetRef, picked.page)
        }

        // ── 新建书要带过来的用户数据 ──────────────────────
        val carry = HashMap<String, Carry>()
        targets.filter { it.docRef !in oldRefs }.forEach { target ->
            val olds = oldBooks.filter { o -> targetsOf[o.id].orEmpty().any { it.docRef == target.docRef } }
            if (olds.isEmpty()) return@forEach
            // 主本 = 这批旧书里读得最近的那本；没进度时取第一本
            val main = olds.maxByOrNull { it.progress?.updatedAt ?: Long.MIN_VALUE } ?: olds.first()
            val isMain = olds.any { mainTargetOfOld[it.id] == target.docRef }
            carry[target.docRef] = Carry(
                // 拆出来的书只有主本继承用户改过的书名与卷号，其余按文件名重新解析
                alias = if (isMain) main.alias else null,
                author = main.author,
                volume = if (isMain) main.volume else null,
                addedAt = main.addedAt,
            )
        }

        // 合并后章标题用旧书的显示名：用户改过的名字不能因为并成章就丢了
        val chapterTitles = HashMap<String, String>()
        targets.forEach { target ->
            target.chapters.forEach { ch ->
                val old = oldByRef[ch.ref] ?: return@forEach
                if (!old.alias.isNullOrBlank()) chapterTitles[ch.ref] = old.displayTitle
            }
        }

        return Plan(
            kept = kept,
            updated = updated,
            removed = removed,
            created = created,
            carry = carry,
            progress = progressByTarget.values.toList(),
            bookmarks = bookmarkAt.values.toList(),
            series = series,
            covers = covers,
            missingPages = missing.toList(),
            pageChecks = checks.toList(),
            problems = problems.distinct(),
            conflicts = conflicts,
            clearCovers = targets.map { it.docRef },
            chapterTitles = chapterTitles,
        )
    }

    // ── 位置计算 ───────────────────────────────────────────

    /** 一处位置换成「章引用 + 章内页」的结果；两种失败要分开报，用户才知道该重扫还是该核对页数 */
    private sealed class Locate {
        data class Ok(val ref: String, val page: Int) : Locate()

        /** 章号对不上：库里的章表与进度打架，只能重扫 */
        object Bad : Locate()

        /** 章内页超出这一章现在的页数：多半是预览之后文件被改过，要求重新预览 */
        object OutOfRange : Locate()
    }

    private data class Position(val ordinal: Int, val page: Int, val global: Int)

    private data class CoverCandidate(val bookId: Long, val label: String, val page: Int)

    private fun owns(target: TargetBook, ref: String): Boolean =
        target.docRef == ref || target.chapters.any { it.ref == ref }

    /**
     * 旧书里的一处位置换成「章引用 + 章内页」。
     *
     * 页数是量出来的（[totals] 里由调用方补进来）就顺手核一下边界：
     * 库里记着两页、实际只剩一页时，第 2 页的进度没有落点，不能照样搬过去。
     */
    private fun locate(old: OldBook, ordinal: Int, page: Int, totals: Map<String, Int>): Locate {
        if (old.chapters.isEmpty()) {
            if (ordinal != 0) return Locate.Bad
            if (page < 0) return Locate.Bad
            val pages = totals[old.docRef] ?: 0
            if (pages > 0 && page >= pages) return Locate.OutOfRange
            return Locate.Ok(old.docRef, page)
        }
        val ch = old.chapters.getOrNull(ordinal) ?: return Locate.Bad
        if (page < 0) return Locate.Bad
        val pages = totals[ch.ref] ?: 0
        if (pages > 0 && page >= pages) return Locate.OutOfRange
        return Locate.Ok(ch.ref, page)
    }

    /**
     * 自选封面的全书页号 → 章引用 + 章内页。
     * 章页数没量过时把缺的引用交给 [need]，这一轮不算位置；页号超出整本时返回 null 且不报缺页。
     */
    private fun locateCover(old: OldBook, totals: Map<String, Int>, checks: MutableSet<String>, need: (String) -> Unit): Locate {
        if (old.chapters.isEmpty()) {
            val pages = totals[old.docRef] ?: 0
            if (pages > 0 && old.coverPage >= pages) return Locate.OutOfRange
            return Locate.Ok(old.docRef, old.coverPage)
        }
        var rest = old.coverPage
        old.chapters.forEach { ch ->
            checks += ch.ref
            val pages = totals[ch.ref] ?: 0
            if (pages <= 0) {
                need(ch.ref)
                return Locate.Bad
            }
            if (rest < pages) return Locate.Ok(ch.ref, rest)
            rest -= pages
        }
        // 走完整本还没落地：记的页号比这本书实际页数大
        return Locate.OutOfRange
    }

    /** 章前有没量过页数的章节时，全局页号算不出来，把它们记进待量 */
    private fun position(
        target: TargetBook,
        ref: String,
        page: Int,
        totals: Map<String, Int>,
        missing: MutableSet<String>,
        checks: MutableSet<String>,
    ): Position? {
        if (ref == target.docRef) return Position(0, page, page)
        val index = target.chapters.indexOfFirst { it.ref == ref }
        if (index < 0) return null
        var prefix = 0
        for (i in 0 until index) {
            val chapterRef = target.chapters[i].ref
            checks += chapterRef
            val pages = totals[chapterRef] ?: 0
            if (pages <= 0) {
                missing += chapterRef
            } else {
                prefix += pages
            }
        }
        if ((0 until index).any { target.chapters[it].ref in missing }) return null
        return Position(index, page, prefix + page)
    }

    private fun prefixUnknown(target: TargetBook, ref: String, totals: Map<String, Int>): Boolean {
        val index = target.chapters.indexOfFirst { it.ref == ref }
        if (index <= 0) return false
        return (0 until index).any { (totals[target.chapters[it].ref] ?: 0) <= 0 }
    }
}
