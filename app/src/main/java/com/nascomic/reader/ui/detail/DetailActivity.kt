package com.nascomic.reader.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityDetailBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.detail.BookDetailLoader
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.applyOrientation
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
    private val appPrefs by lazy { AppPrefs(this) }

    private var bookId: Long = 0
    private var book: BookEntity? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var progress: ProgressEntity? = null
    private var chapterStarts: IntArray = IntArray(0)
    private var bookmarks: List<BookmarkEntity> = emptyList()

    /** 数据装载过一次之后，onResume 才有东西可刷新 */
    private var source: SourceEntity? = null
    private var loaded = false

    /** 量页失败过（文件损坏、远端断开）：提示要停在失败上，不能一直说「正在统计」 */
    private var countFailed = false

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
            val src = db.sourceDao().get(book!!.sourceId).also { source = it }
            chapters = db.chapterDao().forBook(bookId)
            progress = db.progressDao().forBook(bookId)
            bookmarks = db.bookmarkDao().forBookNow(bookId)

            render()
            loadCover()

            // 首次进来页数是 0，需要打开书量一次
            if (book!!.pageTotal <= 0 && src != null) {
                binding.tocHint.text = getString(R.string.detail_counting)
                val detail = loader.load(book!!, src, chapters, opener)
                book = db.bookDao().get(bookId)
                chapters = db.chapterDao().forBook(bookId)
                progress = db.progressDao().forBook(bookId)
                bookmarks = db.bookmarkDao().forBookNow(bookId)

                if (detail == null) {
                    countFailed = true
                    binding.tocHint.text = getString(R.string.detail_count_failed)
                }
                render()
            }
            loaded = true
        }
    }

    override fun onResume() {
        super.onResume()
        applyOrientation(appPrefs)
        // 从阅读页返回时进度已经变了，重读一次再画
        if (!loaded) return
        lifecycleScope.launch {
            progress = db.progressDao().forBook(bookId)
            bookmarks = db.bookmarkDao().forBookNow(bookId)
            render()
        }
    }

    private fun render() {
        val b = book ?: return
        binding.title.text = b.title
        binding.meta.text = metaLine(b)
        binding.sourceLine.text = sourceLine(b)

        val isChaptered = b.isSingleVolume == 0 && chapters.isNotEmpty()
        val p = progress
        val hasProgress = p != null && b.pageTotal > 0

        if (hasProgress) {
            val chapter = chapters.getOrNull(p!!.chapterOrdinal)
            val byChapter = isChaptered && chapter != null && chapter.pageTotal > 0

            binding.percent.visibility = View.VISIBLE
            binding.readInfo.visibility = View.VISIBLE
            binding.percent.text = getString(
                R.string.shelf_percent,
                readPercent(p.pageGlobal, b.pageTotal, p.finished)
            )
            binding.readInfo.text = if (p.finished) {
                getString(R.string.progress_finished)
            } else {
                getString(R.string.detail_read_line, p.pageGlobal + 1, b.pageTotal)
            }

            binding.primary.text = if (byChapter) {
                getString(
                    R.string.detail_continue_reading_chapter, chapter.title, p.pageInChapter + 1
                )
            } else {
                getString(R.string.detail_continue_reading, p.pageGlobal + 1)
            }
            binding.secondary.visibility = View.VISIBLE
        } else {
            binding.percent.visibility = View.GONE
            binding.readInfo.visibility = View.GONE
            binding.primary.text = getString(R.string.detail_start_reading)
            binding.secondary.visibility = View.GONE
        }

        binding.primary.setOnClickListener { openReader(p?.pageGlobal ?: 0) }
        binding.secondary.setOnClickListener { openReader(0) }

        // 各章起始页：靠每章页数累加，读不到页数时退化为 0（点了也从章首开始）
        chapterStarts = IntArray(chapters.size)
        var acc = 0
        chapters.forEachIndexed { i, ch ->
            chapterStarts[i] = acc
            acc += ch.pageTotal
        }

        renderToc(b, isChaptered)
        renderBookmarks(isChaptered)
    }

    /** 第一行：格式 · 体积 · 还剩多少 */
    private fun metaLine(b: BookEntity): String {
        val format = getString(
            when (b.format) {
                BookEntity.FORMAT_PDF -> R.string.format_pdf
                BookEntity.FORMAT_EPUB -> R.string.format_epub
                BookEntity.FORMAT_FOLDER -> R.string.format_folder
                else -> R.string.format_archive
            }
        )
        val size = formatSize(b.byteSize)
        val chaptered = b.isSingleVolume == 0 && chapters.isNotEmpty()
        val left = (chapters.size - (progress?.chapterOrdinal ?: 0) - 1).coerceAtLeast(0)
        return if (chaptered) {
            getString(R.string.detail_meta_line, format, size, left)
        } else {
            getString(R.string.detail_meta_line_pages, format, size, b.pageTotal)
        }
    }

    private fun sourceLine(b: BookEntity): String {
        val name = source?.label
        if (name.isNullOrEmpty()) return ""
        val read = progress?.updatedAt ?: 0L
        return if (read > 0) {
            getString(R.string.detail_source_line, name) + " · " +
                com.nascomic.reader.library.RelativeTime.let {
                    it.dateOf(read)
                }
        } else {
            getString(R.string.detail_source_line, name)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * 目录：序号 · 话名 · 页数 三栏。
     * 已读的话在页数前打 ✓ 并把字降到次要色；当前话整行铺靛蓝浅底、右侧改写成「读到 N 页」。
     */
    private fun renderToc(b: BookEntity, isChaptered: Boolean) {
        binding.tocList.removeAllViews()

        if (!isChaptered) {
            binding.tocHeader.isVisible = false
            binding.tocCount.isVisible = false
            binding.tocHint.isVisible = true
            binding.tocHint.text = when {
                b.pageTotal > 0 -> getString(R.string.detail_no_toc)
                countFailed -> getString(R.string.detail_count_failed)
                else -> getString(R.string.detail_counting)
            }
            return
        }

        binding.tocHeader.isVisible = true
        binding.tocCount.isVisible = true
        binding.tocHeader.text = getString(R.string.detail_toc_with_count)
        binding.tocCount.text = getString(R.string.detail_toc_chapters, chapters.size)
        binding.tocHint.isVisible = false

        val currentChapter = progress?.chapterOrdinal ?: -1
        for (ch in chapters) {
            val isCurrent = ch.ordinal == currentChapter
            val isRead = ch.ordinal < currentChapter || (isCurrent && (progress?.pageInChapter == ch.pageTotal - 1))
            binding.tocList.addView(tocRow(ch, isCurrent, isRead))
        }
    }

    private fun tocRow(
        ch: com.nascomic.reader.library.data.ChapterEntity,
        isCurrent: Boolean,
        isRead: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = if (isCurrent) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(getColor(R.color.brand_indigo_tint))
                }
            } else {
                null
            }
        }

        val titleColor = when {
            isCurrent -> getColor(R.color.brand_indigo)
            isRead -> getColor(R.color.shell_ink_muted)
            else -> getColor(R.color.shell_ink)
        }

        row.addView(TextView(this).apply {
            text = "%02d".format(ch.ordinal + 1)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.shell_ink_muted))
            width = dp(28)
        })

        row.addView(TextView(this).apply {
            text = ch.title
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(titleColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (isRead) {
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, 0, dp(6), 0)
            })
        }

        val right = if (isCurrent) {
            getString(R.string.detail_current_chapter_pages, (progress?.pageInChapter ?: 0) + 1)
        } else {
            getString(R.string.detail_chapter_pages, ch.pageTotal)
        }
        row.addView(TextView(this).apply {
            text = right
            textSize = 12f
            setTextColor(if (isCurrent) getColor(R.color.brand_indigo) else getColor(R.color.shell_ink_muted))
        })

        row.setOnClickListener { openReader(chapterStarts.getOrElse(ch.ordinal) { 0 }) }
        return row
    }

    private fun renderBookmarks(isChaptered: Boolean) {
        binding.bmList.removeAllViews()
        if (bookmarks.isEmpty()) {
            binding.bmHeader.isVisible = false
            return
        }
        binding.bmHeader.isVisible = true

        val pad = dp(6)
        for (bm in bookmarks) {
            val chapter = chapters.getOrNull(bm.chapterOrdinal)
            val row = TextView(this).apply {
                text = if (isChaptered && chapter != null) {
                    getString(
                        R.string.detail_bookmark_row_chapter, chapter.title, bm.pageInChapter + 1
                    )
                } else {
                    getString(R.string.detail_bookmark_row, bm.pageInChapter + 1)
                }
                textSize = 14f
                setPadding(0, pad, 0, pad)
                setTextColor(getColor(R.color.shell_ink))
                setOnClickListener { openReader(globalPageOf(bm)) }
                setOnLongClickListener {
                    confirmDeleteBookmark(bm)
                    true
                }
            }
            binding.bmList.addView(row)
        }
    }

    private fun globalPageOf(bm: BookmarkEntity): Int {
        val start = chapterStarts.getOrElse(bm.chapterOrdinal) { 0 }
        return (start + bm.pageInChapter).coerceAtLeast(0)
    }

    private fun confirmDeleteBookmark(bm: BookmarkEntity) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.detail_bookmark_delete))
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteBookmark(bm) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteBookmark(bm: BookmarkEntity) {
        lifecycleScope.launch {
            db.bookmarkDao().deleteAt(bookId, bm.chapterOrdinal, bm.pageInChapter)
            bookmarks = db.bookmarkDao().forBookNow(bookId)
            renderBookmarks(book!!.isSingleVolume == 0 && chapters.isNotEmpty())
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
