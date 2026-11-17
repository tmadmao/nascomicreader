package com.nascomic.reader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityRecentBinding
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.ui.detail.DetailActivity
import com.nascomic.reader.ui.reader.ReadingActivity
import com.nascomic.reader.ui.shelf.CoverBitmaps
import com.nascomic.reader.ui.shelf.ShelfAdapter
import com.nascomic.reader.ui.shelf.ShelfGrid
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 最近阅读：读过的书按最近打开倒序，独立成一页。
 *
 * 书架页只保留「全部 + 来源」这一套筛选，读过的入口挪到这一页，
 * 所以这里不再有标签、排序与筛选，顺序就是读过的时间。
 */
class RecentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val prefs by lazy { AppPrefs(this) }
    private val thumbs by lazy { ThumbnailStore(this) }
    private val covers by lazy { CoverBitmaps(thumbs) }
    private lateinit var adapter: ShelfAdapter

    /** 正在读缩略图的本数，避免同一本重复排队、也避免一次排太多 */
    private val loading = HashSet<Long>()
    private var coverWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        adapter = ShelfAdapter(
            covers,
            onClick = { book -> openBook(book.id) },
            onCoverNeeded = { id -> loadCover(id) },
        )
        binding.list.adapter = adapter
        binding.back.setOnClickListener { finish() }
        applyGridMetrics()
        load()
    }

    override fun onResume() {
        super.onResume()
        // 从阅读页回来时进度与顺序都可能变了
        load()
    }

    private fun applyGridMetrics() {
        val available = resources.displayMetrics.widthPixels - dp(16)
        val minCell = (ShelfGrid.MIN_CELL_DP * resources.displayMetrics.density).toInt()
        val fixed = prefs.shelfColumns
        val columns = if (fixed > 0) fixed else ShelfGrid.columns(available, minCell)
        binding.list.layoutManager = GridLayoutManager(this, columns)
        coverWidth = ShelfGrid.cellWidth(available, columns) - dp(12)
        adapter.setCoverSize(coverWidth, coverWidth * 4 / 3)
    }

    private fun load() {
        lifecycleScope.launch {
            val books = db.bookDao().recentlyReadNow()
            val progress = db.progressDao().all().associateBy { it.bookId }
            adapter.submit(books, progress)
            binding.count.text = getString(R.string.shelf_section_count, books.size)

            val empty = books.isEmpty()
            binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.list.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /**
     * 只读已经生成好的缩略图：缺了就留灰底。
     * 生成封面要连来源、解页，那是书架页的事，这里不重复开一份队列。
     */
    private fun loadCover(bookId: Long) {
        if (loading.size > MAX_LOADING || !loading.add(bookId)) return
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                val width = if (coverWidth > 0) coverWidth else dp(112)
                runCatching { thumbs.load(bookId, width, width * 4 / 3) }.getOrNull()
            }
            loading.remove(bookId)
            if (bitmap != null) adapter.showCover(bookId, bitmap)
        }
    }

    private fun openBook(bookId: Long) {
        val intent = if (prefs.skipDetail) {
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_BOOK_ID, bookId)
        } else {
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_BOOK_ID, bookId)
        }
        startActivity(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_LOADING = 24
    }
}
