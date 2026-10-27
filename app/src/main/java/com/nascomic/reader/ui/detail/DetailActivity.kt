package com.nascomic.reader.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityDetailBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.detail.BookDetailLoader
import com.nascomic.reader.ui.reader.ReadingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书详情页：封面、进度、章节目录，以及唯一的主动作（开始 / 继续阅读）。
 *
 * 页数在第一次进来时才算（扫描阶段不数页），所以这里可能会短暂显示「正在统计页数」。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }

    private lateinit var binding: ActivityDetailBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val thumbs by lazy { ThumbnailStore(this) }
    private val opener by lazy { BookOpener(this) }
    private val loader by lazy { BookDetailLoader(this, db) }

    private var bookId: Long = 0
    private var book: BookEntity? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var progress: ProgressEntity? = null
    private var chapterStarts: IntArray = IntArray(0)

    /** 数据装载过一次之后，onResume 才有东西可刷新 */
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        if (bookId == 0L) {
            finish()
            return
        }
        binding.back.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            book = db.bookDao().get(bookId) ?: run { finish(); return@launch }
            val src = db.sourceDao().get(book!!.sourceId)
            chapters = db.chapterDao().forBook(bookId)
            progress = db.progressDao().forBook(bookId)

            render()
            loadCover()

            // 首次进来页数是 0，需要打开书量一次
            if (book!!.pageTotal <= 0 && src?.treeUri != null) {
                binding.tocHint.text = getString(R.string.detail_counting)
                val detail = loader.load(book!!, src.treeUri, chapters, opener)
                book = db.bookDao().get(bookId)
                chapters = db.chapterDao().forBook(bookId)
                progress = db.progressDao().forBook(bookId)

                if (detail == null) {
                    binding.tocHint.text = getString(R.string.detail_count_failed)
                }
                render()
            }
            loaded = true
        }
    }

    override fun onResume() {
        super.onResume()
        // 从阅读页返回时进度已经变了，重读一次再画
        if (!loaded) return
        lifecycleScope.launch {
            progress = db.progressDao().forBook(bookId)
            render()
        }
    }

    private fun render() {
        val b = book ?: return
        binding.title.text = b.title

        val isChaptered = b.isSingleVolume == 0 && chapters.isNotEmpty()
        binding.meta.text = if (isChaptered) {
            getString(R.string.detail_meta_chaptered, b.pageTotal, chapters.size)
        } else {
            getString(R.string.detail_meta_paged, b.pageTotal)
        }

        val p = progress
        if (p != null && b.pageTotal > 0) {
            // 章回体按章内页报进度：读到第 2 话第 1 页，而不是全书第 3 页
            val chapter = chapters.getOrNull(p.chapterOrdinal)
            val byChapter = isChaptered && chapter != null && chapter.pageTotal > 0

            binding.progressInfo.isVisible = true
            binding.progressInfo.text = if (byChapter) {
                getString(
                    R.string.detail_progress_chapter,
                    chapter.title, p.pageInChapter + 1, chapter.pageTotal
                )
            } else {
                getString(R.string.detail_progress, p.pageGlobal + 1, b.pageTotal)
            }
            binding.primary.text = if (byChapter) {
                getString(
                    R.string.detail_continue_reading_chapter, chapter.title, p.pageInChapter + 1
                )
            } else {
                getString(R.string.detail_continue_reading, p.pageGlobal + 1)
            }
        } else {
            binding.progressInfo.isVisible = false
            binding.primary.text = getString(R.string.detail_start_reading)
        }

        binding.primary.setOnClickListener { openReader(p?.pageGlobal ?: 0) }

        renderToc(b, isChaptered)
    }

    private fun renderToc(b: BookEntity, isChaptered: Boolean) {
        binding.tocList.removeAllViews()

        if (!isChaptered) {
            binding.tocHeader.isVisible = false
            binding.tocHint.isVisible = true
            binding.tocHint.text = if (b.pageTotal > 0) {
                getString(R.string.detail_no_toc)
            } else {
                getString(R.string.detail_counting)
            }
            return
        }

        binding.tocHeader.isVisible = true
        binding.tocHint.isVisible = false

        // 各章起始页：靠每章页数累加，读不到页数时退化为 0（点了也从章首开始）
        chapterStarts = IntArray(chapters.size)
        var acc = 0
        chapters.forEachIndexed { i, ch ->
            chapterStarts[i] = acc
            acc += ch.pageTotal
        }

        val currentChapter = progress?.chapterOrdinal ?: -1
        val pad = dp(6)
        for (ch in chapters) {
            val row = TextView(this).apply {
                text = if (ch.pageTotal > 0) {
                    getString(R.string.detail_chapter_row, ch.ordinal + 1, ch.title) +
                        "   " + getString(R.string.detail_chapter_pages, ch.pageTotal)
                } else {
                    getString(R.string.detail_chapter_row, ch.ordinal + 1, ch.title)
                }
                textSize = 14f
                gravity = Gravity.START
                setPadding(0, pad, 0, pad)
                setTextColor(
                    if (ch.ordinal == currentChapter) getColor(R.color.brand_indigo)
                    else getColor(R.color.shell_ink)
                )
                setOnClickListener { openReader(chapterStarts.getOrElse(ch.ordinal) { 0 }) }
            }
            binding.tocList.addView(row)
        }
    }

    private fun loadCover() {
        val b = book ?: return
        lifecycleScope.launch {
            val w = binding.cover.width.takeIf { it > 0 } ?: dp(112)
            val bmp = withContext(Dispatchers.IO) { thumbs.load(b.id, w, w * 4 / 3) }
            if (bmp != null) binding.cover.setImageBitmap(bmp)
        }
    }

    private fun openReader(startPage: Int) {
        startActivity(
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_BOOK_ID, bookId)
                .putExtra(ReadingActivity.EXTRA_START_PAGE, startPage)
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
