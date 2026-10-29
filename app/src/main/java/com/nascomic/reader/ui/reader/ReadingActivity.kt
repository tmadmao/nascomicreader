package com.nascomic.reader.ui.reader

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
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
import com.nascomic.reader.format.RenderedPageSource
import com.nascomic.reader.format.pagePosition
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs
import com.nascomic.reader.settings.applyOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页。翻页、双页、裁白边、跨页拆分按 [ReaderPrefs] 走；点屏幕中间呼出工具栏
 * （返回 / 书名 / 上一页 / 设置 / 书签 / 目录 / 下一页），整本读完再往后翻停在书的尾。
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
    private val appPrefs by lazy { AppPrefs(this) }

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
    /** 当前页有没有书签 */
    private var bookmarked = false
    /** 是否停在「书的尾」整屏空态上 */
    private var atEnd = false
    /** 这本书读到过尾没有。双页并排时只看页号判断不出看完，单独记 */
    private var finishedOnce = false

    /** 书名：顶栏要拼「书名 · 话名」，所以单独留一份 */
    private var bookTitle: String = ""
    private var resumedOnce = false

    /** 内存缓存：上限按偏好在进来时定，翻过去的页让位 */
    private val pageCache by lazy { PageCache(prefs.cacheBudgetBytes()) }

    /** 磁盘缓存：只有远程书值得落盘，本地文件再读一遍本来就快 */
    private val diskCache by lazy { DiskPageCache(this) }
    private var cacheToDisk = false

    /** 首屏不淡入：刚进来就闪一下容易被当成加载失败 */
    private var drawnOnce = false

    /** 正在跑的预载：翻页时掐掉重开，免得旧窗口的页还在往下解 */
    private var prefetchJob: Job? = null

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
        binding.openToc.setOnClickListener { showTocSheet() }
        binding.toc.setOnClickListener { showTocSheet() }
        binding.bookmark.setOnClickListener { toggleBookmark() }
        binding.prevPage.setOnClickListener { turnBackward() }
        binding.nextPage.setOnClickListener { turnForward() }
        binding.openSettings.setOnClickListener {
            startActivity(android.content.Intent(this, com.nascomic.reader.ui.settings.SettingsActivity::class.java))
        }
        binding.endBack.setOnClickListener { backToShelf() }
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
        val canvas = when (ReaderPrefs.Canvas.of(prefs.canvas)) {
            ReaderPrefs.Canvas.LIGHT -> 0xFFF2EDE3.toInt()
            ReaderPrefs.Canvas.DARK -> 0xFF2A2E33.toInt()
            ReaderPrefs.Canvas.BLACK -> Color.BLACK
        }
        // 裁边阈值、双页、拆页变了，缓存里的页已经不是同一个样子
        pageCache.clear()
        binding.page.canvasColor = canvas
        applyEndTheme(canvas)
        binding.page.marginPx = dp(prefs.sideMarginDp)
        binding.pageLabel.isVisible = prefs.showPageLabel && !toolbarShown && !atEnd
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 书的尾跟着画布底色走：浅底石墨字，深底转暖白，不在深底上留一块浅色方块 */
    private fun applyEndTheme(canvas: Int) {
        val light = ReaderPrefs.Canvas.of(prefs.canvas) == ReaderPrefs.Canvas.LIGHT
        val muted = if (light) 0xFF6F6A62.toInt() else 0xFFA9AEB4.toInt()
        binding.endPage.setBackgroundColor(canvas)
        binding.endTitle.setTextColor(if (light) 0xFF1B1C20.toInt() else 0xFFEDEAE4.toInt())
        binding.endMeta.setTextColor(muted)
        binding.endSay.setTextColor(muted)
        binding.endTip.setTextColor(muted)
        // 按钮也得跟着翻：深色画布上的墨色按钮等于看不见
        if (light) {
            binding.endBack.setBackgroundResource(R.drawable.bg_pill_ink)
            binding.endBack.setTextColor(0xFFFFFFFF.toInt())
        } else {
            binding.endBack.setBackgroundResource(R.drawable.bg_pill_paper)
            binding.endBack.setTextColor(0xFF1B1C20.toInt())
        }
    }

    private fun openBook() {
        lifecycleScope.launch {
            binding.loading.isVisible = true
            try {
                val book = db.bookDao().get(bookId) ?: error("书不存在")
                val src = db.sourceDao().get(book.sourceId) ?: error("来源不存在")
                val chapters = db.chapterDao().forBook(bookId)

                val opened = withContext(Dispatchers.IO) {
                    opener.open(book, src, chapters)
                }

                // 远程书每页都要连一次网络，值得留一份；本地文件解码本来就快
                cacheToDisk = src.kind != com.nascomic.reader.library.data.SourceEntity.KIND_LOCAL

                source = opened
                total = opened.pageCount
                if (total == 0) {
                    showError(getString(R.string.reader_empty))
                    return@launch
                }

                bookTitle = book.title
                binding.bookTitle.text = book.title
                chaptered = book.isSingleVolume == 0 && opened.chapters.isNotEmpty()
                // 目录只对章回体有意义
                binding.toc.isVisible = chaptered

                // 页数只在真正打开时才知道，这里补写回库。
                // 单行本没有章的概念，章数记 0，免得界面把它显示成章回体
                val chapterCount = if (chaptered) opened.chapters.size else 0
                db.bookDao().setTotals(bookId, total, chapterCount)

                val saved = db.progressDao().forBook(bookId)
                finishedOnce = saved?.finished ?: false
                page = if (requestedPage >= 0) {
                    requestedPage.coerceIn(0, total - 1)
                } else {
                    saved?.pageGlobal?.coerceIn(0, total - 1) ?: 0
                }
                showPage()
                saveProgress()
            } catch (e: Exception) {
                // 打不开很常见（包损坏、授权丢了、远端断开）。界面只给一句人话，
                // 具体是哪个库报的什么错，留给日志——原生库的原文还是英文
                android.util.Log.w("Reading", "打不开书：$bookId", e)
                showError(getString(R.string.reader_open_failed))
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun showPage() {
        val src = source ?: return
        if (loading) return
        // 书的尾占着屏时不画页面，等往回翻再重解
        if (atEnd) return
        // 首帧还没量出尺寸，等布局完再画，否则解码目标会算成 1px
        if (binding.page.width == 0 || binding.page.height == 0) {
            binding.page.post { showPage() }
            return
        }
        loading = true
        binding.loading.isVisible = true

        val viewW = binding.page.width.coerceAtLeast(1)
        val viewH = binding.page.height.coerceAtLeast(1)
        // 双页时每页只占半宽，解码目标也跟着减半，省一半内存
        val targetW = if (prefs.dualPage) (viewW / 2).coerceAtLeast(1) else viewW
        // 转屏或换双页后目标尺寸变了：旧尺寸解出来的图会发虚，整份丢掉
        pageCache.ensureGeometry(targetW, viewH)

        lifecycleScope.launch {
            // 跳过空白页会改页码，所以整段在 IO 线程里定下来再回来更新界面
            val spread = withContext(Dispatchers.IO) { resolveSpread(src, targetW, viewH) }
            if (spread.pages.isEmpty()) {
                showError(getString(R.string.reader_page_failed, spread.index + 1))
            } else {
                page = spread.index
                updateLabels(src)
                binding.error.isVisible = false
                shownPages = spread.pages.size
                currentWide = spread.pages.first().wide
                // 这一屏已经含最后一页，往后翻就是书的尾
                if (page + shownPages >= total) finishedOnce = true
                binding.page.setPages(spread.pages)
                if (prefs.turnAnimation && drawnOnce) {
                    binding.page.alpha = 0.35f
                    binding.page.animate().alpha(1f).setDuration(140).start()
                }
                drawnOnce = true
            }
            binding.loading.isVisible = false
            loading = false
            saveProgress()
            startPrefetch(src, targetW, viewH)
        }
    }

    /** 一屏的内容，以及它实际对应哪一页（跳过空白页后可能不等于进来时的页码） */
    private class Spread(val pages: List<ReaderPage>, val index: Int)

    /**
     * 取这一屏要画的内容。开了跳过空白页就继续往后找，最多连跳 20 页防止整本都空。
     */
    private fun resolveSpread(src: BookSource, targetW: Int, targetH: Int): Spread {
        var index = page
        var pages = loadSpread(src, index, targetW, targetH)
        // 双页并排时不跳：一次两张，跳掉一张会让配对错乱
        val canSkip = prefs.skipBlank && !prefs.dualPage
        var guard = 0
        while (canSkip && guard < 20 && index < total - 1 && pages.firstOrNull()?.let(::isBlankPage) == true) {
            index++
            half = 0
            pages = loadSpread(src, index, targetW, targetH)
            guard++
        }
        return Spread(pages, index)
    }

    private fun isBlankPage(page: ReaderPage): Boolean =
        TrimBounds.isBlank(page.bitmap, prefs.trimLevel)

    /** 当前页加书签 / 取消书签 */
    private fun toggleBookmark() {
        val src = source ?: return
        val chapter = src.chapterOf(page)
        val pageInChapter = page - src.firstPageOfChapter(chapter)

        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                val existing = db.bookmarkDao().find(bookId, chapter, pageInChapter)
                if (existing == null) {
                    db.bookmarkDao().insert(
                        BookmarkEntity(
                            bookId = bookId,
                            chapterOrdinal = chapter,
                            pageInChapter = pageInChapter,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    true
                } else {
                    db.bookmarkDao().deleteAt(bookId, chapter, pageInChapter)
                    false
                }
            }
            bookmarked = added
            updateBookmarkIcon()
            Toast.makeText(
                this@ReadingActivity,
                if (added) R.string.reader_bookmark_added else R.string.reader_bookmark_removed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 翻页后重新查一次，图标跟着变 */
    private fun refreshBookmarkState() {
        val src = source ?: return
        val chapter = src.chapterOf(page)
        val pageInChapter = page - src.firstPageOfChapter(chapter)
        lifecycleScope.launch {
            bookmarked = withContext(Dispatchers.IO) {
                runCatching { db.bookmarkDao().find(bookId, chapter, pageInChapter) != null }
                    .getOrDefault(false)
            }
            updateBookmarkIcon()
        }
    }

    private fun updateBookmarkIcon() {
        binding.bookmarkIcon.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_on else R.drawable.ic_bookmark
        )
    }

    /** 页码、章名。章回体按章内页显示 */
    private fun updateLabels(src: BookSource) {
        val pos = src.pagePosition(page, chaptered)
        binding.pageLabel.text = if (pos.chaptered) {
            getString(R.string.reader_page_chapter_format, pos.chapterTitle, pos.page, pos.total)
        } else {
            getString(R.string.reader_page_format, pos.page, pos.total)
        }
        // 顶栏两行：书名在上、话名在下；单行本没有话就只留书名
        binding.bookTitle.text = bookTitle
        binding.chapterLabel.isVisible = pos.chaptered
        binding.chapterLabel.text = if (pos.chaptered) pos.chapterTitle else ""
        // 整本读到哪：顶栏底部那条细线
        binding.bookProgress.progress = if (total > 0) {
            ((page + 1) * 100 / total).coerceIn(0, 100)
        } else {
            0
        }
        refreshBookmarkState()
    }

    /**
     * 取这一屏要画的内容。双页开启时只有两页都不是宽图才并排——
     * 宽图本身就是跨页大图，并排会挤成两条。
     */
    private fun loadSpread(src: BookSource, index: Int, targetW: Int, targetH: Int): List<ReaderPage> {
        val first = buildPage(src, index, targetW, targetH) ?: return emptyList()
        if (first.wide || !prefs.dualPage) return listOf(first)
        if (index + 1 >= total) return listOf(first)

        val second = buildPage(src, index + 1, targetW, targetH) ?: return listOf(first)
        return if (second.wide) listOf(first) else listOf(first, second)
    }

    /**
     * 取一页：内存 → 磁盘 → 解一次并写回两处。
     * 裁白边与跨页拆分都只改源矩形，不复制位图，所以缓存里放的是「解好的整页」。
     */
    private fun buildPage(src: BookSource, index: Int, targetW: Int, targetH: Int): ReaderPage? {
        val entry = pageCache.get(index) ?: run {
            val loaded = loadPage(src, index, targetW, targetH) ?: return null
            pageCache.put(index, loaded)
            loaded
        }
        return pageOf(entry)
    }

    /**
     * 拿一页解好的样子：内存里没有就去磁盘翻，磁盘也没有才真去读源。
     * 键里带着目标尺寸与裁边参数，所以换尺寸 / 改阈值自然落到新文件上，旧图不会被误用。
     */
    private fun loadPage(src: BookSource, index: Int, targetW: Int, targetH: Int): PageCache.Entry? {
        val key = PageCacheKey.of(index, targetW, targetH, prefs.trimBorder, prefs.trimLevel)
        if (cacheToDisk) {
            diskCache.load(bookId, key)?.let { bitmap ->
                return PageCache.Entry(bitmap, boundsOf(bitmap), bitmap.width > bitmap.height * 1.2f)
            }
        }
        val decoded = decodePage(src, index, targetW, targetH) ?: return null
        if (cacheToDisk) diskCache.save(bookId, key, decoded.bitmap)
        return decoded
    }

    private fun boundsOf(bitmap: Bitmap): Rect {
        val bounds = if (prefs.trimBorder) {
            TrimBounds.detect(bitmap, prefs.trimLevel)
        } else {
            TrimBounds.Bounds(0, 0, bitmap.width, bitmap.height)
        }
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /** 解一页并量出裁边范围。画当前页与后台预载都走这条路，两边存的必须是同一种东西 */
    private fun decodePage(src: BookSource, index: Int, targetW: Int, targetH: Int): PageCache.Entry? {
        val bitmap = runCatching {
            if (src is RenderedPageSource) {
                src.renderPage(index, targetW, targetH)
            } else {
                PageBitmapLoader.load(src.openPage(index), targetW, targetH)
            }
        }.getOrNull() ?: return null

        val full = boundsOf(bitmap)
        return PageCache.Entry(bitmap, full, full.width() > full.height() * 1.2f)
    }

    /** 把缓存里的那一页摆成这一屏要的样子：跨页拆分按 [half] 取左半或右半 */
    private fun pageOf(entry: PageCache.Entry): ReaderPage {
        val full = entry.bounds
        if (entry.wide && prefs.splitWide) {
            val mid = full.left + full.width() / 2
            val part = if (half == 1) {
                Rect(mid, full.top, full.right, full.bottom)
            } else {
                Rect(full.left, full.top, mid, full.bottom)
            }
            return ReaderPage(entry.bitmap, part, wide = true)
        }
        return ReaderPage(entry.bitmap, full, wide = entry.wide)
    }

    /**
     * 往后预取几页。翻页时等的是「连远端 + 解码」，先把这几页解好放进缓存，翻过去就直接出图。
     *
     * 每次只跑一个预载任务，翻页时把上一个掐掉；缓存里超出窗口的页同时丢掉。
     */
    private fun startPrefetch(src: BookSource, targetW: Int, targetH: Int) {
        prefetchJob?.cancel()
        val ahead = prefs.prefetchPages
        pageCache.keepOnly(PageWindow.keep(page, ahead, total))
        val range = PageWindow.prefetch(page, ahead, total)
        if (range.isEmpty()) return
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            for (index in range) {
                if (!isActive) return@launch
                if (pageCache.get(index) != null) continue
                runCatching { loadPage(src, index, targetW, targetH) }
                    .getOrNull()?.let { pageCache.put(index, it) }
            }
            // 一轮预载写完再看要不要清盘，比每写一页都扫一遍目录省事
            if (cacheToDisk) diskCache.trim(bookId)
        }
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.isVisible = true
        binding.pageLabel.text = ""
        binding.loading.isVisible = false
        if (toolbarShown) setToolbarVisible(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP && source != null &&
            !overToolbar(ev) && !overEndButton(ev)
        ) {
            val x = ev.x / (binding.readerRoot.width.coerceAtLeast(1))
            // 关掉点击翻页之后，点哪儿都只呼出工具栏——留给只想看不想误翻的人
            if (!prefs.tapToTurn) {
                setToolbarVisible(!toolbarShown)
                return super.dispatchTouchEvent(ev)
            }
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

    /** 书的尾上只有「回到书库」一个按钮，点它别顺带翻页 */
    private fun overEndButton(ev: MotionEvent): Boolean {
        if (!atEnd) return false
        val hit = Rect()
        binding.endBack.getHitRect(hit)
        return hit.contains(ev.x.toInt(), ev.y.toInt())
    }

    private fun setToolbarVisible(show: Boolean) {
        toolbarShown = show
        // 常驻页码与工具栏里的页码只留一个，避免重复；书的尾上也不显示
        binding.pageLabel.isVisible = !show && prefs.showPageLabel && !atEnd
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
            val isCurrent = info.ordinal == current
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
                setTextColor(getColor(if (isCurrent) R.color.brand_accent else R.color.shell_ink))
                if (isCurrent) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(getColor(R.color.brand_accent_tint))
                    }
                }
                setOnClickListener {
                    sheet.dismiss()
                    setToolbarVisible(false)
                    turnTo(src.firstPageOfChapter(info.ordinal))
                }
            }
            if (isCurrent) currentRow = row
            list.addView(row)
        }

        sheet.setContentView(content)
        sheet.show()
        // 章多的时候把当前章滚到可见位置
        currentRow?.let { row ->
            scroll.post { scroll.scrollTo(0, (row.top - scroll.height / 3).coerceAtLeast(0)) }
        }
    }

    /** 前进一屏：并排时一次跨两页；拆页时先看右半再翻页；最后一屏之后进书的尾 */
    private fun turnForward() {
        if (atEnd) return
        if (splitActive() && half == 0) {
            half = 1
            showPage()
            return
        }
        val step = shownPages.coerceAtLeast(1)
        val target = PageTurn.nextPage(page, step, total)
        if (target == null) {
            showEndPage()
            return
        }
        turnTo(target, newHalf = 0)
    }

    private fun turnBackward() {
        // 书的尾往回翻就是最后一页，不是翻不动
        if (atEnd) {
            leaveEndPage()
            return
        }
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

    /**
     * 整本书看完：清掉当前页位图，换成一块空态。
     * 进度仍停在最后一页，回来时接着从那里看。
     */
    private fun showEndPage() {
        if (atEnd || source == null) return
        atEnd = true
        half = 0
        binding.page.setPages(emptyList())
        binding.error.isVisible = false
        binding.loading.isVisible = false
        binding.pageLabel.isVisible = false
        binding.endMeta.text = endMetaText()
        binding.endPage.isVisible = true
        if (toolbarShown) setToolbarVisible(false)
    }

    private fun leaveEndPage() {
        if (!atEnd) return
        atEnd = false
        binding.endPage.isVisible = false
        // 最后一页是宽图且开着拆页时，从后往前落回右半
        half = if (currentWide && prefs.splitWide) 1 else 0
        showPage()
    }

    private fun endMetaText(): String {
        val src = source ?: return ""
        return if (chaptered) {
            getString(R.string.reader_end_meta_chapter, src.chapters.size, total)
        } else {
            getString(R.string.reader_end_meta_pages, total)
        }
    }

    /** 书的尾上的「回到书库」：清掉详情页与阅读页，直接回书架挑下一本 */
    private fun backToShelf() {
        startActivity(
            android.content.Intent(this, com.nascomic.reader.ui.MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
        finish()
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
                        finished = finishedOnce,
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
        applyOrientation(appPrefs)
        applyDisplayPrefs()
        // 从设置页回来时参数可能变了，按新参数重画当前页
        if (!first && source != null && !loading) showPage()
    }

    /** 锁了方向后屏幕尺寸会变，当前页要按新尺寸重解一次 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!firstLayoutPending()) showPage()
    }

    private fun firstLayoutPending(): Boolean =
        binding.page.width == 0 || binding.page.height == 0

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
