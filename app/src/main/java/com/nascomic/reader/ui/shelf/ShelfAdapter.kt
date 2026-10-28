package com.nascomic.reader.ui.shelf

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.ui.widget.AspectImageView
import com.nascomic.reader.ui.widget.CoverProgress

/** 书架网格。封面缺失时留灰底，不阻塞列表。 */
class ShelfAdapter(
    private val thumbnails: ThumbnailStore,
    private val onClick: (BookEntity) -> Unit,
    /** 封面还没生成时叫一声，由外面排队慢慢生成——一千多本一起生成会把 NAS 连接打满 */
    private val onCoverNeeded: (Long) -> Unit = {},
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var books: List<BookEntity> = emptyList()
    private var progress: Map<Long, ProgressEntity> = emptyMap()
    private var coverWidthPx: Int = 0
    /** 当前真正在屏幕上的格子：封面生成好时只喂给它们 */
    private val bound = HashMap<Long, Holder>()

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
        val progress: CoverProgress = view.findViewById(R.id.progress)
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
        holder.progress.ratio = if (p != null) readPercent(read, total, p.finished) / 100f else 0f
        holder.meta.text = metaOf(holder.itemView.context, book, p)

        // 封面只解码可见的那几张；没有就先留灰底，把活派给生成队列
        holder.cover.tag = book.id
        bound[book.id] = holder
        val w = if (coverWidthPx > 0) coverWidthPx else holder.cover.width
        val bitmap = if (w > 0) thumbnails.load(book.id, w, w * 4 / 3) else null
        if (bitmap != null) {
            holder.cover.setImageBitmap(bitmap)
        } else {
            holder.cover.setImageDrawable(null)
            onCoverNeeded(book.id)
        }

        holder.itemView.setOnClickListener { onClick(book) }
    }

    /** 第 position 本是哪一本；越界给 null */
    fun bookIdAt(position: Int): Long? = books.getOrNull(position)?.id

    /** 封面生成好了：还在显示这一本的格子直接换图，不整表刷新 */
    fun showCover(bookId: Long, bitmap: android.graphics.Bitmap) {
        val holder = bound[bookId] ?: return
        if (holder.cover.tag == bookId) holder.cover.setImageBitmap(bitmap)
    }

    override fun onViewRecycled(holder: Holder) {
        val id = holder.cover.tag as? Long
        if (id != null && bound[id] === holder) bound.remove(id)
        super.onViewRecycled(holder)
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
            // 读到过书的尾就算读完，话多话少都按全书页数判
            p.finished -> ctx.getString(R.string.progress_finished)
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
