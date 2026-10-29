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
import com.nascomic.reader.ui.widget.padBySystemBars
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

    /** 目录是否倒序显示：跟着偏好走，点目录头的按钮就翻 */
    private var tocDescending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        if (bookId == 0L) {
            finish()
            return
        }
        binding.back.setOnClickListener { finish() }
        tocDescending = appPrefs.tocDescending
        applyHeroMetrics()
        load()
    }

    /**
     * 封面剧场按屏宽算尺寸：手机上封面约占四成宽，平板封顶 220dp。
     * 固定 152dp 在平板上会缩成一张小卡片，四周全是深色空场。
     */
    private fun applyHeroMetrics() {
        val width = resources.displayMetrics.widthPixels
        val coverW = (width * 0.42f).toInt().coerceIn(dp(150), dp(220))
        val coverH = coverW * 4 / 3
        binding.coverCard.layoutParams = android.widget.FrameLayout.LayoutParams(
            coverW, coverH, Gravity.CENTER
        )
        binding.hero.layoutParams = binding.hero.layoutParams.also {
            it.height = coverH + dp(74)
        }
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

        val entries = tocEntries(b)
        val isChaptered = b.isSingleVolume == 0 && chapters.isNotEmpty()
        val p = progress
        val hasProgress = p != null && b.pageTotal > 0

        if (hasProgress) {
            val entry = entries.getOrNull(p!!.chapterOrdinal)
            val byChapter = isChaptered && entry != null && entry.pageTotal > 0
            val percent = readPercent(p.pageGlobal, b.pageTotal, p.finished)

            binding.percent.text = getString(R.string.shelf_percent, percent)
            binding.progressBar.progress = percent
            binding.readInfo.text = when {
                p.finished -> getString(R.string.progress_finished)
                byChapter -> getString(
                    R.string.detail_read_position_chapter, entry!!.ordinal + 1, entry.title
                )
                else -> getString(
                    R.string.detail_read_position_pages, p.pageGlobal + 1, b.pageTotal
                )
            }

            binding.primary.text = if (p.finished) {
                getString(R.string.detail_reread)
            } else {
                getString(R.string.detail_continue_reading_short)
            }
        } else {
            binding.percent.text = getString(R.string.shelf_percent, 0)
            binding.progressBar.progress = 0
            binding.readInfo.text = getString(R.string.detail_not_started)
            binding.primary.text = getString(R.string.detail_start_reading)
        }

        // 一个按钮管三种情形：没读过 / 读到一半 / 读完了重来
        val startPage = if (p == null || p.finished) 0 else p.pageGlobal
        binding.primary.setOnClickListener { openReader(startPage) }

        // 各章起始页：靠每章页数累加，读不到页数时退化为 0（点了也从章首开始）
        chapterStarts = IntArray(entries.size)
        var acc = 0
        entries.forEachIndexed { i, entry ->
            chapterStarts[i] = acc
            acc += entry.pageTotal
        }

        renderToc(entries)
        renderBookmarks(entries)
    }

    /**
     * 目录里的一行。单行本在库里没有章记录，但它本身就是一章——
     * 补一条占位，「有一章就列一章、有多章按顺序排」在两种书型上走同一套逻辑。
     */
    private data class TocEntry(val ordinal: Int, val title: String, val pageTotal: Int)

    private fun tocEntries(b: BookEntity): List<TocEntry> = when {
        chapters.isNotEmpty() -> chapters.map { TocEntry(it.ordinal, it.title, it.pageTotal) }
        // 单行本这一章的标题就是这本书的标题（扫描时从文件名取的）
        b.isSingleVolume == 1 -> listOf(TocEntry(0, b.title, b.pageTotal))
        else -> emptyList()
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
     * 目录：序号 · 话名 · 页数 三栏。单行本列一行（它就是一章），多章按顺序排。
     * 已读的话在页数前打 ✓ 并把字降到次要色；当前话整行铺橙调浅底、右侧改写成「当前」。
     */
    private fun renderToc(entries: List<TocEntry>) {
        binding.tocList.removeAllViews()

        binding.tocHeader.isVisible = true
        binding.tocHeader.text = getString(R.string.detail_toc_with_count)

        if (entries.isEmpty()) {
            // 页数还没数出来（或被判定打不开）：标题留着，下面说一句为什么空
            binding.tocCount.isVisible = false
            binding.tocHint.isVisible = true
            binding.tocHint.text = if (countFailed) {
                getString(R.string.detail_count_failed)
            } else {
                getString(R.string.detail_counting)
            }
            renderOrderToggle(emptyList())
            return
        }

        binding.tocCount.isVisible = true
        binding.tocCount.text = getString(R.string.detail_toc_chapters, entries.size)
        binding.tocHint.isVisible = false
        renderOrderToggle(entries)

        val currentChapter = progress?.chapterOrdinal ?: -1
        val ordered = if (tocDescending) entries.asReversed() else entries
        for (entry in ordered) {
            val isCurrent = entry.ordinal == currentChapter
            val isRead = entry.ordinal < currentChapter ||
                (isCurrent && (progress?.pageInChapter == entry.pageTotal - 1))
            binding.tocList.addView(tocRow(entry, isCurrent, isRead))
        }
    }

    /** 目录头的排序钮：只有一个章的书写「正序 / 倒序」没有意义，直接不出现 */
    private fun renderOrderToggle(entries: List<TocEntry>) {
        if (entries.size <= 1) {
            binding.tocOrder.visibility = View.GONE
            return
        }
        binding.tocOrder.visibility = View.VISIBLE
        // 按钮写的是「现在是什么序」，不是「点一下会变成什么」——后者读起来正好相反
        binding.tocOrderLabel.text = getString(
            if (tocDescending) R.string.detail_order_desc else R.string.detail_order_asc
        )
        binding.tocOrder.setOnClickListener {
            tocDescending = !tocDescending
            appPrefs.tocDescending = tocDescending
            render()
        }
    }

    private fun tocRow(
        entry: TocEntry,
        isCurrent: Boolean,
        isRead: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = if (isCurrent) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(getColor(R.color.brand_accent_tint))
                }
            } else {
                null
            }
        }

        val titleColor = when {
            isCurrent -> getColor(R.color.shell_ink)
            isRead -> getColor(R.color.shell_ink_muted)
            else -> getColor(R.color.shell_ink)
        }

        row.addView(TextView(this).apply {
            text = "%02d".format(entry.ordinal + 1)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.shell_ink_faint))
            width = dp(26)
        })

        row.addView(TextView(this).apply {
            text = entry.title
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(titleColor)
            setTypeface(typeface, if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (isRead) {
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_faint))
                setPadding(0, 0, dp(6), 0)
            })
        }

        if (isCurrent) {
            row.addView(TextView(this).apply {
                text = getString(R.string.detail_current_badge)
                textSize = 10f
                setTextColor(getColor(android.R.color.white))
                background = getDrawable(R.drawable.bg_badge_accent)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
        } else {
            row.addView(TextView(this).apply {
                text = getString(R.string.detail_chapter_pages, entry.pageTotal)
                textSize = 11f
                setTextColor(getColor(R.color.shell_ink_faint))
            })
        }

        row.setOnClickListener { openReader(chapterStarts.getOrElse(entry.ordinal) { 0 }) }

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row)
        wrapper.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.shell_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(12)
            },
        )
        return wrapper
    }

    private fun renderBookmarks(entries: List<TocEntry>) {
        binding.bmList.removeAllViews()
        if (bookmarks.isEmpty()) {
            binding.bmHeader.isVisible = false
            return
        }
        binding.bmHeader.isVisible = true

        val pad = dp(6)
        for (bm in bookmarks) {
            // 书签只在真有多章时带上话名；单行本那条占位章名不必重复
            val chapter = if (chapters.isEmpty()) null else entries.getOrNull(bm.chapterOrdinal)
            val row = TextView(this).apply {
                text = if (chapter != null) {
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
            renderBookmarks(book?.let { tocEntries(it) }.orEmpty())
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
