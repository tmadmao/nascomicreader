package com.nascomic.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityMainBinding
import com.nascomic.reader.format.BookOpener
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架。当前是能用的第一版：标签页 + 三列封面 + 进度条。
 * 搜索、排序菜单、详情页随后补。
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

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) scanFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ShelfAdapter(thumbs) { openBook(it) }
        binding.grid.layoutManager = GridLayoutManager(this, 3)
        binding.grid.adapter = adapter

        // 三列：扣掉两侧 padding 与格子间距
        val cell = (resources.displayMetrics.widthPixels - dp(16) - dp(12) * 3) / 3
        adapter.setCoverWidth(cell)

        binding.addFolder.setOnClickListener { pickFolder.launch(null) }
        binding.emptyLocal.setOnClickListener { pickFolder.launch(null) }
        binding.emptyRemote.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.rescan.setOnClickListener { rescanAll() }
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
            renderTabs()

            val books = queryBooks()
            val progress = db.progressDao().all().associateBy { it.bookId }
            adapter.submit(books, progress)

            binding.empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.grid.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE

            generateMissingCovers(books)
        }
    }

    private suspend fun queryBooks(): List<BookEntity> = when {
        currentTab == TAB_RECENT -> db.bookDao().recentlyReadNow()
        currentTab.startsWith(TAB_SOURCE_PREFIX) ->
            db.bookDao().bySourceNow(currentTab.removePrefix(TAB_SOURCE_PREFIX).toLong())
        else -> db.bookDao().allSorted()
    }

    private fun renderTabs() {
        binding.tabRow.removeAllViews()
        val tabs = ArrayList<Pair<String, String>>()
        tabs += TAB_ALL to getString(R.string.tab_all)
        tabs += TAB_RECENT to getString(R.string.tab_recent)
        allSources.forEach { tabs += "$TAB_SOURCE_PREFIX${it.id}" to it.label }

        for ((key, label) in tabs) {
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextColor(
                    if (key == currentTab) getColor(R.color.tab_active)
                    else getColor(R.color.tab_inactive)
                )
                setOnClickListener {
                    if (currentTab != key) {
                        currentTab = key
                        refresh()
                    }
                }
            }
            binding.tabRow.addView(tv)
        }
    }

    /**
     * 缺封面的书逐个生成。串行做，避免同时解多本书把内存顶上去；
     * 一轮生成完再整体刷新。
     */
    private fun generateMissingCovers(books: List<BookEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sourcesById = allSources.associateBy { it.id }
            var any = false
            for (book in books) {
                if (thumbs.has(book.id)) continue
                val src = sourcesById[book.sourceId] ?: continue
                val chapters = db.chapterDao().forBook(book.id)
                if (thumbs.generate(book, src, chapters, opener) != null) any = true
            }
            thumbs.prune(books.map { it.id }.toSet())
            if (any) withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
        }
    }

    // ── 操作 ───────────────────────────────────────────────

    private fun scanFolder(uri: Uri) {
        lifecycleScope.launch {
            status(getString(R.string.status_scanning))
            try {
                val id = sources.addLocalFolder(uri, null)
                val source: SourceEntity? = db.sourceDao().get(id)
                if (source == null) {
                    status(getString(R.string.status_source_missing))
                    return@launch
                }
                val r = scanner.scan(source)
                status(getString(R.string.status_scan_done, source.label, r.found, r.added, r.updated))
                currentTab = TAB_ALL
                refresh()
            } catch (e: Exception) {
                status(getString(R.string.status_scan_failed, e.message ?: ""))
            }
        }
    }

    private fun rescanAll() {
        lifecycleScope.launch {
            val all = sources.list()
            if (all.isEmpty()) {
                status(getString(R.string.status_no_source))
                return@launch
            }
            var total = 0
            for (s in all) {
                status(getString(R.string.status_scanning_named, s.label))
                total += runCatching { scanner.scan(s).found }.getOrDefault(0)
            }
            status(getString(R.string.status_rescan_done, all.size, total))
            refresh()
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
