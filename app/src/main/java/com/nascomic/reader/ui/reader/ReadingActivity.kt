package com.nascomic.reader.ui.reader

import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.databinding.ActivityReaderBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.pagePosition
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页。当前只做「打开一本书、看到第 N 页、点击左右翻页、记住位置」，
 * 缩放、双页、裁边这些等版面引擎做好再接进来。
 */
class ReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        /** 指定从哪一页开始读；不传则接着上次的进度 */
        const val EXTRA_START_PAGE = "start_page"
        /** 左右翻页的分区线，与后续设置项保持一致 */
        private const val ZONE_LEFT = 0.35f
        private const val ZONE_RIGHT = 0.65f
    }

    private lateinit var binding: ActivityReaderBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val opener by lazy { BookOpener(this) }

    private var source: BookSource? = null
    private var bookId: Long = 0
    private var requestedPage: Int = -1
    private var page = 0
    private var total = 0
    /** 是否有章的概念。单行本没有，页码按全书算 */
    private var chaptered = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        requestedPage = intent.getIntExtra(EXTRA_START_PAGE, -1)
        if (bookId == 0L) {
            finish()
            return
        }
        binding.readerRoot.setOnClickListener { }   // 占位，避免点击穿透
        openBook()
    }

    private fun openBook() {
        lifecycleScope.launch {
            binding.loading.visibility = android.view.View.VISIBLE
            try {
                val book = db.bookDao().get(bookId) ?: error("书不存在")
                val src = db.sourceDao().get(book.sourceId) ?: error("来源不存在")
                val treeUri = src.treeUri ?: error("这个来源没有目录授权")
                val chapters = db.chapterDao().forBook(bookId)

                val opened = withContext(Dispatchers.IO) {
                    opener.open(book, treeUri, chapters)
                }

                source = opened
                total = opened.pageCount
                if (total == 0) {
                    showError(getString(com.nascomic.reader.R.string.reader_empty))
                    return@launch
                }

                // 页数只在真正打开时才知道，这里补写回库。
                // 单行本没有章的概念，章数记 0，免得界面把它显示成章回体
                chaptered = book.isSingleVolume == 0 && opened.chapters.isNotEmpty()
                val chapterCount = if (chaptered) opened.chapters.size else 0
                db.bookDao().setTotals(bookId, total, chapterCount)

                page = if (requestedPage >= 0) {
                    requestedPage.coerceIn(0, total - 1)
                } else {
                    val saved = db.progressDao().forBook(bookId)
                    saved?.pageGlobal?.coerceIn(0, total - 1) ?: 0
                }
                showPage()
                saveProgress()
            } catch (e: Exception) {
                showError(e.message ?: getString(com.nascomic.reader.R.string.reader_open_failed))
            } finally {
                binding.loading.visibility = android.view.View.GONE
            }
        }
    }

    private fun showPage() {
        val src = source ?: return
        if (loading) return
        loading = true
        binding.loading.visibility = android.view.View.VISIBLE
        // 章回体显示章内页码：第 2 话的第 1 页就是 1/2，不是全书第 3/4
        val pos = src.pagePosition(page, chaptered)
        binding.pageLabel.text = if (pos.chaptered) {
            getString(
                com.nascomic.reader.R.string.reader_page_chapter_format,
                pos.chapterTitle, pos.page, pos.total
            )
        } else {
            getString(com.nascomic.reader.R.string.reader_page_format, pos.page, pos.total)
        }

        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    PageBitmapLoader.load(
                        src.openPage(page),
                        binding.page.width.coerceAtLeast(1),
                        binding.page.height.coerceAtLeast(1),
                    )
                }.getOrNull()
            }
            if (bitmap == null) {
                showError(
                    getString(
                        com.nascomic.reader.R.string.reader_page_failed,
                        src.pagePosition(page, chaptered).page
                    )
                )
            } else {
                binding.error.visibility = android.view.View.GONE
                binding.page.setImageBitmap(bitmap)
            }
            binding.loading.visibility = android.view.View.GONE
            loading = false
        }
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.visibility = android.view.View.VISIBLE
        binding.pageLabel.text = ""
        binding.loading.visibility = android.view.View.GONE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP && source != null) {
            val x = ev.x / (binding.readerRoot.width.coerceAtLeast(1))
            when {
                x < ZONE_LEFT -> turnTo(page - 1)
                x > ZONE_RIGHT -> turnTo(page + 1)
                else -> Unit          // 中间留给以后的工具栏
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun turnTo(target: Int) {
        if (loading) return
        if (target < 0 || target >= total) return
        page = target
        showPage()
        saveProgress()
    }

    private fun saveProgress() {
        val src = source ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                db.progressDao().upsert(
                    ProgressEntity(
                        bookId = bookId,
                        chapterOrdinal = src.chapterOf(page),
                        pageInChapter = page - src.firstPageOfChapter(src.chapterOf(page)),
                        pageGlobal = page,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { source?.close() }
        binding.page.setImageDrawable(null)
    }
}
