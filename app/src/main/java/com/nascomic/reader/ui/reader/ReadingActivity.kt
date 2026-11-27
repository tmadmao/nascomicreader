package com.nascomic.reader.ui.reader

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityReaderBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.RenderedPageSource
import com.nascomic.reader.format.pagePosition
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.PageEditEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.ReaderPrefs
import com.nascomic.reader.settings.applyOrientation
import com.nascomic.reader.ui.reader.PageEdits.Companion.isEmpty
import com.nascomic.reader.ui.widget.AppDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
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

        /** 低清预览按目标尺寸的几分之一解：二分之后面积是四分之一，够看出画面是什么 */
        private const val PREVIEW_DIVISOR = 2

            /** 竖向窗口：当前页前留一页、后留四页，滚到头再按需接下一张 */
        private const val VERTICAL_BACK = 1
        private const val VERTICAL_AHEAD = 4

        /** 竖排窗口的收窗阈值（按段）：超过时优先裁视口外的整页 */
        private const val VERTICAL_MAX_PAGES = 12

        /** 竖排窗口至少留几段：太少了接页会一顿一顿 */
        private const val VERTICAL_MIN_PAGES = 3

        /** 横向滑动翻页的最低速度（px/s）：太慢的滑动当作没滑 */
        private const val FLING_MIN_VELOCITY = 600f

        /** 关源时最多等当前那一页解多久；等不到就放着，进程回收时一起收 */
        private const val CLOSE_WAIT_MS = 2000L

        /** 竖排惯性滑动的时长，以及一帧按多少秒折算位移 */
        private const val VERTICAL_FLING_MS = 520L
        private const val FRAME_SECONDS = 0.016f

        /** 横置页判定用的小图长边：判定只看字行走向，不需要细节 */
        private const val JUDGE_SIDE = 320

        /** 横置页提示停留多久：够看清，又不长期压着画面 */
        private const val SIDEWAYS_HINT_MS = 6000L

        /** 暖色薄膜的淡入淡出时长：短到不挡视线，长到看得出是「开了」 */
        private const val WARM_TINT_MS = 160L

        /** 自动下滑的计时周期：animator 每 1 秒循环一轮，帧间差值折算位移 */
        private const val AUTO_TICK_MS = 1000L
    }

    private lateinit var binding: ActivityReaderBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val chapterWriter by lazy { com.nascomic.reader.library.scan.ChapterWriter(db) }
    private val opener by lazy { BookOpener(this) }

    /** 按书取参数：这本书单独调过的项优先，其余回落到全局默认 */
    private lateinit var prefs: ReaderPrefs
    private val appPrefs by lazy { AppPrefs(this) }

    /**
     * 只读全局的那一份。护眼暖色是一层整屏薄膜，不是一本书的版面，
     * 换本书还开着才符合直觉，所以读写都走全局。
     */
    private val globalPrefs by lazy { ReaderPrefs(this) }

    private var source: BookSource? = null

    /** 解码与关源互斥用的锁；关源后 [sourceClosed] 置真，后续解码直接放弃 */
    private val sourceLock = java.util.concurrent.locks.ReentrantLock()

    @Volatile
    private var sourceClosed = false
    private var bookId: Long = 0
    private var requestedPage: Int = -1
    private var page = 0
    private var exactJumpPage = -1
    private var total = 0

    /** 是否有章的概念。单行本没有，页码按全书算 */
    private var chaptered = false
    private var opening = true
    private var loading = false
    private var toolbarShown = false
    /** 当前这一屏画了几页：双页开启时可能是 2，翻页步进跟着变 */
    private var shownPages = 1
    /** 跨页拆分：0 看左半，1 看右半。只在宽图上生效 */
    private var half = 0
    /** 当前屏是不是拆页的一半 */
    private var currentSplit = false

    /**
     * 横置页：一页横着扫进来，内容要转 90° 才是正的。
     * 提示过的页和尚未确认的角度只活在本次阅读里。
     */
    private val hintedPages = HashSet<Int>()
    private val rotatedDegrees = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** 这一页已经转过几次，用来兜住「转满一圈还在转」的误判 */
    private var keepHintOnShow = false
    @Volatile private var pageEdits = PageEdits.Empty
    @Volatile private var checkedEditPages = emptySet<Int>()
    private val verifiedProofs = HashMap<Int, String>() // 只在 IO 上顺序读写
    private var editing = false
    private var bookAtOpen: BookEntity? = null

    /** 这一条手势是按在横置提示卡上起的，整条都不当翻页手势处理 */
    private var touchOnSidewaysHint = false
    private var sidewaysHintPage = -1

    /** 转过的那一页只留一份，翻走再翻回来不用重转 */
    private var rotatedIndex = -1
    private var rotatedAngle = 0
    private var rotatedEntry: PageCache.Entry? = null
    /** 旋转结果是哪张源图转出来的：预览小图与高清正片不是同一张，不能互相顶替 */
    private var rotatedSource: Bitmap? = null
    /** 旋转时用的裁边参数：同一张图上改了裁边，旧旋转结果也要作废 */
    private var rotatedTrimKey: String? = null

    private val hideSidewaysHintRunnable = Runnable { if (binding.sidewaysHint.isVisible) hideSidewaysHint() }
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

    /**
     * 正在解的那一页如果明显大于屏幕，先把一张低清交给它，让画面先出来。
     * 只对 [previewFor] 这一页生效，交过一次就清掉——预载的别的页不会把它吃掉。
     */
    @Volatile
    private var onPreviewReady: ((PageCache.Entry) -> Unit)? = null

    /** 预览回调认的这一页；-1 表示当前没在预览 */
    @Volatile
    private var previewFor = -1

    /**
     * 请求代次：每次 showPage 自增。回到主线程画预览前要核对它，
     * 否则用户连续翻页时，上一页的低清可能在几帧后盖到新页上。
     */
    @Volatile
    private var previewGeneration = 0

    /** 分区提示每次进阅读只闪一次 */
    private var zoneHintShown = false

    /** 竖向窗口的槽位 ↔ 全局页号映射：宽图拆上下时一页占两段，页码与接页都经这张表换算 */
    private val verticalSlots = PageSlots()

    /** 正在后台解码待接的页：自动下滑每一帧都会问边界页，同一页不能反复发起解码 */
    private val attachingPages = HashSet<Int>()

    /** 后台返回重建竖向窗口时落回页内原处，而不是该页开头（拆页下半段不停回上半段） */
    private var keepVerticalOffset = false
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
        binding.editPage.setOnClickListener { choosePageEdit() }
        binding.toc.setOnClickListener { showTocSheet() }
        binding.bookmark.setOnClickListener { toggleBookmark() }
        binding.warm.setOnClickListener { toggleWarmTint() }
        binding.auto.setOnClickListener { toggleAutoScroll() }
        binding.autoSlower.setOnClickListener { adjustAutoSpeed(-1) }
        binding.autoFaster.setOnClickListener { adjustAutoSpeed(1) }
        binding.autoStop.setOnClickListener { stopAutoScroll() }
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
        binding.sidewaysRotate.setOnClickListener { rotateCurrentPage() }
        binding.sidewaysSkip.setOnClickListener {
            val angle = rotatedDegrees[page]
            if (page == sidewaysHintPage && angle != null) savePageEdit(page, PageEdits.AUTO, angle, PageEdits.AUTO)
            hideSidewaysHint()
        }
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
            binding.sidewaysHint.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(24) + bars.bottom
            }
            binding.autoSpeedBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(24) + bars.bottom
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
        val key = "${prefs.trimBorder}|${prefs.trimLevel}|${prefs.dualPage}|${prefs.splitMode}|" +
            "${prefs.trimIgnoreSides}|${prefs.trimIgnorePx}|${prefs.trimProtect}|${prefs.trimAlgo}|" +
            "${prefs.spreadOrder}|${prefs.spreadDirection}|" +
            "${prefs.fitWidth}|${prefs.canvas}|${prefs.nativeDecode}|${prefs.argb8888}|${prefs.vertical}|${prefs.detectSideways}"
        if (key != displayKey) {
            if (displayKey != null) {
                pageCache.clear()
                clearRotatedCache()
            }
            displayKey = key
        }
        binding.page.canvasColor = canvas
        binding.page.fitWidth = prefs.fitWidth
        applyEndTheme(canvas)
        binding.page.marginPx = dp(prefs.sideMarginDp)
        // 自动下滑只属于竖向连贯；切走方向就停，入口也只在竖向时露出
        binding.auto.isVisible = prefs.vertical
        if (!prefs.vertical) stopAutoScroll()
        syncAutoButton()
        applyWarmTint()
        binding.progressScrubber.onScrub = { target -> scrubTo(target) }
        binding.progressScrubber.onScrubEnd = { saveProgress() }
        binding.pageLabel.isVisible = prefs.showPageLabel && !toolbarShown && !atEnd
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 护眼暖色：一层暖橙薄膜盖在画面上，不碰系统亮度。
     * 回到阅读页时按当前值直接落位，不做过渡——那一下淡入没必要。
     */
    private fun applyWarmTint() {
        val on = globalPrefs.warmTint
        binding.warmIcon.setImageResource(if (on) R.drawable.ic_warm_on else R.drawable.ic_warm)
        binding.warmTint.animate().cancel()
        binding.warmTint.alpha = if (on) 1f else 0f
        binding.warmTint.isVisible = on
    }

    /** 在阅读页菜单里直接开关暖色：改的是全局值，下一本书照样生效 */
    private fun toggleWarmTint() {
        val on = !globalPrefs.warmTint
        globalPrefs.warmTint = on
        binding.warmIcon.setImageResource(if (on) R.drawable.ic_warm_on else R.drawable.ic_warm)
        val tint = binding.warmTint
        tint.animate().cancel()
        if (on) {
            tint.isVisible = true
            tint.alpha = 0f
            tint.animate().alpha(1f).setDuration(WARM_TINT_MS).start()
        } else {
            tint.animate().alpha(0f).setDuration(WARM_TINT_MS).withEndAction {
                tint.isVisible = false
                tint.alpha = 0f
            }.start()
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

    /** 内容版本：页缓存靠它失效；口径见 [ContentStamp] */
    private fun contentStamp(book: BookEntity, src: BookSource): Long = ContentStamp.of(
        byteSize = book.byteSize,
        chapters = src.chapters.map { it.title to it.pageCount },
        totalPages = src.pageCount,
        // 目录书换图后字节数仍为 0，靠重扫时 +1 的版本号让旧页失效
        contentVersion = book.contentVersion,
    )

    private fun openBook() {
        lifecycleScope.launch {
            binding.loading.isVisible = true
            try {
                val book = db.bookDao().get(bookId) ?: error("书不存在")
                val src = db.sourceDao().get(book.sourceId) ?: error("来源不存在")
                // 章序与名称自然序对不上（远端来源可能留下乱序）就先改正，
                // 免得抽屉里的目录与前后翻页都不按话走
                val chapters = chapterWriter.ensureNaturalOrder(
                    bookId, db.chapterDao().forBook(bookId)
                )

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

                bookTitle = book.displayTitle
                bookAtOpen = book
                bookStamp = contentStamp(book, opened)
                binding.bookTitle.text = book.displayTitle
                chaptered = book.isSingleVolume == 0 && opened.chapters.isNotEmpty()
                // 目录只对章回体有意义
                binding.toc.isVisible = chaptered

                // 页数只在真正打开时才知道，这里补写回库。
                // 单行本没有章的概念，章数记 0，免得界面把它显示成章回体
                val chapterCount = if (chaptered) opened.chapters.size else 0
                val rows = db.pageEditDao().forBook(bookId)
                pageEdits = withContext(Dispatchers.IO) { verifyPageEdits(opened, rows) }
                db.bookDao().setTotals(bookId, total, chapterCount)

                val saved = db.progressDao().forBook(bookId)
                val restart = intent.getBooleanExtra(EXTRA_RESTART, false)
                finishedOnce = if (restart) false else (saved?.finished ?: false)
                page = if (requestedPage >= 0) {
                    requestedPage.coerceIn(0, total - 1)
                } else {
                    saved?.pageGlobal?.coerceIn(0, total - 1) ?: 0
                }
                opening = false
                if (restart) saveProgress()
                half = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
                binding.editPage.isEnabled = true
                showPage()
                binding.page.postDelayed({ showZoneHint() }, 350)
            } catch (e: CancellationException) {
                throw e
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
        if (opening || loading) return
        // 书的尾占着屏时不画页面，等往回翻再重解
        if (atEnd) return
        // 首帧还没量出尺寸，等布局完再画，否则解码目标会算成 1px
        if (binding.page.width == 0 || binding.page.height == 0) {
            binding.page.post { showPage() }
            return
        }
        // 换页就把上一条横置提示收掉；手动转正那一刻除外，提示要留着改方向
        if (!keepHintOnShow) hideSidewaysHint()
        keepHintOnShow = false

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
            binding.error.isVisible = false
            shownPages = spread.pages.size
            updateLabels(src)
            currentSplit = spread.pages.singleOrNull()?.split == true
            if (page + shownPages >= total) finishedOnce = true
            binding.page.setPages(spread.pages)
            if (prefs.turnAnimation && drawnOnce) {
                binding.page.alpha = 0.35f
                binding.page.animate().alpha(1f)
                    .setDuration(if (prefs.flipAnimMs > 0) prefs.flipAnimMs.toLong() else 160L)
                    .start()
            }
            drawnOnce = true
            binding.loading.isVisible = false
            saveProgress()
            startPrefetch(src, targetW, targetH)
            maybeHintSideways(spread.index)
            return
        }

        loading = true
        binding.loading.isVisible = true
        // 大图解码要等：先给一张低清让画面出来，正片解完再换上去。
        // 双页与跳过空白页要一次定两页的顺序，预览会打乱配对；原尺寸解码本来就要全细节，
        // 先给糊的一眼没有意义。这三种情况都不给。
        previewGeneration++
        val requestGeneration = previewGeneration
        val requestPage = page
        val requestHalf = half
        val exact = requestPage == exactJumpPage
        onPreviewReady = if (!prefs.nativeDecode && !prefs.dualPage && !prefs.skipBlank &&
            pageEdits.pairedWith(requestPage) == null
        ) {
            { preview ->
                runOnUiThread {
                    // 代次、页号、半页三者都对上才画：任何一项变了说明用户已经翻走或换了半页。
                    // 还要排除正在销毁的旧实例——转屏时旧 Activity 的 isFinishing 仍是 false，
                    // 只靠它挡不住回调继续碰已经不要的视图
                    if (loading && !atEnd && !isFinishing && !isDestroyed &&
                        requestGeneration == previewGeneration &&
                        page == requestPage && half == requestHalf
                    ) {
                        binding.page.setPages(listOf(pageOf(preview, page)))
                        binding.loading.isVisible = false
                    }
                }
            }
        } else {
            null
        }
        previewFor = if (onPreviewReady != null) page else -1

        lifecycleScope.launch {
            // 跳过空白页会改页码，所以整段在 IO 线程里定下来再回来更新界面
            val spread = withContext(Dispatchers.IO) {
                resolveSpread(src, requestPage, requestHalf, targetW, targetH, exact)
            }
            onPreviewReady = null
            previewFor = -1
            loading = false
            if (showPendingPage()) return@launch
            if (atEnd) return@launch
            if (spread.pages.isEmpty()) {
                showError(getString(R.string.reader_page_failed, spread.index + 1))
                return@launch
            } else {
                if (spread.index != requestPage) half = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
                page = spread.index
                binding.error.isVisible = false
                shownPages = spread.pages.size
                updateLabels(src)
                currentSplit = spread.pages.singleOrNull()?.split == true
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
            saveProgress()
            startPrefetch(src, targetW, targetH)
            maybeHintSideways(spread.index)
        }
    }

    /** 旧的一屏解完时，别让它覆盖加载期间指定的新页。 */
    private fun showPendingPage(): Boolean {
        if (!pendingShow) return false
        pendingShow = false
        showPage()
        return true
    }

    /** 一屏的内容，以及它实际对应哪一页（跳过空白页后可能不等于进来时的页码） */
    private class Spread(val pages: List<ReaderPage>, val index: Int)

    /**
     * 只用内存缓存拼这一屏。任何一页没命中就返回 null，交给正常的解码路径。
     * 跳过空白页要真看像素，不走这条路。
     */
    private fun spreadFromCache(): Spread? {
        val exact = page == exactJumpPage
        if (prefs.skipBlank && !exact) return null
        return assembleSpread(source ?: return null, page, half, exact, cacheOnly = true) { pageCache[it] }
    }

    /**
     * 竖向连贯：以当前页为中心摆一窗，滚到窗口两头再由 [handleVerticalScroll] 接上一张。
     * 双页并排在竖排里没有意义；跨页拆分在竖排里表现为上下两段接着看（见 [pagesOf]）。
     */
    private fun showVerticalWindow(src: BookSource, targetW: Int, targetH: Int) {
        // 重建前记下页内偏移：同一页重摆窗口（后台返回）要落回原处，换页 / 跳页则从页头开始
        val restoring = keepVerticalOffset && binding.page.vertical
        val restoreOffset = if (restoring) {
            val slot = verticalSlots.firstSlotOf(page)
            if (slot >= 0) binding.page.offsetFromSlot(slot) else 0f
        } else {
            0f
        }
        // 短页跨屏：屏顶/屏底可见页可能超出锚点页前1后4，窗口须接得住恢复前的整个视口
        val topPage = if (restoring) verticalSlots.pageAt(binding.page.topIndex) ?: page else page
        val bottomPage = if (restoring) verticalSlots.pageAt(binding.page.bottomIndex) ?: page else page
        keepVerticalOffset = false
        val requestPage = page
        val exact = requestPage == exactJumpPage
        loading = true
        binding.loading.isVisible = true
        lifecycleScope.launch {
            val range = PageSlots.windowRange(requestPage, topPage, bottomPage, VERTICAL_BACK, VERTICAL_AHEAD, total)
            // 跳到末尾附近的短页时，从目标开始排窗；否则视口会被钳回前一页。
            val from = if (exact && !restoring) requestPage else range.first
            val to = range.last
            val built = ArrayList<ReaderPage>()
            val ids = ArrayList<Int>()
            withContext(Dispatchers.IO) {
                for (index in from..to) {
                    val entry = buildEntry(src, index, targetW, targetH) ?: continue
                    for (part in pagesOf(entry, index)) {
                        built += part
                        ids += index
                    }
                }
            }
            loading = false
            if (showPendingPage()) return@launch
            if (atEnd) return@launch
            if (built.isEmpty() || (exact && requestPage !in ids)) {
                showError(getString(R.string.reader_page_failed, page + 1))
                return@launch
            }
            verticalSlots.rebuild(ids)
            synchronized(attachingPages) { attachingPages.clear() }
            half = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
            binding.page.vertical = true
            binding.page.setPages(built)
            val slot = verticalSlots.firstSlotOf(page).coerceAtLeast(0)
            // 有符号偏移：即使屏顶在锚点页之前，窗口也覆盖原落点。
            binding.page.scrollToSlotOffset(slot, restoreOffset)
            shownPages = 1
            updateLabels(src)
            binding.error.isVisible = false
            currentSplit = false
            binding.loading.isVisible = false
            saveProgress()
            startPrefetch(src, targetW, targetH)
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
            val next = (verticalSlots.lastPage() ?: page) + 1
            if (next >= total) {
                showEndPage()
                return
            }
            attachVerticalPage(next, prepend = false)
        } else {
            val first = verticalSlots.firstPage() ?: page
            if (first <= 0) return
            attachVerticalPage(first - 1, prepend = true)
        }
    }

    /**
     * 接一页到窗口上：缓存里有就立刻接，没有就后台解完再接。
     * 竖排滚到头时不能因为「这一页还没解好」就卡在边界上不动。
     * 同一页在解的时候重复请求直接忽略——自动下滑每一帧都会问到边界页。
     * [thenScroll] 非零时接好再滚这段距离：按键翻页在边界处接页后接着走，不吞掉这一跳。
     */
    private fun attachVerticalPage(index: Int, prepend: Boolean, thenScroll: Float = 0f) {
        val cached = pageCache[index]
        if (cached != null) {
            if (attachNow(index, cached, prepend) && thenScroll != 0f) {
                scrollVerticalBy(thenScroll)
            }
            return
        }
        val src = source ?: return
        synchronized(attachingPages) {
            if (!attachingPages.add(index)) return
        }
        lifecycleScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    runCatching { loadPage(src, index, prefetchTargetW, prefetchTargetH) }.getOrNull()
                } ?: return@launch
                pageCache.put(index, entry)
                if (attachNow(index, entry, prepend) && thenScroll != 0f) {
                    scrollVerticalBy(thenScroll)
                }
            } finally {
                synchronized(attachingPages) { attachingPages.remove(index) }
            }
        }
    }

    /** 真正插进窗口。期间页码又变了就不接，免得接错位置；一页可能占多个槽位（拆上下）。返回是否接上 */
    private fun attachNow(index: Int, entry: PageCache.Entry, prepend: Boolean): Boolean {
        if (atEnd || loading) return false
        if (prepend) {
            if (index != (verticalSlots.firstPage() ?: page) - 1) return false
            // 头部插多段时先插后读的那段，落位后的上下顺序才与阅读顺序一致
            for (part in pagesOf(entry, index).asReversed()) {
                binding.page.prependPage(part)
                verticalSlots.prepend(index, 1)
            }
        } else {
            if (index != (verticalSlots.lastPage() ?: page) + 1) return false
            for (part in pagesOf(entry, index)) {
                binding.page.appendPage(part)
                verticalSlots.append(index, 1)
            }
        }
        return true
    }

    /** 滚动后把页码、进度、预载都跟着挪 */
    private fun syncVerticalPage() {
        val global = verticalSlots.pageAt(binding.page.currentIndex) ?: page
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
     * 按页数封顶假设了每页大小固定，实际条漫单页远大于普通页，页数一多就把堆顶穿；
     * 小图则相反，按页数收会白白多解几张。
     * 超限就从身后最远处丢，身后没得丢才丢末尾；两端都可见时暂缓。
     * 单页就比半个预算还大时，允许窗口只剩一页——宁可不预读，也不能闪退。
     */
    private fun trimVerticalWindow() {
        val budget = prefs.cacheBudgetBytes()
        val biggest = binding.page.maxPageBytes()
        val minSlots = if (biggest * 2 > budget) 1 else VERTICAL_MIN_PAGES

        var guard = 0
        while (binding.page.pageCount > minSlots && guard++ < VERTICAL_MAX_PAGES * 2) {
            val size = binding.page.pageCount
            val overPages = size > VERTICAL_MAX_PAGES
            val overBytes = binding.page.bitmapBytes() > budget
            if (!overPages && !overBytes) return

            // 锚点离屏顶可能隔好几短页；收窗不能裁掉还在视口内的半段。
            when (verticalSlots.trimSide(
                top = binding.page.topIndex,
                anchor = binding.page.currentIndex,
                bottom = binding.page.bottomIndex,
                back = VERTICAL_BACK,
                minSlots = minSlots,
            )) {
                PageSlots.TrimSide.START -> binding.page.dropFirst(verticalSlots.dropFirstPages(1))
                PageSlots.TrimSide.END -> binding.page.dropLast(verticalSlots.dropLastPages(1))
                null -> return
            }
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
    private fun resolveSpread(
        src: BookSource, requested: Int, requestedHalf: Int, targetW: Int, targetH: Int, exact: Boolean,
    ): Spread {
        fun load(index: Int, half: Int, exact: Boolean) =
            assembleSpread(src, index, half, exact, cacheOnly = false) { buildEntry(src, it, targetW, targetH) }
                ?: Spread(emptyList(), index)
        var spread = load(requested, requestedHalf, exact)
        // 双页并排时不跳：一次两张，跳掉一张会让配对错乱
        val canSkip = prefs.skipBlank && !prefs.dualPage && !exact
        var guard = 0
        while (canSkip && guard < 20 && spread.index < total - 1 && spread.pages.size == 1 &&
            pageEdits[spread.index] == null && pageEdits.pairedWith(spread.index) == null &&
            spread.pages.firstOrNull()?.let(::isBlankPage) == true
        ) {
            // 规格是「从第 N 张起每隔一张跳」，不是一路跳到有内容为止
            val from = prefs.skipBlankFrom
            if (spread.index < from || (spread.index - from) % 2 != 0) break
            val next = spread.index + 1
            val firstHalf = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
            spread = load(next, firstHalf, exact = true)
            guard++
        }
        return spread
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
    /** 进度条跟随当前页；右侧阅读时整条镜像，右端是第 1 页 */
    private fun syncScrubber() {
        binding.progressScrubber.setState(total, page, prefs.scrollMode == 2 && !prefs.vertical)
    }

    /**
     * 拖进度条：直接跳页，不做翻页动画、不写进度。
     * 写进度留给松手那一刻——拖动中每一帧都落库会白白多几百次写入。
     */
    private fun scrubTo(target: Int) {
        if (target < 0 || target >= total) return
        hideEndPage()
        exactJumpPage = -1
        half = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
        page = target
        showPage()
    }

    private fun updateLabels(src: BookSource) {
        if (toolbarShown) syncScrubber()
        val pos = src.pagePosition(page, chaptered)
        // 双页并排时一屏两页：页码写「3–4 / 12」，别只报左边那个数
        val lastShown = pos.page + (shownPages - 1).coerceAtLeast(0)
        binding.pageLabel.text = when {
            shownPages > 1 && pos.chaptered ->
                getString(R.string.reader_page_chapter_range, pos.chapterTitle, pos.page, lastShown, pos.total)
            shownPages > 1 ->
                getString(R.string.reader_page_range, pos.page, lastShown, pos.total)
            pos.chaptered ->
                getString(R.string.reader_page_chapter_format, pos.chapterTitle, pos.page, pos.total)
            else -> getString(R.string.reader_page_format, pos.page, pos.total)
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
    private fun assembleSpread(
        src: BookSource, index: Int, pageHalf: Int, exact: Boolean, cacheOnly: Boolean,
        fetch: (Int) -> PageCache.Entry?,
    ): Spread? {
        val range = spreadRange(src, index, exact)
        val first = range.first
        val last = range.last
        val forced = pageEdits.pairedWith(first) == last && first != last
        val a = fetch(first)?.let { pageOf(it, first, pageHalf, forced) }
        if (a == null && (cacheOnly || first == index)) return null
        if (first == last || (first == index && a?.wide == true)) {
            return a?.let { Spread(listOf(it), first) }
        }
        val b = fetch(last)?.let { pageOf(it, last, pageHalf, forced) }
        if (b == null && (cacheOnly || last == index)) return null
        if (a != null && b != null && !a.wide && !b.wide) {
            return Spread(if (prefs.spreadDirection == 1) listOf(b, a) else listOf(a, b), first)
        }
        // 前半被宽图截断时，仍须能落在用户要看的后半页。
        return if (index == last) b?.let { Spread(listOf(it), last) }
        else a?.let { Spread(listOf(it), first) }
    }

    /**
     * 这一屏覆盖哪几页。
     *
     * 章边界按「本章第一页到下一章前一页」算，组屏规则交给 [SpreadPlan]——
     * 那边是纯函数，边界情况在 JVM 单测里试完了。
     */
    private fun spreadRange(src: BookSource, index: Int, exact: Boolean = false): IntRange {
        pageEdits.pairedWith(index)?.let { other ->
            return if (exact && other < index) index..index else minOf(index, other)..maxOf(index, other)
        }
        if (pageEdits.pairingAt(index) == PageEdits.SOLO) return index..index
        val chapter = if (chaptered) src.chapterOf(index) else 0
        val chapterFirst = if (chaptered) src.firstPageOfChapter(chapter) else 0
        val chapterLast = if (chaptered) {
            SpreadPlan.chapterLast(
                chapter, chapterFirst, src.chapters.size, src::firstPageOfChapter, total,
            )
        } else {
            total - 1
        }
        val order = if (prefs.dualPage) prefs.spreadOrder else SpreadPlan.ORDER_NONE
        val range = SpreadPlan.range(index, chapterFirst, chapterLast, order)
        val first = if (exact || (range.first != index && pageEdits.pairingAt(range.first) != PageEdits.AUTO)) {
            index
        } else range.first
        val last = if (range.last != index && pageEdits.pairingAt(range.last) != PageEdits.AUTO) index else range.last
        return first..last
    }

    /** 只取解好的整页，不摆版面：竖向窗口要按拆分结果决定一页摆几段 */
    private fun buildEntry(src: BookSource, index: Int, targetW: Int, targetH: Int): PageCache.Entry? {
        return pageCache.get(index) ?: run {
            val loaded = loadPage(src, index, targetW, targetH) ?: return null
            pageCache.put(index, loaded)
            loaded
        }
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
        val useDisk = cacheToDisk && index !in checkedEditPages
        if (useDisk) {
            diskCache.load(bookId, key)?.let { bitmap ->
                return pageEntry(bitmap)
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
            TrimBounds.detect(
                bitmap = bitmap,
                level = prefs.trimLevel,
                ignoreSides = prefs.trimIgnoreSides,
                ignorePx = prefs.trimIgnorePx,
                protect = prefs.trimProtect,
                precise = prefs.trimAlgo == 1,
            )
        } else {
            TrimBounds.Bounds(0, 0, bitmap.width, bitmap.height)
        }
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /** 解一页并量出裁边范围。画当前页与后台预载都走这条路，两边存的必须是同一种东西 */
    private fun decodePage(src: BookSource, index: Int, targetW: Int, targetH: Int): PageCache.Entry? {
        if (sourceClosed) return null
        val preview = if (index == previewFor) onPreviewReady else null
        val bitmap = runCatching {
            // 与 closeSource 互斥：关源时若有人正在读，native 侧会踩空
            sourceLock.withLock {
                if (sourceClosed) {
                    null
                } else {
                    // 现画来源先渲染；章回体里图片章的页渲染不了（返回 null），退回字节流
                    (src as? RenderedPageSource)?.renderPage(index, targetW, targetH)
                        ?: decodeWithPreview(src, index, targetW, targetH, preview)
                }
            }
        }.getOrNull() ?: return null

        return pageEntry(bitmap)
    }

    private fun pageEntry(bitmap: Bitmap): PageCache.Entry {
        val full = boundsOf(bitmap)
        val verdict = if (prefs.detectSideways && !prefs.vertical && bitmap.width >= bitmap.height * 1.25f) {
            lumaOf(bitmap)?.let { SidewaysPage.judge(it.first, it.second, it.third) } ?: SidewaysPage.Verdict.UNKNOWN
        } else SidewaysPage.Verdict.NONE
        return PageCache.Entry(bitmap, full, full.width() > full.height() * 1.2f, verdict)
    }

    /**
     * 解一页；原图明显大于目标尺寸时，先把一张低清交给界面，再去解正片。
     *
     * 字节只读一遍，两次解码共用——否则远端会为这一眼多下一次图，比不等还慢。
     * 低清不进缓存：缓存里永远只有按目标尺寸解好的那一整张。
     */
    private fun decodeWithPreview(
        src: BookSource,
        index: Int,
        targetW: Int,
        targetH: Int,
        onPreview: ((PageCache.Entry) -> Unit)?,
    ): Bitmap? {
        val bytes = src.openPage(index).use { it.readBytes() }
        val bounds = PageBitmapLoader.measure(bytes)
        if (onPreview != null && bounds != null &&
            PageBitmapLoader.needsPreview(bounds.first, bounds.second, targetW, targetH)
        ) {
            // 交过一次就清掉，一屏只预览一张
            onPreviewReady = null
            previewFor = -1
            val small = PageBitmapLoader.load(
                bytes,
                (targetW / PREVIEW_DIVISOR).coerceAtLeast(1),
                (targetH / PREVIEW_DIVISOR).coerceAtLeast(1),
                decodeConfig(),
            )
            if (small != null) {
                onPreview(
                    PageCache.Entry(
                        small,
                        Rect(0, 0, small.width, small.height),
                        small.width > small.height * 1.2f,
                    )
                )
            }
        }
        return PageBitmapLoader.load(bytes, targetW, targetH, decodeConfig())
    }

    /**
     * 把缓存里的那一页摆成横向一屏要的样子。
     *
     * 手动转过的页先转到目标角度，再和其余页走同一条拆分流程：
     * 转成竖图（90°/270°）自然不再是宽图、不会被拆；180° 仍是宽图，就照旧按 [half] 取左半或右半。
     * 显示与翻页用的是同一个 [splitActive] 口径，不会出现「画面整图、导航却按半页走」的错位。
     */
    private fun pageOf(entry: PageCache.Entry, index: Int, pageHalf: Int = half, paired: Boolean = false): ReaderPage {
        val base = baseEntryFor(entry, index)
        val full = base.bounds
        if (paired || pageEdits.pairedWith(index) != null) return ReaderPage(base.bitmap, full, wide = false)
        val wide = shouldTreatAsSpread(base, index)
        if (wide && prefs.splitMode != 0) {
            val part = WideSplit.halfAt(full.toBox(), pageHalf)
            return ReaderPage(base.bitmap, part.toRect(), wide = true, split = true)
        }
        return ReaderPage(base.bitmap, full, wide = wide || isUnrotatedSideways(base, index))
    }

    /**
     * 竖向窗口里这一页占的全部段：普通页一段；宽图开着拆分时两段（上下），
     * 先后按「先看左半 / 先看右半」独立设置排，不随阅读方向自动反转。
     * 每段都铺满屏宽，跨页大图等效放大一倍逐段看。
     */
    private fun pagesOf(entry: PageCache.Entry, index: Int): List<ReaderPage> {
        val base = baseEntryFor(entry, index)
        val full = base.bounds
        if (shouldTreatAsSpread(base, index) && prefs.splitMode != 0) {
            return WideSplit.orderedHalves(full.toBox(), prefs.splitFirstHalf).map {
                ReaderPage(base.bitmap, it.toRect(), wide = true)
            }
        }
        return listOf(ReaderPage(base.bitmap, full, wide = base.wide))
    }

    /** 摆版面前的公共一步：手动转过的页先换成转过后的位图与裁边范围 */
    private fun baseEntryFor(entry: PageCache.Entry, index: Int): PageCache.Entry {
        val angle = rotatedDegrees[index] ?: pageEdits[index]?.rotation ?: 0
        return if (angle != 0) rotatedEntryFor(index, angle, entry.bitmap) else entry
    }

    private fun shouldTreatAsSpread(entry: PageCache.Entry, index: Int): Boolean = when (pageEdits[index]?.kind) {
        PageEdits.NORMAL -> false
        PageEdits.SPREAD -> true
        else -> !isUnrotatedSideways(entry, index) &&
            (entry.wide || (pageEdits[index] == null && entry.verdict == SidewaysPage.Verdict.SPREAD))
    }

    private fun isUnrotatedSideways(entry: PageCache.Entry, index: Int): Boolean =
        prefs.detectSideways && !prefs.vertical && pageEdits[index] == null && entry.verdict == SidewaysPage.Verdict.SIDEWAYS

    private fun Rect.toBox(): WideSplit.Box = WideSplit.Box(left, top, right, bottom)

    private fun WideSplit.Box.toRect(): Rect = Rect(left, top, right, bottom)

    /**
     * 转过的那一页：位图与裁边范围都按转过后的画面算，翻走再回来直接复用。
     *
     * 复用要同时核对四件事：页号、角度、源位图身份、裁边参数。
     * 源位图身份挡住「预览小图顶替高清正片」与重解码；裁边参数挡住「同一张图上改了裁边却仍显示旧范围」。
     */
    @Synchronized
    private fun rotatedEntryFor(index: Int, angle: Int, src: Bitmap): PageCache.Entry {
        val trimKey = trimKey()
        rotatedEntry?.let {
            if (rotatedIndex == index && rotatedAngle == angle &&
                rotatedSource === src && rotatedTrimKey == trimKey
            ) {
                return it
            }
        }
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        val bitmap = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        val bounds = boundsOf(bitmap)
        val entry = PageCache.Entry(bitmap, bounds, bounds.width() > bounds.height() * 1.2f)
        rotatedIndex = index
        rotatedAngle = angle
        rotatedSource = src
        rotatedTrimKey = trimKey
        rotatedEntry = entry
        return entry
    }

    /** 裁边相关参数的快照：任一改动都让旋转结果作废，与主缓存的失效口径对齐 */
    private fun trimKey(): String =
        "${prefs.trimBorder}|${prefs.trimLevel}|${prefs.trimIgnoreSides}|" +
            "${prefs.trimIgnorePx}|${prefs.trimProtect}|${prefs.trimAlgo}"

    /** 主缓存因显示参数变化清空时，旋转结果同步作废，避免留下旧裁边 / 旧尺寸的图 */
    @Synchronized
    private fun clearRotatedCache() {
        rotatedEntry = null
        rotatedSource = null
        rotatedTrimKey = null
    }

    /**
     * 横置页提示：一页横着扫进来，内容转 90° 才是正的。
     *
     * 判定放到后台做，只读一张小图；判不准（[SidewaysPage.Verdict.UNKNOWN]）就什么都不做，
     * 跨页拆分照旧。每页只提示一次，竖向模式下不提示（那里本来就是整页铺满宽度）。
     */
    private fun maybeHintSideways(index: Int) {
        if (!prefs.detectSideways || prefs.vertical || atEnd || page != index) return
        if (index in hintedPages || rotatedDegrees.containsKey(index) || pageEdits.pairedWith(index) != null) return
        if (pageCache[index]?.let { isUnrotatedSideways(it, index) } == true) showSidewaysHint()
    }

    /** 判定只看字行走向，长边缩到 [JUDGE_SIDE] 就够；返回亮度、宽、高 */
    private fun lumaOf(bitmap: Bitmap): Triple<IntArray, Int, Int>? {
        if (bitmap.isRecycled) return null
        val longest = maxOf(bitmap.width, bitmap.height)
        val w = if (longest <= JUDGE_SIDE) bitmap.width else (bitmap.width * JUDGE_SIDE / longest).coerceAtLeast(1)
        val h = if (longest <= JUDGE_SIDE) bitmap.height else (bitmap.height * JUDGE_SIDE / longest).coerceAtLeast(1)
        val small = if (w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return Triple(luma, w, h)
    }

    /**
     * 浮层内容分两态：首次问「要不要转」，转过之后改成二次确认。
     * 转过一次就不再打扰翻页：右边换成「就这样」，提示里只问方向对不对。
     */
    private fun showSidewaysHint(turns: Int = 0) {
        if (page < 0) return
        // 工具栏展开时底栏占着同一块区域，提示盖上去会挡住按钮：此时不显示、也不记「已提示」，
        // 等工具栏收起后再评估。识别开关、阅读模式、书末状态也在显示前统一复查一遍。
        if (toolbarShown || !prefs.detectSideways || prefs.vertical || atEnd) return
        sidewaysHintPage = page
        hintedPages += page
        val rotated = turns > 0
        binding.sidewaysTitle.text = getString(
            if (rotated) R.string.reader_sideways_done_title else R.string.reader_sideways_title,
        )
        binding.sidewaysRotate.text = getString(
            if (rotated) R.string.reader_sideways_again else R.string.reader_sideways_rotate,
        )
        binding.sidewaysSkip.text = getString(
            if (rotated) R.string.reader_sideways_accept else R.string.reader_sideways_skip,
        )
        binding.sidewaysHint.animate().cancel()
        if (!binding.sidewaysHint.isVisible) {
            binding.sidewaysHint.isVisible = true
            binding.sidewaysHint.alpha = 0f
            binding.sidewaysHint.translationY = dp(8).toFloat()
            binding.sidewaysHint.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            // 收起动画刚起就被再次唤起：把透明度拉回实心，别停在半透明上
            binding.sidewaysHint.alpha = 1f
            binding.sidewaysHint.translationY = 0f
        }
        scheduleHintHide()
    }

    private fun scheduleHintHide() {
        binding.sidewaysHint.removeCallbacks(hideSidewaysHintRunnable)
        binding.sidewaysHint.postDelayed(hideSidewaysHintRunnable, SIDEWAYS_HINT_MS)
    }

    private fun hideSidewaysHint() {
        sidewaysHintPage = -1
        binding.sidewaysHint.removeCallbacks(hideSidewaysHintRunnable)
        if (binding.sidewaysHint.isVisible) {
            binding.sidewaysHint.animate().cancel()
            binding.sidewaysHint.animate().alpha(0f).setDuration(150).withEndAction {
                binding.sidewaysHint.isVisible = false
                binding.sidewaysHint.alpha = 1f
            }.start()
        }
    }

    /**
     * 手动转正：每点一次转 90°，转满一圈回到原样。
     * 先预览角度，点「保存转正」才按页存入书库。
     */
    private fun rotateCurrentPage() {
        val index = page
        if (index < 0 || index != sidewaysHintPage || loading || editing) return
        val next = ((rotatedDegrees[index] ?: 0) + 90) % 360
        if (next == 0) rotatedDegrees.remove(index) else rotatedDegrees[index] = next
        clearRotatedCache()
        half = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false)
        keepHintOnShow = true
        showPage()
        if (next == 0) {
            hideSidewaysHint()
            return
        }
        showSidewaysHint(next / 90)
    }

    private suspend fun verifyPageEdits(src: BookSource, rows: List<PageEditEntity>): PageEdits {
        val context = currentCoroutineContext()
        checkedEditPages = checkedEditPages + rows.flatMap { row ->
            listOfNotNull(row.page, PageEdits.partner(row.page, row.pairing))
        }
        return PageEdits.verify(rows, src.pageCount, { if (chaptered) src.chapterOf(it) else 0 }) { index ->
            context.ensureActive()
            try {
                verifiedProofs[index] ?: readPageProof(src, index).also { verifiedProofs[index] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readPageProof(src: BookSource, index: Int): String = sourceLock.withLock {
        check(!sourceClosed)
        PageProof.read(src, index)
    }

    private fun choosePageEdit() {
        val src = source ?: return
        if (loading || editing || atEnd || binding.error.isVisible) return
        if (prefs.vertical && verticalSlots.firstSlotOf(page) < 0) return
        if (!prefs.vertical && !drawnOnce) return
        stopAutoScroll()
        flingAnimator?.cancel()
        val first = page
        if (!prefs.vertical && shownPages == 2) {
            AppDialogBuilder(this).setTitle(R.string.reader_edit_page)
                .setItems(arrayOf(editPageLabel(src, first), editPageLabel(src, first + 1))) { _, which ->
                    showPageEdit(src, first + which)
                }
                .setNegativeButton(R.string.action_cancel, null).show()
        } else {
            showPageEdit(src, first)
        }
    }

    private fun editPageLabel(src: BookSource, index: Int): String {
        val pos = src.pagePosition(index, chaptered)
        val label = getString(R.string.reader_edit_page_number, pos.page)
        return if (pos.chaptered) pos.chapterTitle + " · " + label else label
    }

    private fun showPageEdit(src: BookSource, index: Int) {
        val saved = pageEdits[index]
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        fun field(label: Int, labels: List<Int>, selected: Int): Spinner {
            val spinner = Spinner(this).apply { id = View.generateViewId() }
            form.addView(TextView(this).apply {
                text = getString(label)
                labelFor = spinner.id
                setPadding(0, dp(8), 0, 0)
            })
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels.map(::getString)).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.setSelection(selected.coerceIn(labels.indices))
            form.addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            return spinner
        }
        val kind = field(R.string.reader_edit_kind, listOf(
            R.string.reader_edit_auto, R.string.reader_edit_normal, R.string.reader_edit_spread,
        ), saved?.kind ?: PageEdits.AUTO)
        val rotation = field(R.string.reader_edit_rotation, listOf(
            R.string.reader_edit_0, R.string.reader_edit_90, R.string.reader_edit_180, R.string.reader_edit_270,
        ), (rotatedDegrees[index] ?: saved?.rotation ?: 0) / 90)
        val pairs = buildList {
            add(PageEdits.AUTO to R.string.reader_edit_auto)
            add(PageEdits.SOLO to R.string.reader_edit_solo)
            if (canPair(src, index, index - 1)) add(PageEdits.PREVIOUS to R.string.reader_edit_previous)
            if (canPair(src, index, index + 1)) add(PageEdits.NEXT to R.string.reader_edit_next)
        }
        val pairing = field(R.string.reader_edit_pairing, pairs.map { it.second },
            pairs.indexOfFirst { it.first == pageEdits.pairingAt(index) })
        if (prefs.vertical) form.addView(TextView(this).apply { text = getString(R.string.reader_edit_horizontal_only) })
        AppDialogBuilder(this)
            .setTitle(editPageLabel(src, index)).setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.reader_edit_clear) { _, _ -> savePageEdit(index, PageEdits.AUTO, 0, PageEdits.AUTO) }
            .setPositiveButton(R.string.reader_edit_save) { _, _ ->
                savePageEdit(index, kind.selectedItemPosition, rotation.selectedItemPosition * 90,
                    pairs[pairing.selectedItemPosition].first)
            }.show()
    }

    private fun canPair(src: BookSource, index: Int, other: Int): Boolean =
        other in 0 until total && (!chaptered || src.chapterOf(index) == src.chapterOf(other))

    private fun savePageEdit(index: Int, kind: Int, angle: Int, pairing: Int) {
        val src = source ?: return
        val original = bookAtOpen ?: return
        if (editing || index !in 0 until total) return
        val partner = PageEdits.partner(index, pairing)
        if (partner != null && !canPair(src, index, partner)) return
        editing = true
        binding.editPage.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val clearing = kind == PageEdits.AUTO && angle == 0 && pairing == PageEdits.AUTO
                    if (!clearing) {
                        for (p in listOfNotNull(index, partner)) verifiedProofs[p] = readPageProof(src, p)
                    }
                    val dao = db.pageEditDao()
                    val rows = db.withTransaction {
                        val current = db.bookDao().get(bookId)
                        check(current != null && current.contentVersion == original.contentVersion &&
                            current.byteSize == original.byteSize && current.modifiedAt == original.modifiedAt &&
                            current.docRef == original.docRef) { "Book changed while reading" }
                        for (old in PageEdits.unlink(dao.forBook(bookId), index, partner)) {
                            if (old.isEmpty()) dao.remove(bookId, old.page) else dao.put(old)
                        }
                        if (clearing) dao.remove(bookId, index) else dao.put(PageEditEntity(
                            bookId, index, verifiedProofs.getValue(index), kind, angle, pairing, partner?.let(verifiedProofs::getValue),
                        ))
                        dao.forBook(bookId)
                    }
                    verifyPageEdits(src, rows)
                }
                pageEdits = result
                rotatedDegrees.remove(index)
                hintedPages.remove(index)
                pageCache.remove(index)
                partner?.let(pageCache::remove)
                clearRotatedCache()
                if (!atEnd && (page == index || (!prefs.vertical && shownPages == 2 && page + 1 == index) ||
                        (partner != null && page == partner))) {
                    val target = if (prefs.vertical) page else partner?.let { minOf(index, it) } ?: index
                    keepVerticalOffset = prefs.vertical
                    turnTo(target, exact = !prefs.vertical && partner == null && target > page)
                } else if (!atEnd && prefs.vertical && verticalSlots.firstSlotOf(index) >= 0) {
                    keepVerticalOffset = true
                    turnTo(page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Reading", "Page edit failed", e)
                Toast.makeText(this@ReadingActivity, R.string.reader_edit_failed, Toast.LENGTH_SHORT).show()
            } finally {
                editing = false
                binding.editPage.isEnabled = true
            }
        }
    }

    /**
     * 预取接下来的几页。翻页时等的是「连远端 + 解码」，先把这几页解好放进缓存，翻过去就直接出图。
     *
     * 翻页只往队列里放页号，解码由常驻的两个 worker 慢慢做——每次翻页都掐掉预载重开，
     * 连续翻页时就永远追不上，等于没预载。
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
        // 落在横置提示卡上的这一条手势整套都不喂给手势识别：
        // 手势是提前于子视图分发的，喂了就会先翻页；而且卡片按钮一点就收，
        // 双击判定等 300ms 后补发的单击会落空再翻一页。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnSidewaysHint = overSidewaysHintAt(ev.x, ev.y)
            touchOnAutoSpeedBar = overAutoSpeedBarAt(ev.x, ev.y)
            // 自动下滑一碰就停：调速浮层与「自动」键除外，那两处按下去是为了调速和停/开
            if (autoScroll.running && !touchOnAutoSpeedBar && !overAutoButtonAt(ev.x, ev.y)) {
                stopAutoScroll()
            }
        }
        if (!touchOnSidewaysHint && !touchOnAutoSpeedBar) {
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchOnSidewaysHint = false
            touchOnAutoSpeedBar = false
        }
        return handled
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
                // 方向容差：值越大越要求手势接近水平（0 → 1.2 倍，9 → 2.1 倍）
                val tolerance = 1.2f + prefs.flingResistance * 0.1f
                if (kotlin.math.abs(velocityX) < FLING_MIN_VELOCITY) return false
                // 明显斜着划的不算翻页，免得上下拖动误触
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * tolerance) return false
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
        // 惯性档位：动量越大，初速按比例放大、衰减也拉长；但一次 fling 仍然只滚一段
        val boost = 1f + prefs.flingMomentum * 0.12f
        val anim = android.animation.ValueAnimator.ofFloat(velocityY * boost, 0f).apply {
            duration = (VERTICAL_FLING_MS + prefs.flingMomentum * 60L)
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                // 上滑时 velocityY 为负，内容要往前走，所以取反
                handleVerticalScroll(-(a.animatedValue as Float) * FRAME_SECONDS)
            }
        }
        flingAnimator = anim
        anim.start()
    }

    /** 条漫自动下滑：状态与速度在 [AutoScroll] 里，这里只按帧钟驱动、把位移落到画面 */
    private val autoScroll = AutoScroll()
    private var autoAnimator: android.animation.ValueAnimator? = null

    /** 这一条手势按在调速浮层上，整条不喂手势识别，也不停自动下滑 */
    private var touchOnAutoSpeedBar = false

    private fun toggleAutoScroll() {
        if (autoScroll.running) stopAutoScroll() else startAutoScroll()
    }

    /**
     * 开始自动下滑：匀速推进、滚到书的尾自停、内容上一碰就停。
     * 只在竖向连贯里有意义；书的尾空态与还在解首窗时不起。
     */
    private fun startAutoScroll() {
        if (!prefs.vertical || atEnd || source == null || loading) return
        autoScroll.level = globalPrefs.autoScrollLevel
        autoScroll.start()
        setToolbarVisible(false)
        syncAutoButton()
        showAutoSpeedBar(true)
        autoAnimator?.cancel()
        var last = 0f
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AUTO_TICK_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                // 循环重启时值跳回 0：这一帧的时长 = 上一轮的余量 + 新一轮的开头
                val dt = if (v >= last) v - last else v + (1f - last)
                last = v
                val dyDp = autoScroll.tick(dt, atEnd)
                if (!autoScroll.running) {
                    stopAutoScroll()
                    return@addUpdateListener
                }
                if (dyDp > 0f) {
                    handleVerticalScroll(dyDp * resources.displayMetrics.density)
                }
                // 接页解不动时画面停在边界，等解好接着走；整本翻完才收
                if (atEnd) stopAutoScroll()
            }
        }
        autoAnimator = anim
        anim.start()
    }

    private fun stopAutoScroll() {
        val wasRunning = autoScroll.running
        autoScroll.stop()
        autoAnimator?.cancel()
        autoAnimator = null
        if (wasRunning) {
            syncAutoButton()
            showAutoSpeedBar(false)
            // 停下的位置就是离开的位置，别等节流窗口
            saveProgress()
        }
    }

    /** 调速：改档位立即生效并写全局值，下一本书、下一次进来还按这一档走 */
    private fun adjustAutoSpeed(delta: Int) {
        val next = AutoScroll.clampLevel(autoScroll.level + delta)
        autoScroll.level = next
        globalPrefs.autoScrollLevel = next
        binding.autoSpeedLabel.text = getString(R.string.reader_auto_speed_value, next)
    }

    /** 底栏「自动」键的两态：跑着时写「停止」并点亮图标 */
    private fun syncAutoButton() {
        val running = autoScroll.running
        binding.autoLabel.setText(if (running) R.string.reader_auto_stop else R.string.reader_auto)
        if (running) {
            binding.autoIcon.setColorFilter(getColor(R.color.brand_accent))
        } else {
            binding.autoIcon.clearColorFilter()
        }
    }

    private fun showAutoSpeedBar(show: Boolean) {
        val bar = binding.autoSpeedBar
        bar.animate().cancel()
        if (show) {
            binding.autoSpeedLabel.text = getString(R.string.reader_auto_speed_value, autoScroll.level)
            bar.isVisible = true
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(160).start()
        } else if (bar.isVisible) {
            bar.animate().alpha(0f).setDuration(150).withEndAction {
                bar.isVisible = false
                bar.alpha = 1f
            }.start()
        }
    }

    /** 调速浮层上的触摸不算「一碰就停」，否则想调个速就把滚动停了 */
    private fun overAutoSpeedBarAt(x: Float, y: Float): Boolean {
        if (!binding.autoSpeedBar.isVisible) return false
        return viewHitAt(binding.autoSpeedBar, x, y, dp(6))
    }

    /** 底栏「自动」键：按它是为了停/开，不当内容触摸处理 */
    private fun overAutoButtonAt(x: Float, y: Float): Boolean {
        if (!binding.bottomBar.isVisible || !binding.auto.isVisible) return false
        return viewHitAt(binding.auto, x, y, 0)
    }

    private fun viewHitAt(view: View, x: Float, y: Float, pad: Int): Boolean {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val r = Rect(
            loc[0] - pad,
            loc[1] - pad,
            loc[0] + view.width + pad,
            loc[1] + view.height + pad,
        )
        return r.contains(x.toInt(), y.toInt())
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
        if (overToolbarAt(x, y) || overEndButtonAt(x, y) || overSidewaysHintAt(x, y)) return
        // 竖向连贯里点击只负责呼出工具栏，位置交给滚动
        // 关掉点击翻页之后，点哪儿也都只呼出工具栏——留给只想看不想误翻的人
        if (prefs.vertical || prefs.tapFlipMode == 0) {
            setToolbarVisible(!toolbarShown)
            return
        }
        // 方向可切：从左往右读时点右侧翻到下一页，从右往左读时反过来
        val ratio = x / binding.readerRoot.width.coerceAtLeast(1)
        val upper = y < binding.readerRoot.height / 2f

        // 对角两档：把屏幕按对角线切成两半，左下与右上 / 左上与右下
        val target: Int = when (prefs.tapFlipMode) {
            2 -> if (upper == (ratio > 0.5f)) 0 else 1   // 左下↔右上
            3 -> if (upper == (ratio < 0.5f)) 1 else 0   // 左上↔右下
            else -> when {
                ratio < ZONE_LEFT -> 0
                ratio > ZONE_RIGHT -> 1
                else -> -1
            }
        }
        if (target < 0) {
            setToolbarVisible(!toolbarShown)
            return
        }
        // 互换：左右两区干的活对调，边界坐标不变
        val forward = if (prefs.tapFlipSwap) target == 0 else target == 1
        val goForward = if (prefs.forwardRight) forward else !forward
        if (goForward) turnForward() else turnBackward()
    }

    /** 点在工具栏上时不翻页也不收起工具栏，把事件留给按钮 */
    private fun overToolbarAt(x: Float, y: Float): Boolean {
        if (!toolbarShown) return false
        val inTop = binding.topBar.isVisible && y <= binding.topBar.bottom
        val inBottom = binding.bottomBar.isVisible && y >= binding.bottomBar.top
        return inTop || inBottom
    }

    /** 横置提示卡自带按钮，点在卡上不算翻页，事件留给卡里的按钮 */
    private fun overSidewaysHintAt(x: Float, y: Float): Boolean {
        if (!binding.sidewaysHint.isVisible) return false
        // 卡片淡入时带 translation，getHitRect 会偏；按落位后的窗口坐标加余量算
        val loc = IntArray(2)
        binding.sidewaysHint.getLocationInWindow(loc)
        val pad = dp(6)
        val r = Rect(
            loc[0] - pad,
            loc[1] - pad,
            loc[0] + binding.sidewaysHint.width + pad,
            loc[1] + binding.sidewaysHint.height + pad
        )
        return r.contains(x.toInt(), y.toInt())
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
        if (show) hideSidewaysHint()
        // 常驻页码与工具栏里的页码只留一个，避免重复；书的尾上也不显示
        binding.pageLabel.isVisible = !show && prefs.showPageLabel && !atEnd
        binding.topBar.isVisible = show
        binding.bottomBar.isVisible = show
        // 进度条跟着菜单走；只有一页的书不给它，免得用户以为坏了
        binding.progressScrubber.isVisible = show && total > 1 && !atEnd
        if (show) syncScrubber()
        if (show) {
            binding.topBar.alpha = 0f
            binding.bottomBar.alpha = 0f
            binding.topBar.animate().alpha(1f).setDuration(140).start()
            binding.bottomBar.animate().alpha(1f).setDuration(140).start()
        } else {
            // 工具栏收起后，之前被它挡住的横置提示可以重新评估要不要显示
            maybeHintSideways(page)
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
     * 目录抽屉：点章去首页，点书签去章内页。
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
        val tabs = content.findViewById<TabLayout>(R.id.toc_tabs)
        val contentsTab = tabs.newTab().setText(R.string.reader_toolbar_toc)
        val bookmarksTab = tabs.newTab().setText(R.string.reader_bookmark)
        tabs.addTab(contentsTab)
        tabs.addTab(bookmarksTab)

        var descending = appPrefs.tocDescending
        val current = src.chapterOf(page)
        val padH = dp(10)
        val padV = dp(13)
        var bookmarks: List<BookmarkEntity>? = null
        var bookmarkLoadFailed = false
        var bookmarkJob: Job? = null

        fun renderContents() {
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
                        jumpToPage(src.firstPageOfChapter(info.ordinal))
                    }
                }
                if (isCurrent) currentRow = row
                list.addView(row)
            }

            // 章多的时候把当前章滚到可见位置
            currentRow?.let { row ->
                scroll.post {
                    if (tabs.selectedTabPosition == contentsTab.position && row.parent === list) {
                        scroll.scrollTo(0, (row.top - scroll.height / 3).coerceAtLeast(0))
                    }
                }
            }
        }

        fun renderBookmarks() {
            list.removeAllViews()
            for (mark in bookmarks.orEmpty()) {
                val chapter = src.chapters.getOrNull(mark.chapterOrdinal) ?: continue
                // 章缩短后，越界的旧书签不能落到下一章。
                if (mark.pageInChapter !in 0 until chapter.pageCount) continue
                val target = src.firstPageOfChapter(chapter.ordinal) + mark.pageInChapter
                val row = TextView(this).apply {
                    text = getString(R.string.reader_bookmark_row, chapter.title, mark.pageInChapter + 1)
                    textSize = 14f
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                    setTextColor(getColor(R.color.shell_ink))
                    isFocusable = true
                    setOnClickListener {
                        sheet.dismiss()
                        jumpToPage(target)
                    }
                }
                list.addView(row)
            }
            if (list.childCount != 0) return
            list.addView(TextView(this).apply {
                setText(when {
                    bookmarkLoadFailed -> R.string.reader_bookmark_load_failed
                    bookmarks == null -> R.string.reader_bookmark_loading
                    else -> R.string.reader_bookmark_empty
                })
                textSize = 14f
                minHeight = dp(72)
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(R.color.shell_ink))
            })
        }

        order.setOnClickListener {
            descending = !descending
            appPrefs.tocDescending = descending
            renderContents()
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                scroll.scrollTo(0, 0)
                if (tab == contentsTab) {
                    order.visibility = View.VISIBLE
                    renderContents()
                    return
                }
                order.visibility = View.INVISIBLE
                renderBookmarks()
                if (bookmarkJob == null) {
                    bookmarkJob = lifecycleScope.launch {
                        db.bookmarkDao().observeForBook(bookId)
                            .catch {
                                bookmarkLoadFailed = true
                                if (tabs.selectedTabPosition == bookmarksTab.position) renderBookmarks()
                            }
                            .collect {
                                bookmarks = it
                                if (tabs.selectedTabPosition == bookmarksTab.position) renderBookmarks()
                            }
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        sheet.setOnDismissListener { bookmarkJob?.cancel() }

        sheet.setContentView(content)
        sheet.show()
        renderContents()
    }

    /** 前进一屏：并排时一次跨两页；拆页时按「先看哪半」的顺序逐半看；长图先滚再看下一页 */
    private fun turnForward() {
        if (atEnd) return
        // 竖向连贯没有「一屏一页」：按键与滚轮按视口距离推进，不跳过拆出的上下半段
        if (prefs.vertical) {
            jumpVertical(forward = true)
            return
        }
        val nextHalf = WideSplit.nextHalf(half, prefs.splitFirstHalf)
        if (splitActive() && nextHalf != null) {
            half = nextHalf
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
        turnTo(target, newHalf = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false))
    }

    private fun turnBackward() {
        // 书的尾往回翻就是最后一页，不是翻不动
        if (atEnd) {
            leaveEndPage()
            return
        }
        if (prefs.vertical) {
            jumpVertical(forward = false)
            return
        }
        val prevHalf = WideSplit.prevHalf(half, prefs.splitFirstHalf)
        if (splitActive() && prevHalf != null) {
            half = prevHalf
            showPage()
            return
        }
        // 页内已经往下滚过：先往回滚，到顶才翻页
        if (binding.page.scrollWithin(-jumpDistancePx())) return
        // 后退落到宽图时先看最后读的那半，符合「从后往前」的直觉
        turnTo(
            page - 1,
            newHalf = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = true),
        )
    }

    /**
     * 竖向按键 / 按钮 / 滚轮：按视口距离推进。一次推进跨越窗口边界时，先滚到边界、
     * 余量接页后接着滚完，推进距离才与一屏一致；整本读完才进书尾。
     * 按整页号跳会略过拆出的半段，也会把未滚完的末页直接判成读完。
     */
    private fun jumpVertical(forward: Boolean) {
        scrollVerticalBy(if (forward) jumpDistancePx() else -jumpDistancePx())
    }

    /** 滚 [dy]；被窗口边界截住的余量接页后递归接着滚，直到滚完或整本读完 */
    private fun scrollVerticalBy(dy: Float) {
        val before = binding.page.scrollPosition
        binding.page.scrollBy(dy)
        val rest = dy - (binding.page.scrollPosition - before)
        syncVerticalPage()
        if (kotlin.math.abs(rest) < 0.5f) return
        if (dy > 0f) {
            val next = (verticalSlots.lastPage() ?: page) + 1
            if (next >= total) {
                showEndPage()
                return
            }
            attachVerticalPage(next, prepend = false, thenScroll = rest)
        } else {
            val first = verticalSlots.firstPage() ?: page
            if (first <= 0) return
            attachVerticalPage(first - 1, prepend = true, thenScroll = rest)
        }
    }

    /** 一次翻/滚多远：0 与 2 对长图都是一屏，半屏档留给想细看的人 */
    private fun jumpDistancePx(): Float {
        val h = binding.page.height.toFloat().coerceAtLeast(1f)
        return if (prefs.flipStep == 1) h / 2f else h
    }

    private fun splitActive(): Boolean = currentSplit

    private fun jumpToPage(target: Int) {
        if (target !in 0 until total) return
        stopAutoScroll()
        flingAnimator?.cancel()
        keepVerticalOffset = false
        hideEndPage()
        setToolbarVisible(false)
        turnTo(target, exact = true)
    }

    private fun turnTo(
        target: Int,
        newHalf: Int = WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = false),
        exact: Boolean = false,
    ) {
        if (target < 0 || target >= total) return
        exactJumpPage = if (exact) target else -1
        half = newHalf
        page = target
        // 上一屏还在解：记下这次翻页，等它画完立刻接着画。
        // 若在此直接 return，连续点击会被吞掉，看着像卡住。
        if (loading) {
            pendingShow = true
            return
        }
        showPage()
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
        hideSidewaysHint()
        binding.endMeta.text = endMetaText()
        binding.endPage.isVisible = true
        if (toolbarShown) setToolbarVisible(false)
    }

    private fun leaveEndPage() {
        if (!atEnd) return
        hideEndPage()
        // 最后一页是宽图且开着拆页时，从后往前落回最后读的那半
        half = if (currentSplit) {
            WideSplit.entryHalf(prefs.splitFirstHalf, fromBehind = true)
        } else {
            0
        }
        showPage()
    }

    private fun hideEndPage() {
        atEnd = false
        binding.endPage.isVisible = false
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
        if (opening || loading || binding.error.isVisible) return
        val current = page
        val chapter = src.chapterOf(current)
        val progress = ProgressEntity(
            bookId = bookId,
            chapterOrdinal = chapter,
            pageInChapter = current - src.firstPageOfChapter(chapter),
            pageGlobal = current,
            finished = finishedOnce,
            updatedAt = System.currentTimeMillis(),
        )
        lifecycleScope.launch {
            runCatching {
                db.progressDao().upsert(progress)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val first = !resumedOnce
        resumedOnce = true
        applyOrientation(appPrefs)
        applyDisplayPrefs()
        // 从设置页回来时参数可能变了，按新参数重画当前页；竖向保留页内位置
        if (!first && source != null && !loading) {
            keepVerticalOffset = prefs.vertical
            showPage()
        }
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
        // 退到后台就停：回来时画面停在离开的位置，不自己接着滚
        stopAutoScroll()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
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
