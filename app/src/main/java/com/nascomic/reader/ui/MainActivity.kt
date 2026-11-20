package com.nascomic.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
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
import com.nascomic.reader.ui.shelf.CoverBitmaps
import com.nascomic.reader.ui.shelf.ShelfAdapter
import com.nascomic.reader.ui.shelf.ShelfGrid
import com.nascomic.reader.ui.update.UpdateDialogs
import com.nascomic.reader.ui.widget.ScanProgressDialog
import com.nascomic.reader.ui.widget.padBySystemBars
import com.nascomic.reader.update.UpdateCenter
import com.nascomic.reader.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架。顶部「书库」+ 标签 chip + 「接着读」卡 + 封面网格。
 * 列数按屏宽算（[ShelfGrid]），设置里也能定死；搜书名在本地即时过滤，排序三档记在偏好里。
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
    private lateinit var header: com.nascomic.reader.ui.shelf.ShelfTopAdapter
    private lateinit var concat: ConcatAdapter
    private lateinit var seriesAdapter: com.nascomic.reader.ui.shelf.SeriesCardAdapter
    private val seriesCovers by lazy { com.nascomic.reader.ui.shelf.CoverBitmaps(thumbs) }
    private val seriesRepo by lazy { com.nascomic.reader.library.SeriesRepository(db) }

    /** 一本书上的动作与「加入系列」：详情页用的是同一份实现，不各写一套 */
    private val bookActions by lazy {
        com.nascomic.reader.ui.shelf.BookActions(
            activity = this,
            db = db,
            thumbs = thumbs,
            opener = opener,
            sourceOf = { id -> allSources.firstOrNull { it.id == id } },
            onCoverReady = { id, bitmap -> adapter.showCover(id, bitmap) },
            onChanged = { refresh() },
            coverWidth = { coverWidth },
        )
    }
    private val seriesActions by lazy {
        com.nascomic.reader.ui.shelf.SeriesActions(this, db) { refresh() }
    }
    private var sourceFilter: String = com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES
    private var allSources: List<SourceEntity> = emptyList()

    /** 各来源下的书数，随 refresh 一起算好 */
    private var tabCounts: Map<String, Int> = emptyMap()

    /** 封面生成队列：串行，慢慢来；同一本只排一次 */
    private val coverQueue = Channel<Long>(Channel.UNLIMITED)
    private val queuedCovers = HashSet<Long>()
    private val MAX_COVER_QUEUE = 60
    private var coverWidth = 0

    /** 搜索框里的关键字。本地过滤，不落库也不跨页保存 */
    private var shelfQuery = ""

    /** 搜索范围：0 = 全部（默认），1 = 作品系列，2 = 作家 */
    private var scope = 0

    /** 从作家列表进来时的作者筛选；null = 不按作者筛 */
    private var authorFilter: String? = null

    /** 从「未知作者」进来：只显示没写作者的书 */
    private var authorUnknown = false

    /** 这位作者名下涉及的系列数，用在作者页那条「该作者的系列」入口上 */
    private var authorSeriesCount = 0

    /** 继续阅读卡指向的那本；卡不在时为空 */
    private var resumeBook: BookEntity? = null

    /** 多选：开着时点书是勾选，不是打开 */
    private var selecting = false
    private val selectedIds = LinkedHashSet<Long>()

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) scanFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15 起强制边到边：自己让开状态栏与导航条
        binding.root.padBySystemBars()

        adapter = ShelfAdapter(
            CoverBitmaps(thumbs),
            onClick = { book ->
                if (selecting) toggleSelection(book.id) else openBook(book)
            },
            onLongClick = { showBookMenu(it) },
            onCoverNeeded = { id -> requestCover(id) },
        )
        header = com.nascomic.reader.ui.shelf.ShelfTopAdapter(
            onResumeClick = { resumeBook?.let { openBook(it) } },
            onSortClick = { askSort() },
            onRecentClick = { startActivity(Intent(this, RecentActivity::class.java)) },
            onSeriesClick = { startActivity(Intent(this, SeriesActivity::class.java)) },
            onAuthorClick = {
                startActivity(Intent(this, com.nascomic.reader.ui.author.AuthorListActivity::class.java))
            },
            onAuthorClear = {
                authorFilter = null
                authorUnknown = false
                authorSeriesCount = 0
                refresh()
            },
            onAuthorSeries = {
                val who = authorFilter
                if (who != null) {
                    startActivity(
                        Intent(this, SeriesActivity::class.java)
                            .putExtra(SeriesActivity.EXTRA_AUTHOR, who)
                    )
                }
            },
            onSourceClick = { key ->
                if (sourceFilter != key) {
                    sourceFilter = key
                    refresh()
                }
            },
        )
        concat = ConcatAdapter(header, adapter)
        binding.grid.adapter = concat
        seriesAdapter = com.nascomic.reader.ui.shelf.SeriesCardAdapter(
            onClick = { seriesId -> openSeries(seriesId) },
            onCoverNeeded = { card, image -> loadSeriesCover(card, image) },
        )
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

        // 搜书名：敲字即筛，120ms 防抖，免得每敲一个键都查一次库
        authorFilter = intent.getStringExtra(EXTRA_AUTHOR)
        authorUnknown = intent.getBooleanExtra(EXTRA_AUTHOR_UNKNOWN, false)
        recoverInterruptedScansOnce()
        backfillAuthorsOnce()
        binding.searchScope.setOnClickListener { askScope() }
        renderScopeChip()
        binding.searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            if (query == shelfQuery) return@doAfterTextChanged
            shelfQuery = query
            searchDebounce.removeCallbacks(searchTick)
            searchDebounce.postDelayed(searchTick, 120)
        }

        binding.selectCancel.setOnClickListener { exitSelection() }
        binding.selectDelete.setOnClickListener { showBatchActions() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) exitSelection() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        startCoverWorker()
        sweepCaches()
        refresh()

        watchUpdates()
    }

    /**
     * 更新只在这一页订阅：书架是回到前台的第一站，提示在这里出现最不容易被漏掉。
     * 设置页、阅读页都不订阅，免得同一个弹窗在两层界面上同时冒出来。
     */
    private fun watchUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateCenter.state.collect { state ->
                    when (state) {
                        is UpdateState.Available -> UpdateDialogs.showAvailable(
                            this@MainActivity, state.manifest, state.forced,
                        ) { UpdateCenter.consume() }

                        is UpdateState.Ready -> UpdateDialogs.showReady(
                            this@MainActivity, state.versionName,
                        ) { UpdateCenter.consume() }

                        // 下到了但验不过：包已经删了，说清楚是哪一关没过
                        is UpdateState.Broken -> {
                            Toast.makeText(this@MainActivity, state.reason, Toast.LENGTH_LONG).show()
                            UpdateCenter.consume()
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * 收拾两处会自己长大的缓存：删掉的书的封面、以及为打开格式落到本地的副本。
     * 只在进书架时做一次——搜索每敲一个字都扫一遍目录不值当。
     */
    private fun sweepCaches() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    thumbs.prune(db.bookDao().ids().toHashSet())
                    com.nascomic.reader.format.LocalCopies.trim(this@MainActivity)
                    // 换过键格式的旧页永远不会命中，顺手清掉
                    com.nascomic.reader.ui.reader.DiskPageCache(this@MainActivity).purgeLegacy()
                }
            }
        }
    }

    private val searchDebounce = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchTick = Runnable { refresh() }

    /** 排序三档换一种排法，点哪个立即生效并记住 */
    private fun askSort() {
        val labels = arrayOf(
            getString(R.string.shelf_sort_recent),
            getString(R.string.shelf_sort_title_asc),
            getString(R.string.shelf_sort_title_desc),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_sort)
            .setSingleChoiceItems(labels, prefs.shelfSort) { dialog, which ->
                prefs.shelfSort = which
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        // 已验过还没装的包，回到前台就重新摆出来。放在 onResume 而不是 onCreate：
        // 首次安装会被系统拉去「安装未知应用」授权，授权完回来还得能接着装
        UpdateCenter.restoreReady(this)
        // 距上次检查超过六小时才会真的发请求，这里每次回前台都能调
        UpdateCenter.checkIfDue(this)
        applySelectExtra()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val who = intent.getStringExtra(EXTRA_AUTHOR)
        val unknown = intent.getBooleanExtra(EXTRA_AUTHOR_UNKNOWN, false)
        if (who != null || unknown) {
            intent.removeExtra(EXTRA_AUTHOR)
            intent.removeExtra(EXTRA_AUTHOR_UNKNOWN)
            authorFilter = who
            authorUnknown = unknown
            refresh()
        }
        applySelectExtra()
    }

    /** 从详情页的「多选」回来时，直接把这一本勾上 */
    private fun applySelectExtra() {
        val id = intent.getLongExtra(EXTRA_SELECT_ID, 0L)
        if (id <= 0L) return
        intent.removeExtra(EXTRA_SELECT_ID)
        binding.root.post { enterSelection(id) }
    }

    // ── 数据 ───────────────────────────────────────────────

    private fun refresh() {
        if (scope == 1) {
            loadSeries()
            return
        }
        binding.grid.adapter = concat
        lifecycleScope.launch {
            allSources = sources.list()

            // 标签上的计数与列表分两次取：计数要全量，列表要按当前标签筛
            val allBooks = db.bookDao().allSorted()
            val progress = db.progressDao().all().associateBy { it.bookId }
            tabCounts = buildMap {
                allSources.forEach { source ->
                    put("$TAB_SOURCE_PREFIX${source.id}", allBooks.count { it.sourceId == source.id })
                }
            }

            val books = queryBooks(allBooks).let { list ->
                when {
                    authorUnknown -> list.filter { it.author.isNullOrBlank() }
                    authorFilter != null -> list.filter { it.author == authorFilter }
                    else -> list
                }
            }
            authorSeriesCount = when {
                authorUnknown -> 0
                authorFilter != null -> db.bookDao().seriesOfAuthor(authorFilter!!).size
                else -> 0
            }
            adapter.submit(books, progress)
            renderHeader(allBooks, progress)

            val empty = books.isEmpty()
            val filtering = shelfQuery.isNotBlank()
            // 空书架的引导与「没搜到」是两件事，不能拿前者冒充后者
            binding.empty.visibility = if (empty && !filtering) View.VISIBLE else View.GONE
            binding.searchEmpty.visibility = if (empty && filtering) View.VISIBLE else View.GONE
            binding.grid.visibility = if (empty) View.GONE else View.VISIBLE
            // 一本书都没有时，搜索框没有意义
            binding.searchRow.visibility = if (allBooks.isEmpty()) View.GONE else View.VISIBLE
            requestVisibleCovers()
        }
    }

    /** 列表头（继续阅读卡 + 书源胶囊 + 排序）与标题右侧的本数 */
    private fun renderHeader(
        allBooks: List<BookEntity>,
        progress: Map<Long, com.nascomic.reader.library.data.ProgressEntity>,
    ) {
        val latest = progress.values.maxByOrNull { it.updatedAt }
        val book = latest?.let { p -> allBooks.firstOrNull { it.id == p.bookId } }
        // 筛了某个来源、而这本书不在该来源里时，卡收起——否则「接着读」与下面的网格不是一套书
        val filteredOut = sourceFilter != com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES &&
            book != null && "$TAB_SOURCE_PREFIX${book.sourceId}" != sourceFilter
        val shown = if (latest == null || book == null || filteredOut || book.pageTotal <= 0) {
            null
        } else {
            book
        }
        resumeBook = shown

        val card = if (shown == null || latest == null) {
            null
        } else {
            com.nascomic.reader.ui.shelf.ShelfTopAdapter.Resume(
                title = shown.displayTitle,
                meta = resumeMeta(shown, latest),
                percent = readPercent(latest.pageGlobal, shown.pageTotal, latest.finished),
                action = if (shown.chapterTotal > 0) {
                    getString(
                        R.string.shelf_position_chapter,
                        (latest.chapterOrdinal + 1).coerceAtMost(shown.chapterTotal),
                        shown.chapterTotal,
                    )
                } else {
                    getString(
                        R.string.shelf_position_pages,
                        (latest.pageGlobal + 1).coerceAtMost(shown.pageTotal),
                        shown.pageTotal,
                    )
                },
            )
        }
        // 按作者筛时先把继续阅读卡收起来：那本大概率不是这位作者的
        val resumeCard = if (authorFilter != null || authorUnknown) null else card
        header.update(
            resumeCard,
            sourceTabs(),
            prefs.seriesEnabled,
            if (authorUnknown) getString(R.string.author_unknown) else authorFilter,
            authorSeriesCount,
        )

        binding.shelfCount.text = getString(R.string.shelf_count_meta, allBooks.size)
    }

    /**
     * 书库行：全部 + 每个来源。
     * 书数为 0 的来源不出现（一个来源都没扫到书时，点进去只有空书架）。
     */
    private fun sourceTabs(): List<com.nascomic.reader.ui.shelf.ShelfTopAdapter.SourceTab> {
        val out = ArrayList<com.nascomic.reader.ui.shelf.ShelfTopAdapter.SourceTab>()
        out += com.nascomic.reader.ui.shelf.ShelfTopAdapter.SourceTab(
            com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES,
            getString(R.string.tab_all),
            sourceFilter == com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES,
        )
        allSources.forEach { source ->
            val key = "$TAB_SOURCE_PREFIX${source.id}"
            val count = tabCounts[key] ?: 0
            if (count == 0 && sourceFilter != key) return@forEach
            out += com.nascomic.reader.ui.shelf.ShelfTopAdapter.SourceTab(
                key,
                source.label,
                sourceFilter == key,
            )
        }
        return out
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

    /**
     * 当前要显示的列表：先按书库筛选，再按关键字过滤，最后按排序档排。
     *
     * 搜索只在这套筛选里进行——界面上不再有第二个「范围」开关。
     */
    private suspend fun queryBooks(allBooks: List<BookEntity>): List<BookEntity> {
        val base = if (sourceFilter == com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES) {
            allBooks
        } else {
            allBooks.filter { "$TAB_SOURCE_PREFIX${it.sourceId}" == sourceFilter }
        }
        val sorted = when (AppPrefs.ShelfSort.of(prefs.shelfSort)) {
            AppPrefs.ShelfSort.TITLE_ASC -> base.sortedBy { it.titleSort }
            AppPrefs.ShelfSort.TITLE_DESC -> base.sortedByDescending { it.titleSort }
            AppPrefs.ShelfSort.RECENT ->
                base.sortedWith(compareByDescending<BookEntity> { it.addedAt }.thenByDescending { it.id })
        }
        return filterByQuery(sorted)
    }

    /** 书名或来源名过滤，忽略大小写；空关键字就是原样返回 */
    private fun filterByQuery(list: List<BookEntity>): List<BookEntity> {
        val q = shelfQuery.trim()
        if (q.isEmpty()) return list
        val sourceNames = allSources.associate { it.id to it.label }
        return list.filter { book ->
            book.displayTitle.contains(q, ignoreCase = true) ||
                sourceNames[book.sourceId]?.contains(q, ignoreCase = true) == true
        }
    }

    /** 搜索范围：当前书库（书名 / 书源）/ 系列（系列名） */
    private fun askScope() {
        val labels = arrayOf(
            getString(R.string.shelf_scope_all),
            getString(R.string.shelf_scope_series),
            getString(R.string.shelf_scope_author),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_scope_title)
            .setSingleChoiceItems(labels, scope) { dialog, which ->
                dialog.dismiss()
                // 「作家」不是筛选方式，而是去作家列表输关键字：书架这边只负责把词带过去
                if (which == 2) {
                    scope = 0
                    renderScopeChip()
                    startActivity(
                        Intent(this, com.nascomic.reader.ui.author.AuthorListActivity::class.java)
                            .putExtra(
                                com.nascomic.reader.ui.author.AuthorListActivity.EXTRA_QUERY,
                                binding.searchInput.text?.toString().orEmpty(),
                            )
                    )
                    return@setSingleChoiceItems
                }
                if (scope == which) return@setSingleChoiceItems
                scope = which
                renderScopeChip()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun renderScopeChip() {
        binding.searchScope.text = getString(
            when (scope) {
                1 -> R.string.shelf_scope_series
                else -> R.string.shelf_scope_all
            }
        )
    }

    /** 范围选「系列」时，网格换成系列卡；关键字只筛系列名 */
    private fun loadSeries() {
        if (!prefs.seriesEnabled) {
            scope = 0
            renderScopeChip()
            refresh()
            return
        }
        binding.grid.adapter = seriesAdapter
        lifecycleScope.launch {
            val q = shelfQuery.trim()
            val cards = seriesRepo.all()
                .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .map { entity ->
                    val volumes = seriesRepo.volumes(entity.id)
                    com.nascomic.reader.ui.shelf.SeriesCardAdapter.Card(
                        seriesId = entity.id,
                        name = entity.name,
                        volumeCount = volumes.size,
                        coverBookId = volumes.firstOrNull()?.id,
                    )
                }
            seriesAdapter.submit(cards)
            val empty = cards.isEmpty()
            binding.empty.visibility = if (empty && q.isEmpty()) View.VISIBLE else View.GONE
            binding.searchEmpty.visibility = if (empty && q.isNotEmpty()) View.VISIBLE else View.GONE
            binding.grid.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun loadSeriesCover(
        card: com.nascomic.reader.ui.shelf.SeriesCardAdapter.Card,
        image: android.widget.ImageView,
    ) {
        val bookId = card.coverBookId ?: return
        val cached = seriesCovers.cached(bookId)
        if (cached != null) {
            image.setImageBitmap(cached)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val width = if (coverWidth > 0) coverWidth else dp(112)
                runCatching { thumbs.load(bookId, width, width * 4 / 3) }.getOrNull()
            }
            if (bitmap != null) {
                seriesCovers.put(bookId, bitmap)
                seriesAdapter.showCover(card.seriesId, bitmap)
            }
        }
    }

    private fun openSeries(seriesId: Long) {
        startActivity(
            Intent(this, com.nascomic.reader.ui.SeriesDetailActivity::class.java)
                .putExtra(com.nascomic.reader.ui.SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
        )
    }

    /**
     * 封面按需生成：只有滚到屏幕上、又没有缓存的那几本才去解一次。
     *
     * 若把当前标签下的书全量跑一遍，等于一口气开所有封面——
     * 每个封面都要连远端、建连接，NAS 会被问挂，应用也会被系统杀掉。
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
     * 靠这个把远端开销压到「用户看得见的那点」，与书库总量无关。
     */
    private fun requestVisibleCovers() {
        val lm = binding.grid.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return
        val screen = (last - first + 1).coerceAtLeast(lm.spanCount)
        val until = (last + screen).coerceAtMost(adapter.itemCount)
        for (i in first..until) {
            // 合并后的第一个位置是列表头，不是书
            if (i <= 0) continue
            requestCover(adapter.bookIdAt(i - 1) ?: continue)
        }
    }

    /**
     * 长按一本书：重命名 / 换封面 / 从书库删除。
     *
     * 三个动作都只动「这个 App 里的这本书」——改的是库里的名字、封面页号与记录，
     * 硬盘或 NAS 上的文件一个字节都不碰。所以措辞上写「从书库删除」。
     */
    private fun showBookMenu(book: BookEntity) {
        val items = mutableListOf(
            getString(R.string.shelf_menu_select),
            getString(R.string.shelf_menu_rename),
            getString(R.string.shelf_change_cover),
            getString(R.string.shelf_menu_delete),
        )
        // 系列功能关着时不摆这一项，菜单里每一条都点得动
        if (prefs.seriesEnabled) items += getString(R.string.series_join_action)
        AlertDialog.Builder(this)
            .setTitle(book.displayTitle)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> enterSelection(book.id)
                    1 -> bookActions.rename(book)
                    2 -> bookActions.changeCover(book)
                    3 -> bookActions.delete(book)
                    else -> seriesActions.open(book)
                }
            }
            .show()
    }

    // ── 多选 ───────────────────────────────────────────────

    private fun enterSelection(firstId: Long) {
        selecting = true
        selectedIds.clear()
        selectedIds += firstId
        renderSelection()
    }

    private fun exitSelection() {
        selecting = false
        selectedIds.clear()
        renderSelection()
    }

    private fun toggleSelection(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        renderSelection()
    }

    private fun renderSelection() {
        adapter.setSelection(selecting, selectedIds)
        binding.selectBar.isVisible = selecting
        binding.selectCount.text = getString(R.string.shelf_selected_count, selectedIds.size)
        binding.selectDelete.isEnabled = selectedIds.isNotEmpty()
        binding.selectDelete.alpha = if (selectedIds.isEmpty()) 0.5f else 1f
    }

    /**
     * 多选之后能做什么。现在两项：从书库删除、加入作品系列。
     * 不给每条都摆一个按钮——底栏只留一个入口，动作多了也不会挤。
     */
    private fun showBatchActions() {
        if (selectedIds.isEmpty()) return
        val items = mutableListOf(getString(R.string.shelf_batch_delete))
        if (prefs.seriesEnabled) items += getString(R.string.shelf_batch_series)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shelf_selected_count, selectedIds.size))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) confirmDeleteSelected() else addSelectedToSeries()
            }
            .show()
    }

    /** 把当前勾选的几本一次加进同一个系列 */
    private fun addSelectedToSeries() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val books = ids.mapNotNull { db.bookDao().get(it) }
            seriesActions.openFor(books)
        }
    }

    /** 批量删除：只删库里的记录，源文件不动 */
    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_delete_title)
            .setMessage(getString(R.string.shelf_delete_batch_message, selectedIds.size))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.shelf_delete_confirm) { _, _ ->
                val doomed = selectedIds.toList()
                lifecycleScope.launch {
                    val cache = com.nascomic.reader.ui.reader.DiskPageCache(this@MainActivity)
                    withContext(Dispatchers.IO) {
                        doomed.forEach { id ->
                            runCatching { db.bookDao().delete(id) }
                            cache.clearBook(id)
                            thumbs.invalidate(id)
                        }
                    }
                    doomed.forEach { adapter.invalidateCover(it) }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.shelf_delete_done),
                        Toast.LENGTH_SHORT,
                    ).show()
                    exitSelection()
                    refresh()
                }
            }
            .show()
    }

    

    

    

    

    private fun startCoverWorker() {
        // 两条并发：一条在等远端连上时，另一条已经在解下一页。
        // 会话用完就还（BookOpener 关书时关连接），两条不会把 NAS 顶到会话上限
        repeat(COVER_WORKERS) {
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
    }

    /**
     * 把上次没走完的扫描收拾掉：进程被杀时状态会停在「扫描中」，界面就一直转。
     * 一个进程只跑一次——同一进程里正在跑的扫描不能被误标成中断。
     */
    private fun recoverInterruptedScansOnce() {
        if (scansRecovered) return
        scansRecovered = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { com.nascomic.reader.library.scan.ScanRecovery.recover(db) }
            }
        }
    }

    /** 老书作者回填：只做一次，之后新书在扫描入库时就写好了 */
    private fun backfillAuthorsOnce() {
        if (prefs.authorBackfilled) return
        lifecycleScope.launch {
            val filled = withContext(Dispatchers.IO) {
                runCatching {
                    com.nascomic.reader.library.author.AuthorBackfill(db).run()
                }.getOrDefault(0)
            }
            if (filled > 0) prefs.authorBackfilled = true
        }
    }

    // ── 操作 ───────────────────────────────────────────────

    private fun scanFolder(uri: Uri) {
        var cancelled = false
        val progress = ScanProgressDialog(this) { cancelled = true }
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
                val r = scanner.scan(source, listenerFor(progress, source.label)) { cancelled }
                status(
                    if (r.keptAll) {
                        getString(
                            R.string.status_scan_kept,
                            source.label,
                            db.bookDao().countBySource(source.id),
                        )
                    } else {
                        getString(R.string.status_scan_done, source.label, r.found, r.added, r.updated)
                    }
                )
                // 扫完顺手跑一次自动归入：只在开关打开时做，且只落高置信的组
                if (prefs.seriesEnabled) {
                    val made = withContext(Dispatchers.IO) {
                        runCatching {
                            com.nascomic.reader.library.series.SeriesAutoGrouper(db).run()
                        }.getOrDefault(0)
                    }
                    if (made > 0) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.series_auto_done, made),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                sourceFilter = com.nascomic.reader.ui.shelf.ShelfTopAdapter.ALL_SOURCES
                refresh()
            } catch (e: LibraryScanner.Cancelled) {
                status(getString(R.string.scan_cancelled))
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
        // 设置里定死列数就照它来，否则按屏宽自动
        val fixed = prefs.shelfColumns
        val columns = if (fixed > 0) fixed else ShelfGrid.columns(available, minCell)
        val lm = GridLayoutManager(this, columns)
        // 第 0 位是列表头（继续阅读卡 + 胶囊），它得占满一行，不能挤进封面网格
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (position == 0) columns else 1
        }
        binding.grid.layoutManager = lm
        coverWidth = ShelfGrid.cellWidth(available, columns) - dp(12)
        adapter.setCoverSize(coverWidth, coverWidth * 4 / 3)
    }

    private fun status(text: String) {
        binding.status.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 封面生成并发数：2 条够把「等网络」的时间藏起来 */
        private const val COVER_WORKERS = 2

        /** 来源筛选的键前缀；「全部来源」用 ShelfTopAdapter.ALL_SOURCES */
        const val TAB_SOURCE_PREFIX = "source:"

        /** 从详情页跳回来时带上要勾选的书 */
        const val EXTRA_SELECT_ID = "select_id"

        /** 从作家列表点进来时带上作者名：书架只显示这位作者的书 */
        const val EXTRA_AUTHOR = "author"

        /** 扫描中断的收拾只跑一次（进程级） */
        private var scansRecovered = false

        /** 点的是「未知作者」：书架只显示没写作者的书 */
        const val EXTRA_AUTHOR_UNKNOWN = "author_unknown"
    }
}
