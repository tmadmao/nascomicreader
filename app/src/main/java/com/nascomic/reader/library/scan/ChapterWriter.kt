package com.nascomic.reader.library.scan

import androidx.room.withTransaction
import com.nascomic.reader.core.text.ChapterOrder
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase

/**
 * 把一本书的章写进库。整张章表重建，所以两件容易出错的事都在这里兜住：
 *
 * 一、量过的页数按章的来源引用带过来。章还是那些章，重建不该把它们变回 0 页，
 *     否则详情页目录一片「0 页」，还得再进一次详情页才补回来。
 * 二、章的先后可能变了（旧版远端来源列目录不排序），而进度、书签记的都是章号，
 *     要按来源引用把它们搬到新序上，读到一半的书才不会指到别的章去。
 *
 * 重建章表与搬进度在同一个事务里：中途失败要么全成，要么全不成，不会留下
 * 「章是新序、进度还是旧章号」的半套数据。
 */
class ChapterWriter(private val db: ReaderDatabase) {

    /** 一章在入库前的样子：本地与远端拿到的类型不同，统一成这个再写 */
    class Draft(val title: String, val ref: String, val kind: Int)

    suspend fun write(bookId: Long, drafts: List<Draft>) = db.withTransaction {
        val old = db.chapterDao().forBook(bookId)
            .map { ChapterReorder.ChapterSlot(it.chapterRef, it.pageTotal) }
        val pages = old.associate { it.ref to it.pages }

        val fresh = drafts.mapIndexed { index, draft ->
            ChapterEntity(
                bookId = bookId,
                ordinal = index,
                title = draft.title,
                chapterRef = draft.ref,
                chapterKind = draft.kind,
                pageTotal = pages[draft.ref] ?: 0,
            )
        }
        db.chapterDao().replaceFor(bookId, fresh)

        val newRefs = fresh.map { it.chapterRef }
        if (old.isEmpty() || old.map { it.ref } == newRefs) return@withTransaction

        db.progressDao().forBook(bookId)?.let { progress ->
            val moved = ChapterReorder.newOrdinal(old, newRefs, progress.chapterOrdinal) ?: return@let
            val global = ChapterReorder.newPageGlobal(old, newRefs, moved, progress.pageInChapter)
            db.progressDao().upsert(
                progress.copy(chapterOrdinal = moved, pageGlobal = global ?: progress.pageGlobal)
            )
        }

        for (mark in db.bookmarkDao().forBookNow(bookId)) {
            val moved = ChapterReorder.newOrdinal(old, newRefs, mark.chapterOrdinal) ?: continue
            if (moved != mark.chapterOrdinal) db.bookmarkDao().moveToChapter(mark.id, moved)
        }
    }

    /**
     * 章序与扫描时的排列对不上时（旧版远端来源留下的乱序）就地改回来，
     * 顺手把进度与书签搬过去。本来就对的话一次库都不写。
     *
     * @return 修好之后的章表
     */
    suspend fun ensureNaturalOrder(bookId: Long, chapters: List<ChapterEntity>): List<ChapterEntity> {
        if (chapters.size < 2) return chapters

        val ordered = chapters.sortedWith(ChapterOrder.comparator { it.title })
        if (ordered.map { it.id } == chapters.map { it.id }) return chapters

        write(bookId, ordered.map { Draft(it.title, it.chapterRef, it.chapterKind) })
        return db.chapterDao().forBook(bookId)
    }
}
