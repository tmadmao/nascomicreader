package com.nascomic.reader.ui.shelf

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ItemBookBinding

/**
 * 系列卡：复用书籍卡片的版式，标题换成系列名、进度条收起来。
 *
 * 「作品系列」页与书架搜索（范围选「系列」时）共用这一份，两处长得一样。
 */
class SeriesCardAdapter(
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
        bound[seriesId]?.binding?.cover?.setImageBitmap(bitmap)
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
