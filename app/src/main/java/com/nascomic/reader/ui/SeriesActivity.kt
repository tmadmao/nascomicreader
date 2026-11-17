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
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySeriesListBinding
import com.nascomic.reader.databinding.ItemBookBinding
import com.nascomic.reader.library.SeriesRepository
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.ui.shelf.CoverBitmaps
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

    private lateinit var binding: ActivitySeriesListBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val repo by lazy { SeriesRepository(db) }
    private val prefs by lazy { AppPrefs(this) }
    private val thumbs by lazy { ThumbnailStore(this) }
    private val covers by lazy { CoverBitmaps(thumbs) }

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
            val series = repo.all()
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

    private fun loadCover(card: SeriesCardAdapter.Card, image: ImageView) {
        val bookId = card.coverBookId ?: return
        val cached = covers.cached(bookId)
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

    /** 系列卡：复用书籍卡片的版式，只把标题换成系列名、进度条收起来 */
    private class SeriesCardAdapter(
        private val onClick: (Long) -> Unit,
        private val onCoverNeeded: (Card, ImageView) -> Unit,
    ) : RecyclerView.Adapter<SeriesCardAdapter.Holder>() {

        class Card(
            val seriesId: Long,
            val name: String,
            val volumeCount: Int,
            val coverBookId: Long?,
        )

        private var cards: List<Card> = emptyList()
        private val bound = HashMap<Long, Holder>()

        fun submit(newCards: List<Card>) {
            cards = newCards
            notifyDataSetChanged()
        }

        fun showCover(seriesId: Long, bitmap: android.graphics.Bitmap) {
            val holder = bound[seriesId] ?: return
            holder.binding.cover.setImageBitmap(bitmap)
        }

        class Holder(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = cards.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = cards[position]
            holder.binding.progress.isVisible = false
            holder.binding.check.isVisible = false
            holder.binding.title.text = card.name
            holder.binding.meta.text = holder.binding.root.context
                .getString(R.string.series_volume_count, card.volumeCount)
            holder.binding.cover.setImageDrawable(null)
            holder.binding.root.setOnClickListener { onClick(card.seriesId) }
            bound[card.seriesId] = holder
            onCoverNeeded(card, holder.binding.cover)
        }

        override fun onViewRecycled(holder: Holder) {
            val id = cards.getOrNull(holder.bindingAdapterPosition)?.seriesId
            if (id != null && bound[id] === holder) bound.remove(id)
            super.onViewRecycled(holder)
        }
    }
}
