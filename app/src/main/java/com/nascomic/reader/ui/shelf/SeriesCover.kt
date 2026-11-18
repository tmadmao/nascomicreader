package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系列封面：取首卷的缩略图，没有就现生成一次。
 *
 * 系列自己不存图——首卷是什么样，系列封面就是什么样；首卷换了（移出、重扫），
 * 下次进来按新首卷再取一次，不需要额外的失效逻辑。
 *
 * 只读已生成的缩略图是不够的：刚扫进来的书往往还没有缩略图，
 * 那会让整个「作品系列」页都是灰块，看起来像坏掉。
 */
class SeriesCover(
    private val db: ReaderDatabase,
    private val thumbs: ThumbnailStore,
    private val opener: BookOpener,
) {

    /** 同一本只生成一次：几屏同时滚到同一本时不要排队打架 */
    private val inFlight = HashSet<Long>()

    suspend fun load(bookId: Long, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val height = width * 4 / 3
        thumbs.load(bookId, width, height)?.let { return@withContext it }

        if (!inFlight.add(bookId)) return@withContext null
        try {
            val book = db.bookDao().get(bookId) ?: return@withContext null
            val source = db.sourceDao().get(book.sourceId) ?: return@withContext null
            val chapters = db.chapterDao().forBook(bookId)
            runCatching { thumbs.generate(book, source, chapters, opener, targetWidth = width) }
            thumbs.load(bookId, width, height)
        } finally {
            inFlight.remove(bookId)
        }
    }
}
