package com.nascomic.reader.library.author

import androidx.room.withTransaction
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.series.VolumeNameParser

/**
 * 老书的作者回填：按书名重新解析一遍，写进 `books.author` / `volume`。
 *
 * 只处理 `author IS NULL` 的行——新扫进来的书在入库时就写好了，不重复做。
 * 解析不出作者的不写，下次还会被选中，但它本来就没作者（图集常见的「01.jpg」式命名），
 * 由「作家列表」的 `#` 一节兜住。
 */
class AuthorBackfill(private val db: ReaderDatabase) {

    suspend fun run(): Int {
        val books = db.bookDao().withoutAuthor()
        if (books.isEmpty()) return 0
        // 回填的也是解析值，同样先过别名表
        val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
        var filled = 0
        db.withTransaction {
            for (book in books) {
                val parsed = VolumeNameParser.parse(book.title)
                val author = parsed.author?.let { AuthorAliases.resolve(it, aliases) }
                if (author == null && parsed.volume == null) continue
                db.bookDao().setAuthorAndVolume(
                    book.id,
                    author,
                    parsed.volume?.toString(),
                )
                filled++
            }
        }
        return filled
    }
}
