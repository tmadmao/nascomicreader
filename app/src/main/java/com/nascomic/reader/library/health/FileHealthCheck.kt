package com.nascomic.reader.library.health

import android.content.Context
import android.net.Uri
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.SafWalker
import com.nascomic.reader.settings.SecretStore
import com.nascomic.reader.smb.SmbClient
import com.nascomic.reader.smb.SmbTarget
import com.nascomic.reader.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * 文件健康检查：把某个来源下的书逐本打开一遍，找出打不开、没有图片页、
 * 页数与库里记录对不上的那些。
 *
 * **只读不写**：不修包、不改 `page_total`、不删记录、不动进度；
 * 打开的落盘副本属于缓存，检查完照常由缓存自己回收。
 *
 * 远端要特别防误报：NAS 掉线时每一本都会失败，照单全收等于把整个书库报成坏包。
 * 所以先摸一次根目录，中途再失败就先重试一次，仍失败才摸书源——
 * 摸不到就记「没读到、判不了」，连续摸不到就提前收手。
 */
class FileHealthCheck(
    context: Context,
    private val db: ReaderDatabase,
) {

    private val appContext = context.applicationContext
    private val secrets by lazy { SecretStore(appContext) }
    private val opener by lazy { BookOpener(appContext) }

    interface Listener {
        fun onStart(total: Int) {}
        /** [done] 是已经试过的本数，[title] 是刚检查完的这一本 */
        fun onProgress(title: String, done: Int, total: Int) {}
    }

    class Cancelled : Exception("检查已取消")

    suspend fun check(
        source: SourceEntity,
        listener: Listener? = null,
        /** 每本之间问一次；true 就停下，库不动 */
        isCancelled: () -> Boolean = { false },
    ): HealthReport = withContext(Dispatchers.IO) {
        val books = db.bookDao().bySourceNow(source.id)

        // 连不上就别逐本试：那样每一本都会被记成坏包
        probe(source)

        val issues = ArrayList<HealthIssue>()
        if (books.isEmpty()) return@withContext HealthReport(0, 0, 0, issues)

        listener?.onStart(books.size)
        var checked = 0
        var skipped = 0
        var stoppedEarly = false

        for (book in books) {
            if (isCancelled()) throw Cancelled()
            listener?.onProgress(book.displayTitle, checked, books.size)

            val chapters = chaptersOf(book)
            when (val outcome = inspect(book, source, chapters)) {
                Outcome.Clean -> Unit
                is Outcome.Bad -> issues += outcome.issue
                Outcome.Unsure -> {
                    skipped++
                    // 书源整个失联时，别把剩下上百本都当坏包；已经查出来的照常报
                    if (!probeQuiet(source)) {
                        stoppedEarly = true
                        checked++
                        break
                    }
                }
            }
            checked++
        }

        listener?.onProgress("", checked, books.size)
        HealthReport(books.size, checked, skipped, issues, stoppedEarly)
    }

    private suspend fun chaptersOf(book: BookEntity): List<ChapterEntity> =
        if (book.isSingleVolume == 1) emptyList() else db.chapterDao().forBook(book.id)

    private sealed class Outcome {
        object Clean : Outcome()
        class Bad(val issue: HealthIssue) : Outcome()

        /** 书源暂时读不到，这一本判不了；既不算好也不算坏 */
        object Unsure : Outcome()
    }

    /**
     * 一本试两次：远端偶发一次超时，重试一次多半就好了。
     * 两次都失败再判——先摸书源，摸得到就说明是这本书自己的问题。
     */
    private fun inspect(
        book: BookEntity,
        source: SourceEntity,
        chapters: List<ChapterEntity>,
    ): Outcome {
        // 两次失败的原因取「最具体」的那个：末尾那次多半是 libarchive 兜底报的「读不出来」，
        // 把它盖在「不是 zip」上面，用户就看不到真正的成因
        var cause = HealthIssue.Cause.OTHER
        repeat(2) { attempt ->
            try {
                opener.openForAudit(book, source, chapters).use { src ->
                    val actual = src.pageCount
                    val kind = FileHealthRules.judge(book.pageTotal, actual)
                    return if (kind == null) {
                        Outcome.Clean
                    } else {
                        Outcome.Bad(
                            HealthIssue(
                                bookId = book.id,
                                title = book.displayTitle,
                                kind = kind,
                                storedPages = book.pageTotal,
                                actualPages = actual,
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val found = FileHealthRules.causeOf(e, book.format)
                if (cause == HealthIssue.Cause.OTHER) cause = found
                if (attempt == 0) Thread.sleep(200)
            }
        }

        if (!probeQuiet(source)) return Outcome.Unsure
        return Outcome.Bad(
            HealthIssue(
                bookId = book.id,
                title = book.displayTitle,
                kind = HealthIssue.Kind.UNREADABLE,
                cause = cause,
            )
        )
    }

    /** 摸一下书源根目录：能列出来说明连接与账号都还在 */
    private fun probe(source: SourceEntity) {
        when (source.kind) {
            SourceEntity.KIND_WEBDAV -> {
                val client = WebDavClient(source.account, secrets.get(source.secretRef), source.endpoint)
                client.list(source.endpoint ?: error("这个来源没有地址"))
            }
            SourceEntity.KIND_SMB -> {
                val target = SmbTarget.parse(source.endpoint ?: error("这个来源没有地址"))
                val client = SmbClient(target, source.account, secrets.get(source.secretRef))
                try {
                    client.list(target.path)
                } finally {
                    client.close()
                }
            }
            else -> {
                val treeUri = source.treeUri ?: error("这个来源没有目录授权")
                val walker = SafWalker(appContext, Uri.parse(treeUri))
                if (walker.list(walker.rootDocId) == null) {
                    throw IllegalStateException("目录读不到，授权可能已失效")
                }
            }
        }
    }

    private fun probeQuiet(source: SourceEntity): Boolean =
        runCatching { probe(source) }.isSuccess
}
