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
 * 书架列表的头：继续阅读卡 + 书源胶囊 + 排序按钮。
 *
 * 它是一片「只有一项」的适配器，拼在网格前面（`ConcatAdapter`），
 * 这样滚动时跟着列表一起走，不会像原来那样钉在屏幕上半部分吃掉半屏高度。
 */
class ShelfTopAdapter(
    private val onResumeClick: () -> Unit,
    private val onSortClick: () -> Unit,
    private val onTabClick: (String) -> Unit,
) : RecyclerView.Adapter<ShelfTopAdapter.Holder>() {

    /** 继续阅读卡要显示的内容；[resume] 为 null 时整张卡收起 */
    class Resume(
        val title: String,
        val meta: String,
        val percent: Int,
        val action: String,
    )

    class Tab(val key: String, val label: String, val selected: Boolean)

    private var resume: Resume? = null
    private var tabs: List<Tab> = emptyList()

    fun update(resume: Resume?, tabs: List<Tab>) {
        val sameResume = this.resume == resume
        val sameTabs = this.tabs == tabs
        this.resume = resume
        this.tabs = tabs
        if (sameResume && sameTabs) return
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
        val tabRow: LinearLayout = view.findViewById(R.id.tab_row)
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

        // 胶囊每次重建：数量少（标签数 = 2 + 来源数），重建比做 diff 便宜
        holder.tabRow.removeAllViews()
        for (tab in tabs) {
            holder.tabRow.addView(chip(holder.itemView, tab))
        }
    }

    private fun chip(parent: View, tab: Tab): TextView {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return TextView(context).apply {
            text = tab.label
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(
                context.getColor(
                    if (tab.selected) android.R.color.white else R.color.shell_ink_muted
                )
            )
            background = context.getDrawable(
                if (tab.selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onTabClick(tab.key) }
        }
    }
}
