package com.nascomic.reader.ui.shelf

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R

/**
 * 书架列表的头：书架入口 + 继续阅读卡 + 书库分段 + 排序按钮。
 *
 * 它是一片「只有一项」的适配器，拼在网格前面（`ConcatAdapter`），
 * 这样滚动时跟着列表一起走，不占用固定屏高。
 *
 * 三个维度分开：上面两个胶囊是「去别的页面」（最近阅读 / 作品系列），
 * 下面一条槽是「在这个页面里筛来源」，两者形态不同，不会看混。
 */
class ShelfTopAdapter(
    private val onResumeClick: () -> Unit,
    private val onSortClick: () -> Unit,
    /** 最近阅读页 */
    private val onRecentClick: () -> Unit,
    /** 作品系列页；为 null 时不显示这个入口（页面还没接上） */
    private val onSeriesClick: (() -> Unit)?,
    /** 作家列表页 */
    private val onAuthorClick: (() -> Unit)?,
    /** 点掉作者胶囊：回到全部 */
    private val onAuthorClear: (() -> Unit)?,
    /** 来源键：全部为 [ALL_SOURCES] */
    private val onSourceClick: (String) -> Unit,
) : RecyclerView.Adapter<ShelfTopAdapter.Holder>() {

    /** 继续阅读卡要显示的内容；[resume] 为 null 时整张卡收起 */
    class Resume(
        val title: String,
        val meta: String,
        val percent: Int,
        val action: String,
    )

    /** 书库行的一段：全部，或某个来源 */
    class SourceTab(val key: String, val label: String, val selected: Boolean)

    private var resume: Resume? = null
    private var sources: List<SourceTab> = emptyList()
    private var showSeries = false
    private var authorName: String? = null

    fun update(resume: Resume?, sources: List<SourceTab>, showSeries: Boolean, authorName: String? = null) {
        val same = this.resume == resume && this.sources == sources &&
            this.showSeries == showSeries && this.authorName == authorName
        if (same) return
        this.resume = resume
        this.sources = sources
        this.showSeries = showSeries
        this.authorName = authorName
        notifyItemChanged(0)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val resumeCard: View = view.findViewById(R.id.resume_card)
        val resumeTitle: TextView = view.findViewById(R.id.resume_title)
        val resumeMeta: TextView = view.findViewById(R.id.resume_meta)
        val resumePercent: TextView = view.findViewById(R.id.resume_percent)
        val resumeBar: ProgressBar = view.findViewById(R.id.resume_bar)
        val resumeAction: TextView = view.findViewById(R.id.resume_action)
        val resumePlay: View = view.findViewById(R.id.resume_play)
        val entryRecent: View = view.findViewById(R.id.entry_recent)
        val entrySeries: View = view.findViewById(R.id.entry_series)
        val entryAuthor: View = view.findViewById(R.id.entry_author)
        val authorChip: TextView = view.findViewById(R.id.author_chip)
        val sourceRow: LinearLayout = view.findViewById(R.id.source_row)
        val sortButton: View = view.findViewById(R.id.sort_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shelf_top, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = resume
        if (card == null) {
            holder.resumeCard.visibility = View.GONE
        } else {
            holder.resumeCard.visibility = View.VISIBLE
            holder.resumeTitle.text = card.title
            holder.resumeMeta.text = card.meta
            holder.resumePercent.text = holder.itemView.context
                .getString(R.string.shelf_read_percent, card.percent)
            holder.resumeBar.progress = card.percent
            holder.resumeAction.text = card.action
            holder.resumeCard.setOnClickListener { onResumeClick() }
            holder.resumePlay.setOnClickListener { onResumeClick() }
        }

        holder.sortButton.setOnClickListener { onSortClick() }
        holder.entryRecent.setOnClickListener { onRecentClick() }
        val seriesEntry = onSeriesClick
        holder.entrySeries.visibility = if (showSeries && seriesEntry != null) View.VISIBLE else View.GONE
        holder.entrySeries.setOnClickListener { seriesEntry?.invoke() }
        val authorEntry = onAuthorClick
        holder.entryAuthor.visibility = if (authorEntry != null) View.VISIBLE else View.GONE
        holder.entryAuthor.setOnClickListener { authorEntry?.invoke() }

        val who = authorName
        holder.authorChip.visibility = if (who == null) View.GONE else View.VISIBLE
        if (who != null) {
            holder.authorChip.text = holder.itemView.context.getString(R.string.shelf_author_chip, who)
            holder.authorChip.setOnClickListener { onAuthorClear?.invoke() }
        }

        // 段数少（全部 + 来源数），每次重建比做 diff 便宜
        holder.sourceRow.removeAllViews()
        for (tab in sources) {
            holder.sourceRow.addView(segment(holder.itemView, tab))
        }
    }

    private fun segment(parent: View, tab: SourceTab): TextView {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return TextView(context).apply {
            text = tab.label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setTextColor(
                context.getColor(if (tab.selected) R.color.shell_ink else R.color.shell_ink_muted)
            )
            // 选中才有底色；没选中就直接坐在浅米色槽上
            background = if (tab.selected) context.getDrawable(R.drawable.bg_segment_selected) else null
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onSourceClick(tab.key) }
        }
    }

    companion object {
        /** 「全部来源」这一段的键；与具体来源 id 不冲突 */
        const val ALL_SOURCES = "all"
    }
}
