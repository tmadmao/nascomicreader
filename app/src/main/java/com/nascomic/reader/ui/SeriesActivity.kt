package com.nascomic.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySeriesListBinding
import com.nascomic.reader.library.SeriesRepository
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.ui.shelf.CoverBitmaps
import com.nascomic.reader.ui.shelf.SeriesCardAdapter
import com.nascomic.reader.ui.shelf.SeriesCover
import com.nascomic.reader.ui.shelf.ShelfGrid
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作品系列：与书架同款的网格。
 *
 * 一张卡就是一个系列，封面取首卷、副标题报卷数；点进去是该系列的详情页
 * （骨架与书籍详情一致，目录列各分卷）。
 */
class SeriesActivity : AppCompatActivity() {

    companion object {
        /** 从作者页进来时只显示这位作者的系列 */
        const val EXTRA_AUTHOR = "author"
    }

    private lateinit var binding: ActivitySeriesListBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val repo by lazy { SeriesRepository(db) }
    private val prefs by lazy { AppPrefs(this) }
    private val thumbs by lazy { ThumbnailStore(this) }
    private val covers by lazy { CoverBitmaps(thumbs) }
    private val seriesCover by lazy { SeriesCover(db, thumbs, BookOpener(this)) }

    private lateinit var adapter: SeriesCardAdapter
    private var coverWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        adapter = SeriesCardAdapter(
            onClick = { seriesId -> openSeries(seriesId) },
            onCoverNeeded = { card, image -> loadCover(card, image) },
        )
        applyGridMetrics()
        binding.grid.adapter = adapter
        binding.back.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun applyGridMetrics() {
        val available = resources.displayMetrics.widthPixels - dp(16)
        val minCell = (ShelfGrid.MIN_CELL_DP * resources.displayMetrics.density).toInt()
        val fixed = prefs.shelfColumns
        val columns = if (fixed > 0) fixed else ShelfGrid.columns(available, minCell)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        coverWidth = ShelfGrid.cellWidth(available, columns) - dp(12)
        covers.setTarget(coverWidth, coverWidth * 4 / 3)
    }

    private fun load() {
        lifecycleScope.launch {
            // 从作者页进来就只看这位作者的系列
            val who = intent.getStringExtra(EXTRA_AUTHOR)
            val series = if (who.isNullOrEmpty()) repo.all() else db.bookDao().seriesOfAuthor(who)
            val cards = series.map { entity ->
                val volumes = repo.volumes(entity.id)
                SeriesCardAdapter.Card(
                    seriesId = entity.id,
                    name = entity.name,
                    volumeCount = volumes.size,
                    coverBookId = volumes.firstOrNull()?.id,
                )
            }
            adapter.submit(cards)
            binding.count.text = getString(R.string.shelf_section_count, cards.size)

            val empty = cards.isEmpty()
            binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.grid.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /** 系列封面 = 首卷封面；首卷还没缩略图就现生成一次，别让整页灰着 */
    private fun loadCover(card: SeriesCardAdapter.Card, image: ImageView) {
        val bookId = card.coverBookId ?: return
        covers.cached(bookId)?.let {
            image.setImageBitmap(it)
            return
        }
        lifecycleScope.launch {
            val width = if (coverWidth > 0) coverWidth else dp(112)
            val bitmap = seriesCover.load(bookId, width)
            if (bitmap != null) {
                covers.put(bookId, bitmap)
                adapter.showCover(card.seriesId, bitmap)
            }
        }
    }

    private fun openSeries(seriesId: Long) {
        startActivity(
            Intent(this, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

}
