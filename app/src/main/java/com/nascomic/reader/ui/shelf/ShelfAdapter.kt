package com.nascomic.reader.ui.shelf

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.ui.widget.AspectImageView

/** 书架网格。封面缺失时留灰底，不阻塞列表。 */
class ShelfAdapter(
    private val thumbnails: ThumbnailStore,
    private val onClick: (BookEntity) -> Unit,
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var books: List<BookEntity> = emptyList()
    private var progress: Map<Long, ProgressEntity> = emptyMap()
    private var coverWidthPx: Int = 0

    fun submit(books: List<BookEntity>, progress: Map<Long, ProgressEntity>) {
        this.books = books
        this.progress = progress
        notifyDataSetChanged()
    }

    /** 由外部按列宽告知封面尺寸，用于降采样解码 */
    fun setCoverWidth(px: Int) {
        coverWidthPx = px
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: AspectImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val progressText: TextView = view.findViewById(R.id.progress_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = books.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = books[position]
        holder.title.text = book.title

        val p = progress[book.id]
        val hasProgress = p != null && book.pageTotal > 0
        if (hasProgress) {
            val current = (p!!.pageGlobal + 1).coerceAtMost(book.pageTotal)
            holder.progress.visibility = View.VISIBLE
            holder.progress.max = book.pageTotal
            holder.progress.progress = current
            holder.progressText.visibility = View.VISIBLE
            holder.progressText.text = "$current/${book.pageTotal}"
        } else {
            holder.progress.visibility = View.GONE
            holder.progressText.visibility = View.GONE
        }

        val w = if (coverWidthPx > 0) coverWidthPx else holder.cover.width
        val bitmap = if (w > 0) thumbnails.load(book.id, w, w * 4 / 3) else null
        if (bitmap != null) {
            holder.cover.setImageBitmap(bitmap)
        } else {
            holder.cover.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onClick(book) }
    }
}
