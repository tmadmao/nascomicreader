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
import androidx.core.widget.doAfterTextChanged
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
import com.nascomic.reader.ui.shelf.CoverBitmaps
import com.nascomic.reader.ui.shelf.ShelfAdapter
import com.nascomic.reader.ui.shelf.ShelfGrid
import com.nascomic.reader.ui.widget.ScanProgressDialog
import com.nascomic.reader.ui.widget.padBySystemBars
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
    private var currentTab: String = TAB_ALL
    private var allSources: List<SourceEntity> = emptyList()

    /** 各标签下的书数，随 refresh 一起算好 */
    private var tabCounts: Map<String, Int> = emptyMap()

    /** 封面生成队列：串行，慢慢来；同一本只排一次 */
    private val coverQueue = Channel<Long>(Channel.UNLIMITED)
    private val queuedCovers = HashSet<Long>()
    private val MAX_COVER_QUEUE = 60
    private var coverWidth = 0

    /** 搜索框里的关键字。本地过滤，不落库也不跨页保存 */
    private var shelfQuery = ""

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
            onClick = { openBook(it) },
            onLongClick = { showBookMenu(it) },
            onCoverNeeded = { id -> requestCover(id) },
        )
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

        // 搜书名：敲字即筛，120ms 防抖，免得一千多本时每敲一个键都查一次库
        binding.searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            if (query == shelfQuery) return@doAfterTextChanged
            shelfQuery = query
            searchDebounce.removeCallbacks(searchTick)
            searchDebounce.postDelayed(searchTick, 120)
        }
        binding.sortButton.setOnClickListener { askSort() }

        startCoverWorker()
        sweepCaches()
        refresh()
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
            val filtering = shelfQuery.isNotBlank()
            // 空书架的引导与「没搜到」是两件事，不能拿前者冒充后者
            binding.empty.visibility = if (empty && !filtering) View.VISIBLE else View.GONE
            binding.searchEmpty.visibility = if (empty && filtering) View.VISIBLE else View.GONE
            binding.grid.visibility = if (empty) View.GONE else View.VISIBLE
            // 一本书都没有时，搜索框没有意义
            binding.searchRow.visibility = if (allBooks.isEmpty()) View.GONE else View.VISIBLE
            // 空书架时不再报「全部漫画 0 本」——上面那块引导已经把话说清了
            binding.sectionTitle.visibility = if (empty) View.GONE else View.VISIBLE
            binding.sectionCount.visibility = if (empty) View.GONE else View.VISIBLE
            // 计数跟着当前列表走：搜索时是筛剩下的本数，不是全量
            binding.sectionCount.text = getString(R.string.shelf_section_count, books.size)

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
        if (latest == null || book == null || currentTab == TAB_RECENT || book.pageTotal <= 0) {
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

    private suspend fun queryBooks(): List<BookEntity> {
        val base = when {
            currentTab == TAB_RECENT -> db.bookDao().recentlyReadNow()
            currentTab.startsWith(TAB_SOURCE_PREFIX) ->
                db.bookDao().bySourceNow(currentTab.removePrefix(TAB_SOURCE_PREFIX).toLong())
            else -> db.bookDao().allSorted()
        }
        // 「最近阅读」标签本身按读过的时间排，排序档不覆盖它——否则这个标签就没意义了
        if (currentTab == TAB_RECENT) return filterByQuery(base)
        val sorted = when (AppPrefs.ShelfSort.of(prefs.shelfSort)) {
            AppPrefs.ShelfSort.TITLE_ASC -> base.sortedBy { it.titleSort }
            AppPrefs.ShelfSort.TITLE_DESC -> base.sortedByDescending { it.titleSort }
            AppPrefs.ShelfSort.RECENT ->
                base.sortedWith(compareByDescending<BookEntity> { it.addedAt }.thenByDescending { it.id })
        }
        return filterByQuery(sorted)
    }

    /** 书名过滤，忽略大小写；空关键字就是原样返回 */
    private fun filterByQuery(list: List<BookEntity>): List<BookEntity> {
        val q = shelfQuery.trim()
        if (q.isEmpty()) return list
        return list.filter { it.title.contains(q, ignoreCase = true) }
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

    /**
     * 长按一本书：重命名 / 换封面 / 从书库删除。
     *
     * 三个动作都只动「这个 App 里的这本书」——改的是库里的名字、封面页号与记录，
     * 硬盘或 NAS 上的文件一个字节都不碰。所以措辞上写「从书库删除」。
     */
    private fun showBookMenu(book: BookEntity) {
        val items = arrayOf(
            getString(R.string.shelf_menu_rename),
            getString(R.string.shelf_change_cover),
            getString(R.string.shelf_menu_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameBook(book)
                    1 -> showChangeCover(book)
                    2 -> confirmDeleteBook(book)
                }
            }
            .show()
    }

    /** 只改库里的显示名：标题与排序键一起更新，书架立刻重排 */
    private fun showRenameBook(book: BookEntity) {
        val input = android.widget.EditText(this).apply {
            setText(book.title)
            setSelection(text.length)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.shelf_rename_hint)
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_rename_title)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.shelf_rename_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    db.bookDao().updateTitle(
                        book.id, name, com.nascomic.reader.core.text.NaturalSort.key(name)
                    )
                    refresh()
                    Toast.makeText(this@MainActivity, R.string.shelf_rename_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 只从库里删：进度、书签、历史随外键级联走，磁盘上的源文件留着 */
    private fun confirmDeleteBook(book: BookEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_delete_title)
            .setMessage(getString(R.string.shelf_delete_message, book.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.shelf_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    db.bookDao().delete(book.id)
                    withContext(Dispatchers.IO) {
                        // 这本书的页缓存与缩略图也一并清掉，不然没人再用它们
                        com.nascomic.reader.ui.reader.DiskPageCache(this@MainActivity)
                            .clearBook(book.id)
                        thumbs.invalidate(book.id)
                    }
                    adapter.invalidateCover(book.id)
                    refresh()
                    Toast.makeText(
                        this@MainActivity, R.string.shelf_delete_done, Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    /**
     * 换封面：长按书架上的这一本，指定用它的第几张图。
     *
     * 这事只能按书做——同一套漫画里每本的第一张未必都是封面（很多是扉页），
     * 所以不放在设置里做全局默认。
     */
    private fun showChangeCover(book: BookEntity) {
        val pages = book.pageTotal
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.shelf_cover_input_hint)
            setText((book.coverPage + 1).toString())
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.shelf_cover_hint, pages.coerceAtLeast(1))
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shelf_cover_title, book.title))
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                val upper = if (pages > 0) pages else Int.MAX_VALUE
                if (n == null || n < 1 || n > upper) {
                    Toast.makeText(
                        this,
                        getString(R.string.shelf_cover_invalid, minOf(upper, pages.coerceAtLeast(1))),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                applyCover(book, n - 1)
            }
            .show()
    }

    /** 写回取图页号，删掉旧缩略图，重新生成一次再喂给书架 */
    private fun applyCover(book: BookEntity, page: Int) {
        lifecycleScope.launch {
            db.bookDao().updateCoverPage(book.id, page)
            val updated = book.copy(coverPage = page)
            val src = allSources.firstOrNull { it.id == book.sourceId }
            val chapters = db.chapterDao().forBook(book.id)
            adapter.invalidateCover(book.id)
            val file = if (src == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { thumbs.generate(updated, src, chapters, opener) }.getOrNull()
                }
            }
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.shelf_cover_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val width = if (coverWidth > 0) coverWidth else dp(112)
            val bitmap = withContext(Dispatchers.IO) { thumbs.load(book.id, width, width * 4 / 3) }
            if (bitmap != null) adapter.showCover(book.id, bitmap)
            Toast.makeText(this@MainActivity, R.string.shelf_cover_done, Toast.LENGTH_SHORT).show()
        }
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
        // 设置里定死列数就照它来，否则按屏宽自动
        val fixed = prefs.shelfColumns
        val columns = if (fixed > 0) fixed else ShelfGrid.columns(available, minCell)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        coverWidth = ShelfGrid.cellWidth(available, columns) - dp(12)
        adapter.setCoverSize(coverWidth, coverWidth * 4 / 3)
    }

    private fun status(text: String) {
        binding.status.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** 封面生成并发数：2 条够把「等网络」的时间藏起来 */
        private const val COVER_WORKERS = 2

        const val TAB_ALL = "all"
        const val TAB_RECENT = "recent"
        const val TAB_SOURCE_PREFIX = "source:"
    }
}
