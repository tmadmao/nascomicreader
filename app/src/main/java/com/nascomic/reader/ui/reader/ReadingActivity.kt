package com.nascomic.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityReaderBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.pagePosition
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页。当前做的是「打开一本书、看到第 N 页、点击左右翻页、记住位置」，
 * 以及点屏幕中间呼出的工具栏（返回 / 书名 / 目录 / 页码）。
 *
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
    private val prefs by lazy { ReaderPrefs(this) }

    private var source: BookSource? = null
    private var bookId: Long = 0
    private var requestedPage: Int = -1
    private var page = 0
    private var total = 0

    /** 是否有章的概念。单行本没有，页码按全书算 */
    private var chaptered = false
    private var loading = false
    private var toolbarShown = false
    /** 当前这一屏画了几页：双页开启时可能是 2，翻页步进跟着变 */
    private var shownPages = 1
    /** 跨页拆分：0 看左半，1 看右半。只在宽图上生效 */
    private var half = 0
    /** 当前页是不是宽图 */
    private var currentWide = false
    private var resumedOnce = false

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
        binding.back.setOnClickListener { finish() }
        binding.toc.setOnClickListener { showTocSheet() }
        applySystemBars()
        openBook()
    }

    /**
     * 页面是全屏的（新系统强制边到边），工具栏要自己让开状态栏与导航条，
     * 否则底部那行字会被手势条压住。
     */
    private fun applySystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bars.bottom)
            binding.pageLabel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(20) + bars.bottom
            }
            insets
        }
    }

    /** 把显示类参数应用到画面上。设置改完回来会再调一次。 */
    private fun applyDisplayPrefs() {
        binding.page.canvasColor = when (ReaderPrefs.Canvas.of(prefs.canvas)) {
            ReaderPrefs.Canvas.LIGHT -> 0xFFF4F6F8.toInt()
            ReaderPrefs.Canvas.DARK -> 0xFF2A2E33.toInt()
            ReaderPrefs.Canvas.BLACK -> Color.BLACK
        }
        binding.page.marginPx = dp(prefs.sideMarginDp)
        binding.pageLabel.isVisible = prefs.showPageLabel && !toolbarShown
    }

    private fun openBook() {
        lifecycleScope.launch {
            binding.loading.isVisible = true
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
                    showError(getString(R.string.reader_empty))
                    return@launch
                }

                binding.bookTitle.text = book.title
                chaptered = book.isSingleVolume == 0 && opened.chapters.isNotEmpty()
                // 目录只对章回体有意义
                binding.toc.isVisible = chaptered

                // 页数只在真正打开时才知道，这里补写回库。
                // 单行本没有章的概念，章数记 0，免得界面把它显示成章回体
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
                showError(e.message ?: getString(R.string.reader_open_failed))
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun showPage() {
        val src = source ?: return
        if (loading) return
        // 首帧还没量出尺寸，等布局完再画，否则解码目标会算成 1px
        if (binding.page.width == 0 || binding.page.height == 0) {
            binding.page.post { showPage() }
            return
        }
        loading = true
        binding.loading.isVisible = true

        // 章回体显示章内页码：第 2 话的第 1 页就是 1 / 2，不是全书第 3 / 4
        val pos = src.pagePosition(page, chaptered)
        val label = if (pos.chaptered) {
            getString(R.string.reader_page_chapter_format, pos.chapterTitle, pos.page, pos.total)
        } else {
            getString(R.string.reader_page_format, pos.page, pos.total)
        }
        binding.pageLabel.text = label
        binding.pageLabelBar.text = label
        binding.chapterName.text = if (pos.chaptered) pos.chapterTitle else ""

        val viewW = binding.page.width.coerceAtLeast(1)
        val viewH = binding.page.height.coerceAtLeast(1)
        // 双页时每页只占半宽，解码目标也跟着减半，省一半内存
        val targetW = if (prefs.dualPage) (viewW / 2).coerceAtLeast(1) else viewW

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadSpread(src, targetW, viewH) }
            if (loaded.isEmpty()) {
                showError(getString(R.string.reader_page_failed, pos.page))
            } else {
                binding.error.isVisible = false
                shownPages = loaded.size
                currentWide = loaded.first().wide
                binding.page.setPages(loaded)
            }
            binding.loading.isVisible = false
            loading = false
        }
    }

    /**
     * 取这一屏要画的内容。双页开启时只有两页都不是宽图才并排——
     * 宽图本身就是跨页大图，并排会挤成两条。
     */
    private fun loadSpread(src: BookSource, targetW: Int, targetH: Int): List<ReaderPage> {
        val first = buildPage(src, page, targetW, targetH) ?: return emptyList()
        if (first.wide || !prefs.dualPage) return listOf(first)
        if (page + 1 >= total) return listOf(first)

        val second = buildPage(src, page + 1, targetW, targetH) ?: return listOf(first)
        return if (second.wide) listOf(first) else listOf(first, second)
    }

    /**
     * 解一页，并按参数决定取哪一块：裁白边、跨页拆分都只改源矩形，不复制位图。
     */
    private fun buildPage(src: BookSource, index: Int, targetW: Int, targetH: Int): ReaderPage? {
        val bitmap = runCatching {
            PageBitmapLoader.load(src.openPage(index), targetW, targetH)
        }.getOrNull() ?: return null

        val bounds = if (prefs.trimBorder) {
            TrimBounds.detect(bitmap)
        } else {
            TrimBounds.Bounds(0, 0, bitmap.width, bitmap.height)
        }
        val full = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val wide = full.width() > full.height() * 1.2f

        if (wide && prefs.splitWide) {
            val mid = full.left + full.width() / 2
            val part = if (half == 1) {
                Rect(mid, full.top, full.right, full.bottom)
            } else {
                Rect(full.left, full.top, mid, full.bottom)
            }
            return ReaderPage(bitmap, part, wide = true)
        }
        return ReaderPage(bitmap, full, wide = wide)
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.isVisible = true
        binding.pageLabel.text = ""
        binding.loading.isVisible = false
        if (toolbarShown) setToolbarVisible(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP && source != null && !overToolbar(ev)) {
            val x = ev.x / (binding.readerRoot.width.coerceAtLeast(1))
            // 方向可切：从左往右读时点右侧翻到下一页，从右往左读时反过来
            val forwardRight = prefs.flipForwardRight
            when {
                x < ZONE_LEFT -> if (forwardRight) turnBackward() else turnForward()
                x > ZONE_RIGHT -> if (forwardRight) turnForward() else turnBackward()
                else -> setToolbarVisible(!toolbarShown)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 点在工具栏上时不翻页也不收起工具栏，把事件留给按钮 */
    private fun overToolbar(ev: MotionEvent): Boolean {
        if (!toolbarShown) return false
        val inTop = binding.topBar.isVisible && ev.y <= binding.topBar.bottom
        val inBottom = binding.bottomBar.isVisible && ev.y >= binding.bottomBar.top
        return inTop || inBottom
    }

    private fun setToolbarVisible(show: Boolean) {
        toolbarShown = show
        // 常驻页码与工具栏里的页码只留一个，避免重复
        binding.pageLabel.isVisible = !show && prefs.showPageLabel
        binding.topBar.isVisible = show
        binding.bottomBar.isVisible = show
        if (show) {
            binding.topBar.alpha = 0f
            binding.bottomBar.alpha = 0f
            binding.topBar.animate().alpha(1f).setDuration(140).start()
            binding.bottomBar.animate().alpha(1f).setDuration(140).start()
        }
    }

    /** 章节目录。当前章标出来，点任一章跳过去 */
    private fun showTocSheet() {
        val src = source ?: return
        if (!chaptered) return

        val sheet = BottomSheetDialog(this)
        val content = LayoutInflater.from(this)
            .inflate(R.layout.sheet_reader_toc, binding.readerRoot, false)
        val list = content.findViewById<LinearLayout>(R.id.toc_list)
        val scroll = content.findViewById<View>(R.id.toc_scroll)

        val current = src.chapterOf(page)
        var currentRow: View? = null
        val padH = dp(10)
        val padV = dp(13)

        for (info in src.chapters) {
            val row = TextView(this).apply {
                text = if (info.pageCount > 0) {
                    getString(
                        R.string.reader_toc_row_pages,
                        info.ordinal + 1, info.title, info.pageCount
                    )
                } else {
                    getString(R.string.reader_toc_row, info.ordinal + 1, info.title)
                }
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setTextColor(
                    if (info.ordinal == current) getColor(R.color.brand_indigo_soft)
                    else getColor(android.R.color.white)
                )
                setOnClickListener {
                    sheet.dismiss()
                    setToolbarVisible(false)
                    turnTo(src.firstPageOfChapter(info.ordinal))
                }
            }
            if (info.ordinal == current) currentRow = row
            list.addView(row)
        }

        sheet.setContentView(content)
        sheet.show()
        // 章多的时候把当前章滚到可见位置
        currentRow?.let { row ->
            scroll.post { scroll.scrollTo(0, (row.top - scroll.height / 3).coerceAtLeast(0)) }
        }
    }

    /** 前进一屏：并排时一次跨两页；拆页时先看右半再翻页 */
    private fun turnForward() {
        if (splitActive() && half == 0) {
            half = 1
            showPage()
            return
        }
        turnTo(page + shownPages.coerceAtLeast(1), newHalf = 0)
    }

    private fun turnBackward() {
        if (splitActive() && half == 1) {
            half = 0
            showPage()
            return
        }
        // 后退落到宽图时先看右半，符合「从后往前」的直觉
        turnTo(page - shownPages.coerceAtLeast(1), newHalf = 1)
    }

    private fun splitActive(): Boolean = currentWide && prefs.splitWide

    private fun turnTo(target: Int, newHalf: Int = 0) {
        if (loading) return
        if (target < 0 || target >= total) return
        half = newHalf
        page = target
        showPage()
        saveProgress()
    }

    private fun saveProgress() {
        val src = source ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val chapter = src.chapterOf(page)
                db.progressDao().upsert(
                    ProgressEntity(
                        bookId = bookId,
                        chapterOrdinal = chapter,
                        pageInChapter = page - src.firstPageOfChapter(chapter),
                        pageGlobal = page,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 工具栏开着时先收起，再按一次才退出阅读
        if (toolbarShown) {
            setToolbarVisible(false)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val first = !resumedOnce
        resumedOnce = true
        applyDisplayPrefs()
        // 从设置页回来时参数可能变了，按新参数重画当前页
        if (!first && source != null && !loading) showPage()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { source?.close() }
        binding.page.setPages(emptyList())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
