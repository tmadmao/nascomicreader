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

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM books WHERE source_id = :sourceId")
    suspend fun countBySource(sourceId: Long): Int

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

    /** 扫描后清理：来源里已经不存在于磁盘的条目 */
    @Query("DELETE FROM books WHERE source_id = :sourceId AND doc_ref NOT IN (:presentRefs)")
    suspend fun deleteMissing(sourceId: Long, presentRefs: List<String>)
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
