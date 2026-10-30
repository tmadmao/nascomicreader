package com.nascomic.reader.ui.reader

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
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
import kotlin.concurrent.withLock

/**
 * 阅读页。翻页、双页、裁白边、跨页拆分按 [ReaderPrefs] 走；点屏幕中间呼出工具栏
 * （返回 / 书名 / 上一页 / 设置 / 书签 / 目录 / 下一页），整本读完再往后翻停在书的尾。
 */
class ReadingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        /** 指定从哪一页开始读；不传则接着上次的进度 */
        const val EXTRA_START_PAGE = "start_page"

        /** 从头再看：带着它进来会清掉「已读完」标记 */
        const val EXTRA_RESTART = "restart"
        /** 左右翻页的分区线，与后续设置项保持一致 */
        private const val ZONE_LEFT = 0.35f
        private const val ZONE_RIGHT = 0.65f
        /** 原尺寸解码时给解码器一个足够大的上限，等于不按屏幕降采样 */
        private const val NATIVE_TARGET = Int.MAX_VALUE / 2

        /** 预载并发数：两个够把单核解码的时间藏起来，再多会抢主线程的 CPU */
        private const val PREFETCH_WORKERS = 2

        /** 竖向窗口：当前页前留一页、后留四页，滚到头再按需接下一张 */
        private const val VERTICAL_BACK = 1
        private const val VERTICAL_AHEAD = 4

        /** 竖排窗口最多留几页：超过这个数怎么也要收，再多滚动本身也变笨重 */
        private const val VERTICAL_MAX_PAGES = 12

        /** 竖排窗口至少留几页：太少了接页会一顿一顿 */
        private const val VERTICAL_MIN_PAGES = 3

        /** 横向滑动翻页的最低速度（px/s）：太慢的滑动当作没滑 */
        private const val FLING_MIN_VELOCITY = 600f

        /** 关源时最多等当前那一页解多久；等不到就放着，进程回收时一起收 */
        private const val CLOSE_WAIT_MS = 2000L

        /** 竖排惯性滑动的时长，以及一帧按多少秒折算位移 */
        private const val VERTICAL_FLING_MS = 520L
        private const val FRAME_SECONDS = 0.016f
    }

    private lateinit var binding: ActivityReaderBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val opener by lazy { BookOpener(this) }

    /** 按书取参数：这本书单独调过的项优先，其余回落到全局默认 */
    private lateinit var prefs: ReaderPrefs
    private val appPrefs by lazy { AppPrefs(this) }

    private var source: BookSource? = null

    /** 解码与关源互斥用的锁；关源后 [sourceClosed] 置真，后续解码直接放弃 */
    private val sourceLock = java.util.concurrent.locks.ReentrantLock()

    @Volatile
    private var sourceClosed = false
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

    /** 书的大小：页缓存的键里带上它，远程书换了内容就不会命中旧页 */
    private var bookStamp: Long = 0L
    private var resumedOnce = false

    /** 内存缓存：上限按偏好在进来时定，翻过去的页让位。放到 Holder 里，设置页才统计得到 */
    private val pageCache by lazy {
        PageCache(prefs.cacheBudgetBytes()).also { PageCacheHolder.install(it) }
    }

    /** 磁盘缓存：只有远程书值得落盘，本地文件再读一遍本来就快 */
    private val diskCache by lazy {
        DiskPageCache(this, prefs.diskBudgetBytes(filesDir.usableSpace))
    }
    private var cacheToDisk = false

    /** 首屏不淡入：刚进来就闪一下容易被当成加载失败 */
    private var drawnOnce = false

    /** 分区提示每次进阅读只闪一次 */
    private var zoneHintShown = false

    /** 竖向窗口里第一页的全局页号，页码与进度都按它换算 */
    private var verticalFirst = 0
    private var lastSaveAt = 0L

    /** 上一屏还在解的时候又翻了一次：解完立刻补画，别把这次点击丢掉 */
    private var pendingShow = false

    /** 上一次应用显示参数时的快照：参数没变就别清缓存 */
    private var displayKey: String? = null

    /** 预载队列：翻页只放页号进来，常驻 worker 慢慢解 */
    private val prefetchQueue = kotlinx.coroutines.channels.Channel<Int>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )
    private val prefetchQueued = HashSet<Int>()
    private var prefetchWorkersStarted = false

    @Volatile
    private var prefetchTargetW = 0

    @Volatile
    private var prefetchTargetH = 0

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
        prefs = ReaderPrefs(this, bookId)
        binding.back.setOnClickListener { finish() }
        binding.openToc.setOnClickListener { showTocSheet() }
        binding.toc.setOnClickListener { showTocSheet() }
        binding.bookmark.setOnClickListener { toggleBookmark() }
        binding.prevPage.setOnClickListener { turnBackward() }
        binding.nextPage.setOnClickListener { turnForward() }
        binding.openSettings.setOnClickListener {
            startActivity(
                android.content.Intent(
                    this,
                    com.nascomic.reader.ui.settings.SettingsActivity::class.java,
                )
                    .putExtra(com.nascomic.reader.ui.settings.SettingsActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(
                        com.nascomic.reader.ui.settings.SettingsActivity.EXTRA_BOOK_TITLE,
                        bookTitle,
                    )
            )
        }
        binding.endBack.setOnClickListener { backToShelf() }
        // 返回：工具栏开着先收起，收着才退出阅读。
        // 走 OnBackPressedDispatcher 而不是重写 onBackPressed——后者在 Android 16
        // 的预测性返回下不再被调用，返回手势会直接退出阅读
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (toolbarShown) {
                        setToolbarVisible(false)
                        return
                    }
                    // 交回系统处理：临时关掉自己，再走一次分发
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
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
        val tone = ReaderPrefs.Canvas.of(prefs.canvas)
        val light = tone == ReaderPrefs.Canvas.LIGHT ||
            (tone == ReaderPrefs.Canvas.SYSTEM && !isNightMode())
        val canvas = when {
            !light -> if (tone == ReaderPrefs.Canvas.BLACK) Color.BLACK else 0xFF2A2E33.toInt()
            else -> 0xFFF2EDE3.toInt()
        }
        // 裁边阈值、双页、拆页变了，缓存里的页已经不是同一个样子。
        // 只是从设置页回来、参数没动的话保留缓存，免得回来第一页又要重解
        val key = "${prefs.trimBorder}|${prefs.trimLevel}|${prefs.dualPage}|${prefs.splitWide}|" +
            "${prefs.fitWidth}|${prefs.canvas}|${prefs.nativeDecode}|${prefs.argb8888}"
        if (key != displayKey) {
            if (displayKey != null) pageCache.clear()
            displayKey = key
        }
        binding.page.canvasColor = canvas
        binding.page.fitWidth = prefs.fitWidth
        applyEndTheme(canvas)
        binding.page.marginPx = dp(prefs.sideMarginDp)
        binding.pageLabel.isVisible = prefs.showPageLabel && !toolbarShown && !atEnd
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 书的尾跟着画布底色走：浅底石墨字，深底转暖白，不在深底上留一块浅色方块 */
    private fun applyEndTheme(canvas: Int) {
        val light = canvas == 0xFFF2EDE3.toInt()
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
                sourceClosed = false
                total = opened.pageCount
                if (total == 0) {
                    showError(getString(R.string.reader_empty))
                    return@launch
                }

                bookTitle = book.title
                bookStamp = book.byteSize
                // 历史是「进过这本书」，不是「翻了几页」：打开成功就记一笔
                withContext(Dispatchers.IO) {
                    runCatching {
                        db.historyDao().insert(
                            com.nascomic.reader.library.data.HistoryEntity(
                                bookId = bookId,
                                openedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                binding.bookTitle.text = book.title
                chaptered = book.isSingleVolume == 0 && opened.chapters.isNotEmpty()
                // 目录只对章回体有意义
                binding.toc.isVisible = chaptered

                // 页数只在真正打开时才知道，这里补写回库。
                // 单行本没有章的概念，章数记 0，免得界面把它显示成章回体
                val chapterCount = if (chaptered) opened.chapters.size else 0
                db.bookDao().setTotals(bookId, total, chapterCount)

                val saved = db.progressDao().forBook(bookId)
                val restart = intent.getBooleanExtra(EXTRA_RESTART, false)
                finishedOnce = if (restart) false else (saved?.finished ?: false)
                page = if (requestedPage >= 0) {
                    requestedPage.coerceIn(0, total - 1)
                } else {
                    saved?.pageGlobal?.coerceIn(0, total - 1) ?: 0
                }
                if (restart) saveProgress()
                showPage()
                saveProgress()
                binding.page.postDelayed({ showZoneHint() }, 350)
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

        val viewW = binding.page.width.coerceAtLeast(1)
        val viewH = binding.page.height.coerceAtLeast(1)
        // 双页时每页只占半宽，解码目标也跟着减半，省一半内存
        val perPageW = if (prefs.dualPage) (viewW / 2).coerceAtLeast(1) else viewW
        // 原尺寸解码：不按屏幕降采样，放大后才不糊；代价是内存，由用户自己选
        val targetW = if (prefs.nativeDecode) NATIVE_TARGET else perPageW
        // 按宽度铺满时长图上下会超出屏幕，解码高度留一倍，放大后仍有细节
        val targetH = if (prefs.nativeDecode) {
            NATIVE_TARGET
        } else if (prefs.fitWidth) {
            viewH * 2
        } else {
            viewH
        }
        // 转屏或换双页后目标尺寸变了：旧尺寸解出来的图会发虚，整份丢掉
        pageCache.ensureGeometry(targetW, targetH)

        // 竖向连贯：一次摆好一窗页，靠滚动看，不走「一屏一页」那套
        if (prefs.vertical) {
            showVerticalWindow(src, targetW, targetH)
            return
        }
        // 从竖向切回横向时要把布局方式换回来，否则还在按竖排堆页
        binding.page.vertical = false

        // 快路径：这一屏的页都已经在内存里，直接画。
        // 翻页最招人烦的就是缓存明明有、还要先亮一下加载圈再出图。
        spreadFromCache()?.let { spread ->
            page = spread.index
            updateLabels(src)
            binding.error.isVisible = false
            shownPages = spread.pages.size
            currentWide = spread.pages.first().wide
            if (page + shownPages >= total) finishedOnce = true
            binding.page.setPages(spread.pages)
            if (prefs.turnAnimation && drawnOnce) {
                binding.page.alpha = 0.35f
                binding.page.animate().alpha(1f).setDuration(140).start()
            }
            drawnOnce = true
            binding.loading.isVisible = false
            saveProgress()
            startPrefetch(src, targetW, targetH)
            return
        }

        loading = true
        binding.loading.isVisible = true

        lifecycleScope.launch {
            // 跳过空白页会改页码，所以整段在 IO 线程里定下来再回来更新界面
            val spread = withContext(Dispatchers.IO) { resolveSpread(src, targetW, targetH) }
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
            startPrefetch(src, targetW, targetH)
            // 这一屏还在解的时候用户又翻了：现在接着画最新那一页
            if (pendingShow) {
                pendingShow = false
                showPage()
            }
        }
    }

    /** 一屏的内容，以及它实际对应哪一页（跳过空白页后可能不等于进来时的页码） */
    private class Spread(val pages: List<ReaderPage>, val index: Int)

    /**
     * 只用内存缓存拼这一屏。任何一页没命中就返回 null，交给正常的解码路径。
     * 跳过空白页要真看像素，不走这条路。
     */
    private fun spreadFromCache(): Spread? {
        if (prefs.skipBlank) return null
        val first = pageCache[page] ?: return null
        val pages = ArrayList<ReaderPage>(2)
        pages += pageOf(first)
        if (prefs.dualPage && !first.wide && page + 1 < total) {
            val nextChapter = chaptered && source?.chapterOf(page + 1) != source?.chapterOf(page)
            val second = if (nextChapter) null else pageCache[page + 1]
            if (second != null && !second.wide) pages += pageOf(second)
        }
        return Spread(pages, page)
    }

    /**
     * 竖向连贯：以当前页为中心摆一窗，滚到窗口两头再由 [handleVerticalScroll] 接上一张。
     * 双页并排与跨页拆分在竖排里没有意义，这里一律按单页铺满宽度。
     */
    private fun showVerticalWindow(src: BookSource, targetW: Int, targetH: Int) {
        loading = true
        binding.loading.isVisible = true
        lifecycleScope.launch {
            val from = (page - VERTICAL_BACK).coerceAtLeast(0)
            val to = (page + VERTICAL_AHEAD).coerceAtMost(total - 1)
            val pages = withContext(Dispatchers.IO) {
                (from..to).mapNotNull { buildPage(src, it, targetW, targetH) }
            }
            if (pages.isEmpty()) {
                showError(getString(R.string.reader_page_failed, page + 1))
                loading = false
                return@launch
            }
            verticalFirst = from
            half = 0
            binding.page.vertical = true
            binding.page.setPages(pages)
            binding.page.scrollToIndex((page - from).coerceIn(0, pages.size - 1))
            updateLabels(src)
            binding.error.isVisible = false
            shownPages = 1
            currentWide = false
            binding.loading.isVisible = false
            loading = false
            saveProgress()
            startPrefetch(src, targetW, targetH)
            if (pendingShow) {
                pendingShow = false
                showPage()
            }
        }
    }

    /** 竖向滚动：先滚画面，到头再按需接页；一直接不动就是整本读完了 */
    private fun handleVerticalScroll(dy: Float) {
        if (binding.page.scrollBy(dy)) {
            syncVerticalPage()
            return
        }
        // dy > 0 是手指往上滑，看的是后面的内容
        if (dy > 0f) {
            val next = verticalFirst + binding.page.pageCount
            if (next >= total) {
                showEndPage()
                return
            }
            attachVerticalPage(next, prepend = false)
        } else {
            if (verticalFirst <= 0) return
            attachVerticalPage(verticalFirst - 1, prepend = true)
        }
    }

    /**
     * 接一页到窗口上：缓存里有就立刻接，没有就后台解完再接。
     * 竖排滚到头时不能因为「这一页还没解好」就卡在边界上不动。
     */
    private fun attachVerticalPage(index: Int, prepend: Boolean) {
        val cached = pageCache[index]
        if (cached != null) {
            attachNow(index, cached, prepend)
            return
        }
        val src = source ?: return
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                runCatching { loadPage(src, index, prefetchTargetW, prefetchTargetH) }.getOrNull()
            } ?: return@launch
            pageCache.put(index, entry)
            attachNow(index, entry, prepend)
        }
    }

    /** 真正插进窗口。期间页码又变了就不接，免得接错位置 */
    private fun attachNow(index: Int, entry: PageCache.Entry, prepend: Boolean) {
        if (prepend) {
            if (index != verticalFirst - 1) return
            binding.page.prependPage(pageOf(entry))
            verticalFirst = index
        } else {
            if (index != verticalFirst + binding.page.pageCount) return
            binding.page.appendPage(pageOf(entry))
        }
    }

    /** 滚动后把页码、进度、预载都跟着挪 */
    private fun syncVerticalPage() {
        val global = verticalFirst + binding.page.currentIndex
        if (global == page) {
            trimVerticalWindow()
            return
        }
        page = global
        val src = source ?: return
        updateLabels(src)
        startPrefetch(src, prefetchTargetW, prefetchTargetH)
        saveProgressThrottled()
        trimVerticalWindow()
    }

    /**
     * 竖排窗口按内存收，而不是只按页数收。
     *
     * 页数封顶（12）是按「一页 2 MB 左右」估的，条漫单页能到 20 MB，十几页就是几百兆，
     * 直接把堆顶穿；反过来，小图只有一兆出头，还按 12 页收就白白多解几张。
     * 所以先看字节：超了就从身后最远处丢，丢到预算内为止；身后没得丢才丢末尾。
     * 单页就比半个预算还大时，允许窗口只剩一页——宁可不预读，也不能闪退。
     */
    private fun trimVerticalWindow() {
        val budget = prefs.cacheBudgetBytes()
        val biggest = binding.page.maxPageBytes()
        val minPages = if (biggest * 2 > budget) 1 else VERTICAL_MIN_PAGES

        var guard = 0
        while (binding.page.pageCount > minPages && guard++ < VERTICAL_MAX_PAGES * 2) {
            val size = binding.page.pageCount
            val overPages = size > VERTICAL_MAX_PAGES
            val overBytes = binding.page.bitmapBytes() > budget
            if (!overPages && !overBytes) return

            val current = binding.page.currentIndex
            // 身后留几页供往回翻，多出来的先丢
            val behind = (current - VERTICAL_BACK).coerceAtLeast(0)
            val need = if (overBytes) {
                (size - minPages).coerceAtLeast(1)
            } else {
                (size - VERTICAL_MAX_PAGES).coerceAtLeast(1)
            }
            val drop = minOf(need, behind, size - minPages)
            if (drop > 0) {
                binding.page.dropFirst(drop)
                verticalFirst += drop
                continue
            }
            // 身后没得丢：丢最远的那张。当前页是最后一页时不丢，免得把正看的页丢掉
            if (size > minPages && current < size - 1) {
                binding.page.dropLast(1)
                continue
            }
            return
        }
    }

    /** 滚动时进度写库要节流：一秒钟十几次写盘没必要 */
    private fun saveProgressThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < 800L) return
        lastSaveAt = now
        saveProgress()
    }

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
     *
     * 另外不跨章组屏：一章最后一页不和下一章第一页拼在一屏，
     * 否则「章」的边界在阅读里就看不出来了。
     */
    private fun loadSpread(src: BookSource, index: Int, targetW: Int, targetH: Int): List<ReaderPage> {
        val first = buildPage(src, index, targetW, targetH) ?: return emptyList()
        if (first.wide || !prefs.dualPage) return listOf(first)
        if (index + 1 >= total) return listOf(first)
        if (chaptered && src.chapterOf(index + 1) != src.chapterOf(index)) return listOf(first)

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
        val quality = cacheQuality()
        val key = PageCacheKey.of(
            index = index,
            width = targetW,
            height = targetH,
            trim = prefs.trimBorder,
            level = prefs.trimLevel,
            quality = quality,
            stamp = bookStamp,
        )
        if (cacheToDisk) {
            diskCache.load(bookId, key)?.let { bitmap ->
                return PageCache.Entry(bitmap, boundsOf(bitmap), bitmap.width > bitmap.height * 1.2f)
            }
        }
        val decoded = decodePage(src, index, targetW, targetH) ?: return null
        if (cacheToDisk) diskCache.save(bookId, key, decoded.bitmap, quality)
        return decoded
    }

    /**
     * 落盘时用多少质量。
     *
     * 按屏幕解码的那档存 88 就够——反正已经缩到屏宽了，再高的比特只是存了看不见的细节。
     * 开了「原图显示」就得存 96：这一档的意义就是放大看，二次压缩的块效应会被一眼看出来。
     */
    private fun cacheQuality(): Int =
        if (prefs.nativeDecode) DiskPageCache.QUALITY_ORIGINAL else DiskPageCache.QUALITY

    /** 解码位深：32 位颜色更细腻，16 位省一半内存 */
    private fun decodeConfig(): Bitmap.Config =
        if (prefs.argb8888) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

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
        if (sourceClosed) return null
        val bitmap = runCatching {
            // 与 closeSource 互斥：关源时若有人正在读，native 侧会踩空
            sourceLock.withLock {
                if (sourceClosed) {
                    null
                } else if (src is RenderedPageSource) {
                    src.renderPage(index, targetW, targetH)
                } else {
                    PageBitmapLoader.load(src.openPage(index), targetW, targetH, decodeConfig())
                }
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
     * 预取接下来的几页。翻页时等的是「连远端 + 解码」，先把这几页解好放进缓存，翻过去就直接出图。
     *
     * 翻页只往队列里放页号，解码由常驻的两个 worker 慢慢做——原来每翻一页就把预载任务掐掉重开，
     * 手快一点就永远追不上，等于没预载。
     */
    private fun startPrefetch(src: BookSource, targetW: Int, targetH: Int) {
        prefetchTargetW = targetW
        prefetchTargetH = targetH
        // 保留窗口前后都留够，往回翻也不用重解；竖排的窗口页不能按当前页裁，否则会把正看着的页丢掉
        if (!prefs.vertical) {
            pageCache.keepOnly(PageWindow.keep(page, prefs.prefetchPrev, prefs.prefetchNext, total))
        }
        ensurePrefetchWorkers()
        for (index in PageWindow.prefetchAround(page, prefs.prefetchPrev, prefs.prefetchNext, total)) {
            enqueuePrefetch(index)
        }
    }

    private fun enqueuePrefetch(index: Int) {
        if (index < 0 || index >= total) return
        if (pageCache.get(index) != null) return
        synchronized(prefetchQueued) {
            if (!prefetchQueued.add(index)) return
        }
        prefetchQueue.trySend(index)
    }

    private fun ensurePrefetchWorkers() {
        if (prefetchWorkersStarted) return
        prefetchWorkersStarted = true
        repeat(PREFETCH_WORKERS) {
            lifecycleScope.launch(Dispatchers.IO) {
                for (index in prefetchQueue) {
                    try {
                        val book = source
                        val w = prefetchTargetW
                        val h = prefetchTargetH
                        if (book == null || w <= 0 || h <= 0) continue
                        if (pageCache.get(index) == null) {
                            runCatching { loadPage(book, index, w, h) }
                                .getOrNull()?.let { pageCache.put(index, it) }
                        }
                    } finally {
                        synchronized(prefetchQueued) { prefetchQueued.remove(index) }
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.isVisible = true
        binding.pageLabel.text = ""
        binding.loading.isVisible = false
        if (toolbarShown) setToolbarVisible(false)
    }

    /**
     * 手势入口。单击交给 [handleTap]（翻页或呼工具栏），双击与双指做缩放，
     * 放大后拖动平移。双击缩放开着时单击要等双击判定，翻页会慢一拍——设置页里写明了。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** 音量键 / 自定义键翻页。关掉就原样交给系统，让音量条正常出现 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (prefs.keyFlip && source != null && !atEnd && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                prefs.keyPrevCode -> {
                    turnBackward()
                    return true
                }
                prefs.keyNextCode -> {
                    turnForward()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 鼠标滚轮 / 触控板：按设置映射成上一页或下一页 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (prefs.wheelMode != 0 && source != null && !atEnd &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                // 轴上滚为负；模式 1 向上=上一页，模式 2 向上=下一页
                val up = v < 0f
                val forward = if (prefs.wheelMode == 1) !up else up
                if (forward) turnForward() else turnBackward()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            // 双击缩放开着才等判定；关着就走 onSingleTapUp，翻页没有延迟
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!zoomEnabled()) return false
                handleTap(e.x, e.y)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (zoomEnabled()) return false
                handleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!zoomEnabled() || source == null || atEnd) return false
                binding.page.doubleTapZoom(
                    factor = prefs.doubleZoomFactor,
                    tapX = e.x,
                    tapY = e.y,
                    focusH = prefs.doubleZoomFocusH,
                    focusV = prefs.doubleZoomFocusV,
                )
                return true
            }

            /**
             * 横向滑动翻页：左滑看下一页（方向可切）。
             * 只在没放大时接管——放大了左右滑是挪画面，不是翻页。
             */
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (source == null) return false
                // 竖向连贯：松手后按惯性接着滑。条漫一屏一屏擦太累，手指一甩应该滑一段
                if (prefs.vertical) {
                    if (kotlin.math.abs(velocityY) < FLING_MIN_VELOCITY) return false
                    flingVertical(velocityY)
                    return true
                }
                if (binding.page.isZoomed) return false
                if (kotlin.math.abs(velocityX) < FLING_MIN_VELOCITY) return false
                // 明显斜着划的不算翻页，免得上下拖动误触
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.2f) return false
                val forward = if (prefs.forwardRight) velocityX < 0 else velocityX > 0
                if (forward) turnForward() else turnBackward()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!prefs.holdZoom || source == null || atEnd) return
                binding.page.longPressZoom(
                    factor = prefs.holdZoomFactor,
                    tapX = e.x,
                    tapY = e.y,
                    focusH = prefs.holdZoomFocus,
                    focusV = prefs.holdZoomFocus,
                )
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // 双指缩放时两指的位置也在变，GestureDetector 照样报滑动。
                // 不拦的话画面会被「缩放」和「平移」同时推，捏合时看着一跳一跳
                if (scaleDetector.isInProgress) return false
                // 竖向连贯：上下滑就是滚动，不需要再判断翻页区
                if (prefs.vertical) {
                    handleVerticalScroll(distanceY)
                    return true
                }
                // 长按放大态下拖动更快：手指挪一点就能扫很远
                val factor = if (prefs.holdZoom && binding.page.isZoomed) {
                    prefs.holdZoomScrollFactor.toFloat()
                } else {
                    1f
                }
                return binding.page.panBy(-distanceX * factor, -distanceY * factor)
            }
        })
    }

    /** 竖排惯性：正在跑的动画，松手再甩一次要先掐掉旧的 */
    private var flingAnimator: android.animation.ValueAnimator? = null

    /**
     * 竖排的惯性滑动。速度按帧衰减，每帧的位移交给 [handleVerticalScroll]，
     * 这样滚到窗口边界照样会接下一页，和手滑没有区别。
     */
    private fun flingVertical(velocityY: Float) {
        flingAnimator?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(velocityY, 0f).apply {
            duration = VERTICAL_FLING_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                // 上滑时 velocityY 为负，内容要往前走，所以取反
                handleVerticalScroll(-(a.animatedValue as Float) * FRAME_SECONDS)
            }
        }
        flingAnimator = anim
        anim.start()
    }

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 竖向连贯里双指缩放会跟上下滚动抢手势，这里直接不接
                if (!prefs.pinchZoom || prefs.vertical || source == null || atEnd) return false
                binding.page.pinchScale(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    }

    /** 双击缩放只在横向阅读时生效；竖向滚动里双击留给工具栏 */
    private fun zoomEnabled(): Boolean = prefs.doubleZoom && !prefs.vertical

    private fun handleTap(x: Float, y: Float) {
        if (source == null) return
        if (overToolbarAt(x, y) || overEndButtonAt(x, y)) return
        // 竖向连贯里点击只负责呼出工具栏，位置交给滚动
        // 关掉点击翻页之后，点哪儿也都只呼出工具栏——留给只想看不想误翻的人
        if (prefs.vertical || !prefs.tapToTurn) {
            setToolbarVisible(!toolbarShown)
            return
        }
        // 方向可切：从左往右读时点右侧翻到下一页，从右往左读时反过来
        val ratio = x / binding.readerRoot.width.coerceAtLeast(1)
        val forwardRight = prefs.forwardRight
        when {
            ratio < ZONE_LEFT -> if (forwardRight) turnBackward() else turnForward()
            ratio > ZONE_RIGHT -> if (forwardRight) turnForward() else turnBackward()
            else -> setToolbarVisible(!toolbarShown)
        }
    }

    /** 点在工具栏上时不翻页也不收起工具栏，把事件留给按钮 */
    private fun overToolbarAt(x: Float, y: Float): Boolean {
        if (!toolbarShown) return false
        val inTop = binding.topBar.isVisible && y <= binding.topBar.bottom
        val inBottom = binding.bottomBar.isVisible && y >= binding.bottomBar.top
        return inTop || inBottom
    }

    /** 书的尾上只有「回到书库」一个按钮，点它别顺带翻页 */
    private fun overEndButtonAt(x: Float, y: Float): Boolean {
        if (!atEnd) return false
        val hit = Rect()
        binding.endBack.getHitRect(hit)
        return hit.contains(x.toInt(), y.toInt())
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

    /** 进阅读时闪一下分区边界：左右可点、中间呼工具栏，约 2.5 秒淡出 */
    private fun showZoneHint() {
        // 竖向连贯没有左右分区，提示只对横向有意义
        if (prefs.vertical || !prefs.zoneHint || zoneHintShown) return
        zoneHintShown = true
        val prev = getString(R.string.reader_prev_page)
        val next = getString(R.string.reader_next_page)
        val hint = binding.zoneHint
        hint.leftLabel = if (prefs.forwardRight) prev else next
        hint.rightLabel = if (prefs.forwardRight) next else prev
        hint.middleLabel = getString(R.string.reader_zone_menu)
        hint.isVisible = true
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(160).start()
        hint.postDelayed({
            hint.animate().alpha(0f).setDuration(260)
                .withEndAction { hint.isVisible = false }
                .start()
        }, 2200)
    }

    /** 章节目录。当前章标出来，点任一章跳过去 */
    /**
     * 目录抽屉：列章与每章页数，点章跳过去。
     *
     * 右上角一枚正序/倒序：章多的书从第 1 话翻到第 300 话很累，
     * 倒过来以后「最新一话」就在顶上。它与详情页目录共用同一个偏好。
     */
    private fun showTocSheet() {
        val src = source ?: return
        if (!chaptered) return

        val sheet = BottomSheetDialog(this)
        val content = LayoutInflater.from(this)
            .inflate(R.layout.sheet_reader_toc, binding.readerRoot, false)
        val list = content.findViewById<LinearLayout>(R.id.toc_list)
        val scroll = content.findViewById<View>(R.id.toc_scroll)
        val order = content.findViewById<TextView>(R.id.toc_order)

        var descending = appPrefs.tocDescending
        val current = src.chapterOf(page)
        val padH = dp(10)
        val padV = dp(13)

        fun render() {
            list.removeAllViews()
            // 按钮写的是「现在是什么序」，不是「点一下会变成什么」——后者读起来正好相反
            order.text = getString(
                if (descending) R.string.detail_order_desc else R.string.detail_order_asc
            )
            val items = if (descending) src.chapters.reversed() else src.chapters
            var currentRow: View? = null

            for (info in items) {
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

            // 章多的时候把当前章滚到可见位置
            currentRow?.let { row ->
                scroll.post { scroll.scrollTo(0, (row.top - scroll.height / 3).coerceAtLeast(0)) }
            }
        }

        order.setOnClickListener {
            descending = !descending
            appPrefs.tocDescending = descending
            render()
        }

        sheet.setContentView(content)
        sheet.show()
        render()
    }

    /** 前进一屏：并排时一次跨两页；拆页时先看右半再翻页；长图先滚再看下一页 */
    private fun turnForward() {
        if (atEnd) return
        // 竖向连贯没有「一屏一页」，按键与滚轮就是往下挪一页
        if (prefs.vertical) {
            if (page + 1 >= total) {
                showEndPage()
                return
            }
            page += 1
            showPage()
            return
        }
        if (splitActive() && half == 0) {
            half = 1
            showPage()
            return
        }
        // 页面比屏幕高：先按跳进距离往下滚，滚到底才翻页
        if (binding.page.scrollWithin(jumpDistancePx())) return
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
        if (prefs.vertical) {
            if (page <= 0) return
            page -= 1
            showPage()
            return
        }
        if (splitActive() && half == 1) {
            half = 0
            showPage()
            return
        }
        // 页内已经往下滚过：先往回滚，到顶才翻页
        if (binding.page.scrollWithin(-jumpDistancePx())) return
        // 后退落到宽图时先看右半，符合「从后往前」的直觉
        turnTo(page - shownPages.coerceAtLeast(1), newHalf = 1)
    }

    /** 一次翻/滚多远：0 与 2 对长图都是一屏，半屏档留给想细看的人 */
    private fun jumpDistancePx(): Float {
        val h = binding.page.height.toFloat().coerceAtLeast(1f)
        return if (prefs.flipStep == 1) h / 2f else h
    }

    private fun splitActive(): Boolean = currentWide && prefs.splitWide

    private fun turnTo(target: Int, newHalf: Int = 0) {
        if (target < 0 || target >= total) return
        half = newHalf
        page = target
        // 上一屏还在解：记下这次翻页，等它画完立刻接着画。
        // 原来这里是直接 return，手一快就有几次点击被吞掉，看着像卡住。
        if (loading) {
            pendingShow = true
            return
        }
        showPage()
        saveProgress()
    }

    /**
     * 整本书看完：清掉当前页位图，换成一块空态。
     * 进度仍停在最后一页，回来时接着从那里看。
     *
     * 「读到过书的尾」就在这里落定。横向看图时最后一屏的首页不等于最后一页，
     * 竖排更是没有换页这一步，所以判定只能放在「翻过尾」这个动作上，
     * 不能放在「当前页是最后一页」上。
     */
    private fun showEndPage() {
        if (atEnd || source == null) return
        atEnd = true
        half = 0
        finishedOnce = true
        saveProgress()
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
        flingAnimator?.cancel()
        flingAnimator = null
        closeSource()
        binding.page.setPages(emptyList())
        PageCacheHolder.release(pageCache)
    }

    /**
     * 关掉书源。它要和解码互斥：预载 worker 可能正在 `openPage` 里没返回，
     * 这时把 libarchive / PdfRenderer 关掉就是 native 层踩空（随机崩，栈还指不到 Java）。
     *
     * 关的动作放到另一条线程上等锁，拿到再关：既不在主线程上等 IO，
     * 也不会打断正在读的那一页。等不到（远端卡住）就放弃，让进程回收时一起收掉。
     */
    private fun closeSource() {
        sourceClosed = true
        val src = source ?: return
        source = null
        Thread({
            val lock = sourceLock
            if (runCatching { lock.tryLock(CLOSE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
            ) {
                try {
                    runCatching { src.close() }
                } finally {
                    lock.unlock()
                }
            }
        }, "reader-close").start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
