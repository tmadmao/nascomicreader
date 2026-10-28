package com.nascomic.reader.library.detail

import android.content.Context
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.ChapterInfo
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打开一本书，量出总页数与每章页数。
 *
 * 扫描阶段不数页（否则几百本要几分钟），所以第一次进详情页时补这一趟：
 * 结果写回库，之后再看就是现成的。
 */
class BookDetailLoader(
    private val context: Context,
    private val db: ReaderDatabase,
) {

    data class Detail(
        val pageTotal: Int,
        val chapters: List<ChapterInfo>,
        /** 每章第一页的全局页号，与 [chapters] 等长 */
        val chapterStarts: IntArray,
    )

    suspend fun load(
        book: BookEntity,
        source: com.nascomic.reader.library.data.SourceEntity,
        chapters: List<ChapterEntity>,
        opener: BookOpener,
    ): Detail? = withContext(Dispatchers.IO) {
        runCatching {
            val bookSource = opener.open(book, source, chapters)
            bookSource.use { src ->
                val total = src.pageCount
                val infos = src.chapters
                val starts = IntArray(infos.size) { src.firstPageOfChapter(it) }

                // 写回库：总页数 + 每章页数。下次进详情页就不用再打开书了
                db.bookDao().setTotals(
                    book.id, total,
                    if (book.isSingleVolume == 1) 0 else infos.size
                )
                if (infos.isNotEmpty() && book.isSingleVolume == 0) {
                    db.chapterDao().replaceFor(
                        book.id,
                        chapters.sortedBy { it.ordinal }.mapIndexed { index, ch ->
                            ch.copy(pageTotal = infos.getOrNull(index)?.pageCount ?: 0)
                        }
                    )
                }
                Detail(total, infos, starts)
            }
        }.onFailure {
            // 量页失败不阻塞界面（详情页照常显示），但要留下原因，否则远端问题无从查起
            android.util.Log.w("BookDetail", "量页失败：${book.title}", it)
        }.getOrNull()
    }
}
