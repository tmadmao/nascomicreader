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
        ensureActive(isCancelled)
        probe(source)
        ensureActive(isCancelled)

        val issues = ArrayList<HealthIssue>()
        if (books.isEmpty()) return@withContext HealthReport(0, 0, 0, issues)

        runHealthCheck(
            books = books,
            listener = listener,
            isCancelled = isCancelled,
            inspect = { book -> inspect(book, source, chaptersOf(book), isCancelled) },
            probeQuiet = { probeQuiet(source) },
        )
    }

    private suspend fun chaptersOf(book: BookEntity): List<ChapterEntity> =
        if (book.isSingleVolume == 1) emptyList() else db.chapterDao().forBook(book.id)

    internal sealed class Outcome {
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
        isCancelled: () -> Boolean,
    ): Outcome {
        // 两次失败的原因取「最具体」的那个：末尾那次多半是 libarchive 兜底报的「读不出来」，
        // 把它盖在「不是 zip」上面，用户就看不到真正的成因
        var cause = HealthIssue.Cause.OTHER
        repeat(2) { attempt ->
            ensureActive(isCancelled)
            try {
                opener.openForAudit(book, source, chapters, isCancelled).use { src ->
                    // 打开归档可能已花掉几秒：先看取消，再决定要不要数页
                    ensureActive(isCancelled)
                    val actual = src.pageCount
                    ensureActive(isCancelled)
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
            } catch (e: Cancelled) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 下载被取消时，底下的 socket/句柄会抛各种不同的异常；
                // 先看取消标志，别把「用户按了取消」误报成「这本书打不开」
                if (isCancelled()) throw Cancelled()
                val found = FileHealthRules.causeOf(e, book.format)
                if (cause == HealthIssue.Cause.OTHER) cause = found
                if (attempt == 0) {
                    Thread.sleep(200)
                    ensureActive(isCancelled)
                }
            }
        }

        ensureActive(isCancelled)
        if (!probeQuiet(source)) return Outcome.Unsure
        ensureActive(isCancelled)
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

internal fun ensureActive(isCancelled: () -> Boolean) {
    if (isCancelled()) throw FileHealthCheck.Cancelled()
}

/**
 * 逐本检查的循环，抽出来是为了能在 JVM 单测里塞一个假 [inspect] 验取消。
 *
 * 取消点有三处：每本开始前、一本检查完、全部检查完。最后这处不能省——
 * 用户可能在最后一本检查期间按取消，此时若直接返回报告，界面会当成「检查完成」。
 */
internal suspend fun runHealthCheck(
    books: List<BookEntity>,
    listener: FileHealthCheck.Listener?,
    isCancelled: () -> Boolean,
    inspect: suspend (BookEntity) -> FileHealthCheck.Outcome,
    probeQuiet: () -> Boolean,
): HealthReport {
    val issues = ArrayList<HealthIssue>()
    listener?.onStart(books.size)
    var checked = 0
    var skipped = 0
    var stoppedEarly = false

    for (book in books) {
        ensureActive(isCancelled)
        listener?.onProgress(book.displayTitle, checked, books.size)

        val outcome = inspect(book)
        // 检查一本期间用户可能已经按了取消：这本的结论不再入账，直接收手
        ensureActive(isCancelled)
        when (outcome) {
            FileHealthCheck.Outcome.Clean -> Unit
            is FileHealthCheck.Outcome.Bad -> issues += outcome.issue
            FileHealthCheck.Outcome.Unsure -> {
                skipped++
                // 书源整个失联时，别把剩下上百本都当坏包；已经查出来的照常报
                if (!probeQuiet()) {
                    stoppedEarly = true
                    checked++
                    break
                }
            }
        }
        checked++
    }

    // 最后一本检查完也要再看一次：此时按取消不能被当成「检查完成」
    ensureActive(isCancelled)
    listener?.onProgress("", checked, books.size)
    return HealthReport(books.size, checked, skipped, issues, stoppedEarly)
}
