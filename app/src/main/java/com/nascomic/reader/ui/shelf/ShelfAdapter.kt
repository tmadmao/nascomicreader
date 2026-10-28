package com.nascomic.reader.ui.shelf

import android.content.Context
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
import com.nascomic.reader.ui.widget.SpineProgress

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
        val spine: SpineProgress = view.findViewById(R.id.spine)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
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
        val total = book.pageTotal
        val read = p?.pageGlobal ?: 0
        holder.spine.ratio = if (p != null && total > 0) (read + 1f) / total else 0f
        holder.meta.text = metaOf(holder.itemView.context, book, p)

        val w = if (coverWidthPx > 0) coverWidthPx else holder.cover.width
        val bitmap = if (w > 0) thumbnails.load(book.id, w, w * 4 / 3) else null
        if (bitmap != null) {
            holder.cover.setImageBitmap(bitmap)
        } else {
            holder.cover.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onClick(book) }
    }

    /**
     * 封面下那行小字。漫画按「话」讲，单行本没话就按页；
     * 没读过的写明「未开始」，别让 0% 变成一串零。
     */
    private fun metaOf(context: Context, book: BookEntity, p: ProgressEntity?): String {
        val chaptered = book.chapterTotal > 0
        val ctx = context
        // 页数还没统计出来（第一次进详情页才数）：只写「未开始」，不写 0 页
        if (p == null && book.pageTotal <= 0) return ctx.getString(R.string.shelf_meta_unread)
        return when {
            p == null -> ctx.getString(
                if (chaptered) R.string.shelf_meta_unread_chapter else R.string.shelf_meta_unread_pages,
                if (chaptered) book.chapterTotal else book.pageTotal,
            )
            chaptered -> ctx.getString(
                R.string.shelf_meta_chapter, p.chapterOrdinal + 1, book.chapterTotal
            )
            else -> ctx.getString(
                R.string.shelf_meta_pages,
                (p.pageGlobal + 1).coerceAtMost(book.pageTotal.coerceAtLeast(1)),
                book.pageTotal,
            )
        }
    }
}
