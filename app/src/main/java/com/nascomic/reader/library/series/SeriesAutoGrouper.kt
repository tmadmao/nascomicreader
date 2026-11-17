package com.nascomic.reader.library.series

import com.nascomic.reader.library.SeriesRepository
import com.nascomic.reader.library.data.ReaderDatabase

/**
 * 自动归入：把「同一部作品的各卷」认出来后，只把高置信的组落库。
 *
 * 低置信的一律不建——宁可不并，也不能把不相干的书串成一套；
 * 认错一次的代价比少认一组大得多。
 */
class SeriesAutoGrouper(private val db: ReaderDatabase) {

    private val repo = SeriesRepository(db)

    /** 返回这次新建的系列数；0 表示没有可自动归入的 */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        val books = db.bookDao().allSorted()
        if (books.size < 2) return 0

        // 已经在某个系列里的不动，避免和人工整理打架
        val free = books.filter { db.seriesDao().membershipOf(it.id) == null }
        if (free.size < 2) return 0

        val entries = free.map { book ->
            SeriesDetector.Entry(
                bookId = book.id,
                title = book.title,
                parent = book.docRef.substringBeforeLast('/', missingDelimiterValue = ""),
            )
        }
        val groups = SeriesDetector().detect(entries)
            .filter { it.highConfidence && it.members.size >= 2 }
        if (groups.isEmpty()) return 0

        var created = 0
        for (group in groups) {
            val id = repo.create(group.name, now)
            group.members.forEach { member -> repo.addBook(id, member.bookId, now) }
            created++
        }
        return created
    }
}
