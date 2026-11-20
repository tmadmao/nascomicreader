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

    @Query("UPDATE sources SET book_mode = :mode WHERE id = :id")
    suspend fun updateBookMode(id: Long, mode: Int)

    @Query("UPDATE sources SET scan_state = :state, error_code = :errorCode, last_scan_at = :at WHERE id = :id")
    suspend fun setScanState(id: Long, state: Int, errorCode: Int?, at: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

/** 作者与他的书数：作家列表用 */
data class AuthorCount(val name: String, val total: Int)

@Dao
interface BookDao {

    /** 还没解析出作者的书：老库回填时用；写完 author 就不再被选中 */
    @Query("SELECT * FROM books WHERE author IS NULL")
    suspend fun withoutAuthor(): List<BookEntity>

    /** 每位作者有多少本；空作者不算在内 */
    @Query(
        "SELECT author AS name, COUNT(*) AS total FROM books " +
            "WHERE author IS NOT NULL AND author <> '' GROUP BY author"
    )
    suspend fun authorCounts(): List<AuthorCount>

    @Query("SELECT COUNT(*) FROM books WHERE author IS NULL OR author = ''")
    suspend fun booksWithoutAuthor(): Int

    @Query("SELECT * FROM books WHERE author = :author ORDER BY title_sort ASC")
    suspend fun byAuthor(author: String): List<BookEntity>

    /** 没有作者的书（图集常见），作家列表的「未知作者」用 */
    @Query("SELECT * FROM books WHERE author IS NULL OR author = '' ORDER BY title_sort ASC")
    suspend fun booksWithNoAuthor(): List<BookEntity>

    @Query("UPDATE books SET author = :author WHERE id = :id")
    suspend fun setAuthor(id: Long, author: String?)

    /** 整批改名：同一作者下所有书一起换，别让同一个人分成两条 */
    @Query("UPDATE books SET author = :fresh WHERE author = :old")
    suspend fun renameAuthor(old: String, fresh: String)

    /** 这位作者的书涉及哪些系列 */
    @Query(
        "SELECT * FROM series WHERE id IN (" +
            "SELECT sb.series_id FROM series_books sb JOIN books b ON b.id = sb.book_id " +
            "WHERE b.author = :author) ORDER BY name_sort ASC"
    )
    suspend fun seriesOfAuthor(author: String): List<SeriesEntity>

    @Query("UPDATE books SET author = :author, volume = :volume WHERE id = :id")
    suspend fun setAuthorAndVolume(id: Long, author: String?, volume: String?)


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

    /**
     * 重命名：只写用户别名与排序键，`title` 保持来源上的名字。
     * 写 title 的话，下一次扫描比对不出差异会当成来源变了，又把文件名盖回来。
     */
    @Query("UPDATE books SET alias = :alias, title_sort = :titleSort WHERE id = :id")
    suspend fun rename(id: Long, alias: String?, titleSort: String)

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

    /** 重扫后章序变了时，把书签挪到新的章号上（页内页不变，章的内容没动） */
    @Query("UPDATE bookmarks SET chapter_ordinal = :chapter WHERE id = :id")
    suspend fun moveToChapter(id: Long, chapter: Int)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** 分组计数的投影：来源 id 与该来源下的书数 */
data class SourceCount(val sourceId: Long, val total: Int)

@Dao
interface SeriesDao {

    @Insert
    suspend fun insert(series: SeriesEntity): Long

    @Query("SELECT * FROM series ORDER BY name_sort ASC")
    fun observeAll(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series ORDER BY name_sort ASC")
    suspend fun all(): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun get(id: Long): SeriesEntity?

    @Query("UPDATE series SET name = :name, name_sort = :nameSort WHERE id = :id")
    suspend fun rename(id: Long, name: String, nameSort: String)

    /** 删系列只删这一行；关联表靠外键级联跟着走，书本身不动 */
    @Query("DELETE FROM series WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM series_books WHERE series_id = :seriesId ORDER BY ordinal ASC")
    suspend fun members(seriesId: Long): List<SeriesBookEntity>

    @Query("SELECT * FROM series_books WHERE book_id = :bookId LIMIT 1")
    suspend fun membershipOf(bookId: Long): SeriesBookEntity?

    /** 一本只归一个系列：book_id 上有唯一索引，换系列时覆盖原来那行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMember(member: SeriesBookEntity): Long

    @Query("DELETE FROM series_books WHERE book_id = :bookId")
    suspend fun removeMember(bookId: Long)

    @Query("UPDATE series_books SET ordinal = :ordinal WHERE id = :id")
    suspend fun moveMember(id: Long, ordinal: Int)

    @Query("SELECT MAX(ordinal) FROM series_books WHERE series_id = :seriesId")
    suspend fun lastOrdinal(seriesId: Long): Int?

    /** 追加到组尾。组内序只往后长，重排由界面显式调 moveMember */
    @Transaction
    suspend fun append(seriesId: Long, bookId: Long, at: Long) {
        val next = (lastOrdinal(seriesId) ?: -1) + 1
        putMember(SeriesBookEntity(seriesId = seriesId, bookId = bookId, ordinal = next, addedAt = at))
    }
}
