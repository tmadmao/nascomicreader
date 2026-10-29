package com.nascomic.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityMainBinding
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.SourceRepository
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.LibraryScanner
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.settings.applyOrientation
import com.nascomic.reader.ui.detail.DetailActivity
import com.nascomic.reader.ui.reader.ReadingActivity
import com.nascomic.reader.ui.settings.SettingsActivity
import com.nascomic.reader.ui.shelf.ShelfAdapter
import com.nascomic.reader.ui.shelf.ShelfGrid
import com.nascomic.reader.ui.widget.ScanProgressDialog
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架。顶部「书库」+ 标签 chip + 「接着读」卡 + 封面网格；列数按屏宽算（[ShelfGrid]）。
 * 搜索与排序还没做。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val sources by lazy { SourceRepository(this, db) }
    private val scanner by lazy { LibraryScanner(this, db) }
    private val thumbs by lazy { ThumbnailStore(this) }
    private val opener by lazy { BookOpener(this) }

    private val prefs by lazy { AppPrefs(this) }

    private lateinit var adapter: ShelfAdapter
    private var currentTab: String = TAB_ALL
    private var allSources: List<SourceEntity> = emptyList()

    /** 各标签下的书数，随 refresh 一起算好 */
    private var tabCounts: Map<String, Int> = emptyMap()

    /** 封面生成队列：串行，慢慢来；同一本只排一次 */
    private val coverQueue = Channel<Long>(Channel.UNLIMITED)
    private val queuedCovers = HashSet<Long>()
    private val MAX_COVER_QUEUE = 60
    private var coverWidth = 0

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) scanFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        adapter = ShelfAdapter(thumbs, { openBook(it) }, { id -> requestCover(id) })
        binding.grid.adapter = adapter
        applyGridMetrics()

        binding.emptyLocal.setOnClickListener { pickFolder.launch(null) }
        binding.emptyRemote.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.grid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) requestVisibleCovers()
            }
        })
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        startCoverWorker()
        refresh()
    }

    /**
     * 清单里声明了 configChanges，转屏不会重建 Activity——
     * 列数与封面解码宽度得自己按新屏宽重算，否则横屏还是竖屏那 3/6 列。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGridMetrics()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        applyOrientation(prefs)
        refresh()
    }

    // ── 数据 ───────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            allSources = sources.list()

            // 标签上的计数与列表分两次取：计数要全量，列表要按当前标签筛
            val allBooks = db.bookDao().allSorted()
            val progress = db.progressDao().all().associateBy { it.bookId }
            tabCounts = buildMap {
                put(TAB_ALL, allBooks.size)
                put(TAB_RECENT, allBooks.count { progress.containsKey(it.id) })
                allSources.forEach { source ->
                    put("$TAB_SOURCE_PREFIX${source.id}", allBooks.count { it.sourceId == source.id })
                }
            }
            renderTabs()

            val books = queryBooks()
            adapter.submit(books, progress)

            val empty = books.isEmpty()
            binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.grid.visibility = if (empty) View.GONE else View.VISIBLE
            // 空书架时不再报「全部漫画 0 本」——上面那块引导已经把话说清了
            binding.sectionTitle.visibility = if (empty) View.GONE else View.VISIBLE
            binding.sectionCount.visibility = if (empty) View.GONE else View.VISIBLE

            renderResume(allBooks, progress)
            requestVisibleCovers()
        }
    }

    /**
     * 「接着读」卡片：取最近读过的那本。
     * 最近阅读标签下不再重复——那里第一本本来就是它。
     */
    private fun renderResume(
        allBooks: List<BookEntity>,
        progress: Map<Long, com.nascomic.reader.library.data.ProgressEntity>,
    ) {
        val latest = progress.values.maxByOrNull { it.updatedAt }
        val book = latest?.let { p -> allBooks.firstOrNull { it.id == p.bookId } }
        if (book == null || latest == null || currentTab == TAB_RECENT || book.pageTotal <= 0) {
            binding.resumeCard.visibility = View.GONE
            return
        }

        binding.resumeCard.visibility = View.VISIBLE
        binding.resumeTitle.text = book.title
        binding.resumeMeta.text = resumeMeta(book, latest)

        val percent = readPercent(latest.pageGlobal, book.pageTotal, latest.finished)
        binding.resumePercent.text = getString(R.string.shelf_read_percent, percent)
        binding.resumeBar.progress = percent
        binding.resumeAction.text = if (book.chapterTotal > 0) {
            getString(
                R.string.shelf_position_chapter,
                (latest.chapterOrdinal + 1).coerceAtMost(book.chapterTotal),
                book.chapterTotal,
            )
        } else {
            getString(
                R.string.shelf_position_pages,
                (latest.pageGlobal + 1).coerceAtMost(book.pageTotal),
                book.pageTotal,
            )
        }

        binding.resumeCard.setOnClickListener { openBook(book) }
        binding.resumePlay.setOnClickListener { openBook(book) }
    }

    /** 「第 11 话 / 24 话 · 来源名」；单行本没有话就报页 */
    private fun resumeMeta(
        book: BookEntity,
        p: com.nascomic.reader.library.data.ProgressEntity,
    ): String {
        val source = allSources.firstOrNull { it.id == book.sourceId }?.label.orEmpty()
        val position = when {
            p.finished -> getString(R.string.progress_finished)
            book.chapterTotal > 0 ->
                getString(R.string.shelf_meta_chapter, p.chapterOrdinal + 1, book.chapterTotal)
            else -> getString(R.string.shelf_meta_pages, p.pageGlobal + 1, book.pageTotal)
        }
        return if (source.isEmpty()) position else "$position · $source"
    }

    private suspend fun queryBooks(): List<BookEntity> = when {
        currentTab == TAB_RECENT -> db.bookDao().recentlyReadNow()
        currentTab.startsWith(TAB_SOURCE_PREFIX) ->
            db.bookDao().bySourceNow(currentTab.removePrefix(TAB_SOURCE_PREFIX).toLong())
        else -> db.bookDao().allSorted()
    }

    /**
     * 标签做成一排胶囊：选中的墨色实底白字，其余白底一像素描边。
     * 书数为 0 的来源不出现（一个来源都没扫到书时，点进去只有空书架）。
     * 本数写在列表上方的分段标题里，胶囊上不再重复。
     */
    private fun renderTabs() {
        binding.tabRow.removeAllViews()

        val tabs = ArrayList<Triple<String, String, Int>>()
        tabs += Triple(TAB_ALL, getString(R.string.tab_all), tabCounts[TAB_ALL] ?: 0)
        tabs += Triple(TAB_RECENT, getString(R.string.tab_recent), tabCounts[TAB_RECENT] ?: 0)
        allSources.forEach { source ->
            val key = "$TAB_SOURCE_PREFIX${source.id}"
            tabs += Triple(key, source.label, tabCounts[key] ?: 0)
        }

        for ((key, label, count) in tabs) {
            if (key.startsWith(TAB_SOURCE_PREFIX) && count == 0) continue
            binding.tabRow.addView(chip(key, label))
        }

        // 分段标题跟着当前标签走：全部漫画 / 最近阅读 / 来源名
        val current = tabs.firstOrNull { it.first == currentTab }
        binding.sectionTitle.text = when {
            currentTab == TAB_ALL -> getString(R.string.shelf_section_all)
            current != null -> current.second
            else -> getString(R.string.shelf_section_all)
        }
        binding.sectionCount.text =
            getString(R.string.shelf_section_count, tabCounts[currentTab] ?: 0)

        binding.shelfCount.text =
            getString(R.string.shelf_count_meta, tabCounts[TAB_ALL] ?: 0)
    }

    private fun chip(key: String, label: String): TextView {
        val selected = key == currentTab
        return TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(getColor(if (selected) android.R.color.white else R.color.shell_ink_muted))
            background = getDrawable(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            isClickable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener {
                if (currentTab != key) {
                    currentTab = key
                    refresh()
                }
            }
        }
    }

    /**
     * 封面按需生成：只有滚到屏幕上、又没有缓存的那几本才去解一次。
     *
     * 早先是把当前标签下的书全量跑一遍，一千多本时等于一口气开一千多个封面——
     * 每个封面都要连远端、建连接，NAS 直接被问挂，应用也会被系统杀掉。
     */
    private fun listenerFor(
        progress: ScanProgressDialog,
        name: String,
    ) = object : LibraryScanner.Listener {
        override fun onListing() = progress.showListing(name)
        override fun onProgress(done: Int, total: Int) = progress.progress(done, total)
    }

    private fun requestCover(bookId: Long) {
        if (queuedCovers.size > MAX_COVER_QUEUE) return
        if (thumbs.has(bookId) || !queuedCovers.add(bookId)) return
        coverQueue.trySend(bookId)
    }

    /**
     * 只要眼前这一屏，外加往后一屏的预取；再远的等滚到再说。
     * 一千多本的书库靠这个把远端开销压到「用户看得见的那点」。
     */
    private fun requestVisibleCovers() {
        val lm = binding.grid.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return
        val screen = (last - first + 1).coerceAtLeast(lm.spanCount)
        val until = (last + screen).coerceAtMost(adapter.itemCount - 1)
        for (i in first..until) {
            requestCover(adapter.bookIdAt(i) ?: continue)
        }
    }

    private fun startCoverWorker() {
        lifecycleScope.launch {
            for (bookId in coverQueue) {
                try {
                    if (!thumbs.has(bookId)) {
                        val book = db.bookDao().get(bookId) ?: continue
                        val src = allSources.firstOrNull { it.id == book.sourceId } ?: continue
                        val chapters = db.chapterDao().forBook(bookId)
                        withContext(Dispatchers.IO) {
                            thumbs.generate(book, src, chapters, opener)
                        }
                    }
                    val width = if (coverWidth > 0) coverWidth else dp(112)
                    val bitmap = withContext(Dispatchers.IO) {
                        thumbs.load(bookId, width, width * 4 / 3)
                    }
                    if (bitmap != null) adapter.showCover(bookId, bitmap)
                } catch (e: Exception) {
                    // 单本失败不影响别的；缓存目录被清掉时下次滚到再试
                    android.util.Log.d("Cover", "封面生成失败 $bookId", e)
                } finally {
                    queuedCovers.remove(bookId)
                }
            }
        }
    }

    // ── 操作 ───────────────────────────────────────────────

    private fun scanFolder(uri: Uri) {
        val progress = ScanProgressDialog(this)
        lifecycleScope.launch {
            status(getString(R.string.status_scanning))
            try {
                val id = sources.addLocalFolder(uri, null)
                val source: SourceEntity? = db.sourceDao().get(id)
                if (source == null) {
                    status(getString(R.string.status_source_missing))
                    return@launch
                }
                progress.showListing(source.label)
                val r = scanner.scan(source, listenerFor(progress, source.label))
                status(getString(R.string.status_scan_done, source.label, r.found, r.added, r.updated))
                currentTab = TAB_ALL
                refresh()
            } catch (e: Exception) {
                status(getString(R.string.status_scan_failed, e.message ?: ""))
            } finally {
                progress.dismiss()
            }
        }
    }

    /**
     * 点书：默认进详情页；用户勾了「跳过详情页」就直接接着读。
     */
    private fun openBook(book: BookEntity) {
        val intent = if (prefs.skipDetail) {
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_BOOK_ID, book.id)
        } else {
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_BOOK_ID, book.id)
        }
        startActivity(intent)
    }

    /**
     * 列数按屏宽算：手机上 3 列，平板上一行能放 6 本。
     * 封面解码宽度也跟着走，免得大屏按老人的尺寸解码发虚、或小屏解太大白占内存。
     */
    private fun applyGridMetrics() {
        val available = resources.displayMetrics.widthPixels - dp(16)
        val minCell = (ShelfGrid.MIN_CELL_DP * resources.displayMetrics.density).toInt()
        val columns = ShelfGrid.columns(available, minCell)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        coverWidth = ShelfGrid.cellWidth(available, columns) - dp(12)
        adapter.setCoverWidth(coverWidth)
    }

    private fun status(text: String) {
        binding.status.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAB_ALL = "all"
        const val TAB_RECENT = "recent"
        const val TAB_SOURCE_PREFIX = "source:"
    }
}
