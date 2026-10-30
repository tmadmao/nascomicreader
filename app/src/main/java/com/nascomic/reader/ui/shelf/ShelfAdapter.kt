package com.nascomic.reader.ui.shelf

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.ui.widget.AspectImageView
import com.nascomic.reader.ui.widget.CoverProgress

/**
 * 书架网格。封面缺失时留灰底，不阻塞列表。
 *
 * 封面解码走 [CoverBitmaps]：`onBindViewHolder` 只从内存缓存取图，
 * 取不到就排个后台任务，解完再回主线程填——主线程不读盘。
 */
class ShelfAdapter(
    private val covers: CoverBitmaps,
    private val onClick: (BookEntity) -> Unit,
    /** 长按一本书：换封面、看详情这些「针对这一本」的动作 */
    private val onLongClick: (BookEntity) -> Unit = {},
    /** 封面还没生成时叫一声，由外面排队慢慢生成——一千多本一起生成会把 NAS 连接打满 */
    private val onCoverNeeded: (Long) -> Unit = {},
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var books: List<BookEntity> = emptyList()
    private var progress: Map<Long, ProgressEntity> = emptyMap()

    /** 当前真正在屏幕上的格子：封面生成好时只喂给它们 */
    private val bound = HashMap<Long, Holder>()

    /**
     * 只把真变了的那几行刷一遍。
     *
     * 早先是 `notifyDataSetChanged()`：搜索时每敲一个字都重绑整屏，
     * 一千多本的书库上能明显看出掉帧，而且封面要重算一遍。
     */
    fun submit(newBooks: List<BookEntity>, newProgress: Map<Long, ProgressEntity>) {
        val oldBooks = books
        val oldProgress = progress
        if (oldBooks === newBooks && oldProgress == newProgress) return
        books = newBooks
        progress = newProgress

        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldBooks.size
                override fun getNewListSize(): Int = newBooks.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldBooks[oldPos].id == newBooks[newPos].id

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val before = oldBooks[oldPos]
                    val after = newBooks[newPos]
                    return before == after && oldProgress[before.id] == newProgress[after.id]
                }
            },
            // 不做「移动」检测：排序切换时整表换序，检测移动的代价是平方级的
            false,
        )
        diff.dispatchUpdatesTo(this)
    }

    /** 封面尺寸变了（转屏、改列数）：让缓存按新尺寸重解 */
    fun setCoverSize(width: Int, height: Int) {
        covers.setTarget(width, height)
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

        holder.cover.tag = book.id
        bound[book.id] = holder

        val bitmap = covers.cached(book.id)
        if (bitmap != null) {
            holder.cover.setImageBitmap(bitmap)
        } else {
            holder.cover.setImageDrawable(null)
            // 文件在就排队解一次；真的没有，外面那条生成队列会补上，生成好再回调到这里
            covers.load(book.id) { id, loaded -> showCover(id, loaded) }
            onCoverNeeded(book.id)
        }

        holder.itemView.setOnClickListener { onClick(book) }
        holder.itemView.setOnLongClickListener {
            onLongClick(book)
            true
        }
    }

    /** 第 position 本是哪一本；越界给 null */
    fun bookIdAt(position: Int): Long? = books.getOrNull(position)?.id

    /** 换过封面之后：把这一本旧的缩略图从内存里丢掉，免得又画回旧图 */
    fun invalidateCover(bookId: Long) {
        covers.invalidate(bookId)
    }

    /** 封面生成好了：还在显示这一本的格子直接换图，不整表刷新 */
    fun showCover(bookId: Long, bitmap: android.graphics.Bitmap) {
        covers.put(bookId, bitmap)
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
