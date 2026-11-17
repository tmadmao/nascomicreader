package com.nascomic.reader.library

import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SeriesEntity

/**
 * 作品系列的读写。系列只是本机的一张关联表，这里不碰来源文件。
 *
 * 系列在界面上当「一本书」用：建、改名、加卷、移出、删都走这几个入口。
 */
class SeriesRepository(private val db: ReaderDatabase) {

    suspend fun all(): List<SeriesEntity> = db.seriesDao().all()

    suspend fun get(id: Long): SeriesEntity? = db.seriesDao().get(id)

    suspend fun create(name: String, at: Long): Long = db.seriesDao().insert(
        SeriesEntity(name = name, nameSort = NaturalSort.key(name), createdAt = at)
    )

    /** 追加到组尾；同一本重复加入只保留一行（book_id 上有唯一索引） */
    suspend fun addBook(seriesId: Long, bookId: Long, at: Long) =
        db.seriesDao().append(seriesId, bookId, at)

    suspend fun rename(id: Long, name: String) =
        db.seriesDao().rename(id, name, NaturalSort.key(name))

    /** 删系列只解关联，书本身不动 */
    suspend fun delete(id: Long) = db.seriesDao().delete(id)

    suspend fun removeBook(bookId: Long) = db.seriesDao().removeMember(bookId)

    /** 这本书属于哪个系列；没归属返回 null */
    suspend fun seriesOf(bookId: Long): SeriesEntity? =
        db.seriesDao().membershipOf(bookId)?.let { db.seriesDao().get(it.seriesId) }

    /** 系列里的各卷，按组内序 */
    suspend fun volumes(seriesId: Long): List<BookEntity> =
        db.seriesDao().members(seriesId).mapNotNull { member -> db.bookDao().get(member.bookId) }

    suspend fun countOf(seriesId: Long): Int = db.seriesDao().members(seriesId).size
}
