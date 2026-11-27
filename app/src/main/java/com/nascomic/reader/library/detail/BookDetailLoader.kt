package com.nascomic.reader.library.detail

import android.content.Context
import androidx.room.withTransaction
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.ChapterInfo
import com.nascomic.reader.format.comic.ComicMeta
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.series.VolumeNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打开一本书，量出总页数与每章页数。
 *
 * 扫描阶段不数页（逐本数页会让扫描随库规模明显变慢），所以第一次进详情页时补这一趟：
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
                // 包里有 ComicInfo 就顺手把作者与卷号修准；读不动不影响量页，也不阻塞界面。
                // 无论读到还是没读到都记一笔：包里没有这个文件的占多数，不能让它们每次进详情都白开一次书
                runCatching { src.meta }.getOrNull()?.let { applyMeta(book, it) }
                db.bookDao().markMetaRead(book.id)
                Detail(total, infos, starts)
            }
        }.onFailure {
            // 量页失败不阻塞界面（详情页照常显示），但要留下原因，否则远端问题无从查起
            android.util.Log.w("BookDetail", "量页失败：${book.title}", it)
        }.getOrNull()
    }

    /**
     * 把包内声明落到库里。
     *
     * 只覆盖「还停在文件名解析值」的那一栏：用户改过的（`author` 与文件名解析结果不一致、
     * 或本来就是他自己设的）一律不动。卷号同理。
     */
    private suspend fun applyMeta(book: BookEntity, meta: ComicMeta) = db.withTransaction {
        val parsed = VolumeNameParser.parse(book.title)
        // 包里的写法和文件名解析值都要先过别名表，否则「用户改过」的判断会把旧写法当成用户改的
        val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
        fun canon(name: String?): String? =
            name?.let { com.nascomic.reader.library.author.AuthorAliases.resolve(it, aliases) }
        val writer = canon(meta.writer)
        val parsedAuthor = canon(parsed.author)
        val author = when {
            writer == null -> null
            book.author.isNullOrBlank() -> writer
            book.author == parsedAuthor -> writer
            else -> null
        }
        val parsedVolume = parsed.volume?.toString()
        val volume = when {
            meta.volume == null -> null
            book.volume.isNullOrBlank() -> meta.volume
            book.volume == parsedVolume -> meta.volume
            else -> null
        }
        // 两栏各写各的，并且只在「库里的值还等于进来时的快照」时才落库：
        // 远端读元数据要几秒，这期间用户可能已经手动改过其中一栏，不能把他的改动盖回去
        if (author != null) db.bookDao().setAuthorIfUnchanged(book.id, author, book.author)
        if (volume != null) db.bookDao().setVolumeIfUnchanged(book.id, volume, book.volume)
        Unit
    }
}
