package com.nascomic.reader.library.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY added_at ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY added_at ASC")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE kind = :kind AND tree_uri = :treeUri LIMIT 1")
    suspend fun findByTreeUri(kind: Int, treeUri: String): SourceEntity?

    @Query("UPDATE sources SET scan_state = :state, error_code = :errorCode, last_scan_at = :at WHERE id = :id")
    suspend fun setScanState(id: Long, state: Int, errorCode: Int?, at: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>): List<Long>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE source_id = :sourceId AND doc_ref = :docRef LIMIT 1")
    suspend fun findByRef(sourceId: Long, docRef: String): BookEntity?

    /** 书架默认顺序：最近添加 */
    @Query("SELECT * FROM books ORDER BY added_at DESC, id DESC")
    fun observeRecentAdded(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title_sort ASC")
    fun observeByTitleAsc(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title_sort DESC")
    fun observeByTitleDesc(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE source_id = :sourceId ORDER BY title_sort ASC")
    fun observeBySource(sourceId: Long): Flow<List<BookEntity>>

    /** 最近阅读：有进度记录的书，按进度更新时间倒序 */
    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN progress p ON p.book_id = b.id
        ORDER BY p.updated_at DESC
        """
    )
    fun observeRecentlyRead(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title_sort ASC")
    suspend fun allSorted(): List<BookEntity>

    @Query("SELECT * FROM books ORDER BY added_at DESC, id DESC")
    suspend fun allRecentAdded(): List<BookEntity>

    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN progress p ON p.book_id = b.id
        ORDER BY p.updated_at DESC
        """
    )
    suspend fun recentlyReadNow(): List<BookEntity>

    @Query("SELECT * FROM books WHERE source_id = :sourceId ORDER BY title_sort ASC")
    suspend fun bySourceNow(sourceId: Long): List<BookEntity>

    /** 重命名：只改书库里的显示名与排序键，源文件名不动 */
    @Query("UPDATE books SET title = :title, title_sort = :titleSort WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, titleSort: String)

    /** 换封面：只改这一本的取图页号，下次生成缩略图就按它取 */
    @Query("UPDATE books SET cover_page = :page WHERE id = :id")
    suspend fun updateCoverPage(id: Long, page: Int)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT id FROM books")
    suspend fun ids(): List<Long>

    @Query("SELECT id FROM books WHERE source_id = :sourceId")
    suspend fun idsBySource(sourceId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM books WHERE source_id = :sourceId")
    suspend fun countBySource(sourceId: Long): Int

    /** 每个来源下有几本：书源管理一屏要列出所有来源，别一条一条去数 */
    @Query("SELECT source_id AS sourceId, COUNT(*) AS total FROM books GROUP BY source_id")
    suspend fun countsBySource(): List<SourceCount>

    @Query("UPDATE books SET scan_state = :state, error_code = :errorCode WHERE id = :id")
    suspend fun setScanState(id: Long, state: Int, errorCode: Int?)

    @Query("UPDATE books SET page_total = :pages, chapter_total = :chapters WHERE id = :id")
    suspend fun setTotals(id: Long, pages: Int, chapters: Int)

    @Query("UPDATE books SET cover_page = :page WHERE id = :id")
    suspend fun setCoverPage(id: Long, page: Int)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM books WHERE source_id = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** 按 id 批量删（来源里消失的书）。列表长度要自己分段，别撞 SQLite 的变量上限 */
    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    suspend fun forBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY ordinal ASC")
    fun observeForBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** 建章是一次覆盖写：先清后插，避免残留 */
    @Transaction
    suspend fun replaceFor(bookId: Long, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        insertAll(chapters)
    }
}

@Dao
interface ProgressDao {

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE book_id = :bookId")
    suspend fun forBook(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE book_id = :bookId")
    fun observeForBook(bookId: Long): Flow<ProgressEntity?>

    @Query("DELETE FROM progress WHERE book_id = :bookId")
    suspend fun delete(bookId: Long)
}

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query(
        "SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY chapter_ordinal ASC, page_in_chapter ASC"
    )
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY chapter_ordinal ASC, page_in_chapter ASC"
    )
    suspend fun forBookNow(bookId: Long): List<BookmarkEntity>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE book_id = :bookId AND chapter_ordinal = :chapter AND page_in_chapter = :page
        LIMIT 1
        """
    )
    suspend fun find(bookId: Long, chapter: Int, page: Int): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId AND chapter_ordinal = :chapter AND page_in_chapter = :page")
    suspend fun deleteAt(bookId: Long, chapter: Int, page: Int)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** 分组计数的投影：来源 id 与该来源下的书数 */
data class SourceCount(val sourceId: Long, val total: Int)

/**
 * 阅读历史。同一本反复打开只保留一条「最近打开」的投影，
 * 原始记录留着不删，方便以后做按天统计。
 */
@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE book_id = :bookId")
    suspend fun clearFor(bookId: Long)

    /**
     * 最近打开的书：按 book_id 取最大时间，再按这个时间倒序。
     * 停在书库里没点开过的书不会出现在这里。
     */
    @Query(
        """
        SELECT b.* FROM books b
        JOIN (SELECT book_id, MAX(opened_at) AS t FROM history GROUP BY book_id) h
          ON b.id = h.book_id
        ORDER BY h.t DESC
        LIMIT :limit
        """
    )
    suspend fun recentBooks(limit: Int = 100): List<BookEntity>

    /** 每本书最近一次打开的时间，列表里直接显示 */
    @Query("SELECT book_id AS bookId, MAX(opened_at) AS at FROM history GROUP BY book_id")
    suspend fun lastOpened(): List<BookOpened>
}

/** 书 id 与最近打开时间 */
data class BookOpened(val bookId: Long, val at: Long)
