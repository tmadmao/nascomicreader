package com.nascomic.reader.ui.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nascomic.reader.R
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.CancelSignal
import java.util.concurrent.atomic.AtomicReference
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.ui.widget.AppDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从书里挑一页当封面。
 *
 * 全屏弹层、四列网格、按章分段；点一页立刻写库并关窗，不留二次确认。
 * 缩略图滑到哪解到哪，只解到格子大小；滑出屏幕、换页、关窗都会取消还没解完的请求，
 * 解码失败只显示「取不出来」，绝不改动当前封面。
 */
internal class CoverPickerSheet(
    private val activity: AppCompatActivity,
    private val book: BookEntity,
    private val source: SourceEntity,
    private val chapters: List<ChapterEntity>,
    private val opener: BookOpener,
    private val onPick: (Int) -> Unit,
) : BottomSheetDialog(activity) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var opened: BookSource? = null
    private var dismissed = false
    private val opening = CancelSignal()
    private val hostObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dismiss() }
    }

    private val cellWidth: Int
    private val cellHeight: Int

    private val list: RecyclerView
    private val loading: LinearLayout
    private val error: TextView
    private val bookLine: TextView
    private val currentLine: TextView
    private val jumpChip: TextView

    private var adapter: PageAdapter? = null
    private var loader: CoverPageLoader? = null
    private var rows: List<Row> = emptyList()

    init {
        val metrics = activity.resources.displayMetrics
        val gap = dp(9)
        cellWidth = ((metrics.widthPixels - dp(32) - gap * 3) / 4).coerceAtLeast(dp(48))
        cellHeight = (cellWidth * 4 / 3).coerceAtLeast(dp(64))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_top)
        }

        // ── 顶栏：关 / 标题 / 跳到第 N 页 ──────────────────
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        val close = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            setTextColor(activity.getColor(R.color.shell_ink))
            gravity = Gravity.CENTER
            setOnClickListener { dismiss() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.cover_picker_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.shell_ink))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        jumpChip = TextView(activity).apply {
            text = activity.getString(R.string.cover_picker_jump)
            textSize = 11f
            setTextColor(activity.getColor(R.color.shell_ink_muted))
            gravity = Gravity.CENTER
            background = activity.getDrawable(R.drawable.bg_chip_unselected)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { askJump() }
        }
        top.addView(close)
        top.addView(title)
        top.addView(jumpChip)
        root.addView(top)

        bookLine = TextView(activity).apply {
            textSize = 11f
            setTextColor(activity.getColor(R.color.shell_ink_faint))
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(bookLine)

        // ── 网格与两种占位 ─────────────────────────────────
        val content = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        list = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, 4)
            setPadding(dp(16), 0, dp(16), dp(12))
            clipToPadding = false
            isVisible = false

        }
        content.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        loading = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    activity.getColor(R.color.brand_accent)
                )
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.cover_picker_loading)
                textSize = 12f
                setTextColor(activity.getColor(R.color.shell_ink_muted))
                setPadding(0, dp(10), 0, 0)
            })
        }
        content.addView(loading, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        error = TextView(activity).apply {
            text = activity.getString(R.string.cover_picker_failed)
            textSize = 12.5f
            setTextColor(activity.getColor(R.color.shell_ink_muted))
            gravity = Gravity.CENTER
            isVisible = false
        }
        content.addView(error, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        root.addView(content)

        // ── 底栏：恢复默认 + 当前封面 ──────────────────────
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(activity.getColor(R.color.shell_surface))
        }
        val restore = TextView(activity).apply {
            text = activity.getString(R.string.cover_picker_restore)
            textSize = 12f
            setTextColor(activity.getColor(R.color.shell_ink_muted))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                onPick(0)
                dismiss()
            }
        }
        currentLine = TextView(activity).apply {
            textSize = 10.5f
            setTextColor(activity.getColor(R.color.shell_ink_faint))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottom.addView(restore)
        bottom.addView(currentLine)
        root.addView(bottom)

        setContentView(root)

        val height = (metrics.heightPixels * 0.92f).toInt()
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, height
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        setOnShowListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.peekHeight = height
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        bookLine.text = activity.getString(
            R.string.cover_picker_book,
            book.displayTitle,
            book.pageTotal.coerceAtLeast(0),
        )
        currentLine.text = activity.getString(
            R.string.cover_picker_current,
            book.coverPage + 1,
        )

        activity.lifecycle.addObserver(hostObserver)
        setOnDismissListener { release() }
        open()
    }

    private fun open() {
        scope.launch {
            val unclaimed = AtomicReference<BookSource?>()
            val src = try {
                withContext(Dispatchers.IO) {
                    opening.use { token ->
                        runCatching { opener.openForCoverPicker(book, source, chapters, token) }.getOrNull()
                            .also { unclaimed.set(it) }
                    }
                }.also { unclaimed.set(null) }
            } finally {
                unclaimed.getAndSet(null)?.let { cleanupScope.launch { runCatching { it.close() } } }
            }
            if (src == null || src.pageCount <= 0) {
                if (src != null) cleanupScope.launch { runCatching { src.close() } }
                loading.isVisible = false
                error.isVisible = true
                return@launch
            }
            opened = src
            bookLine.text = activity.getString(
                R.string.cover_picker_book,
                book.displayTitle,
                src.pageCount,
            )
            buildRows(src)
            val pageLoader = CoverPageLoader(src, cellWidth, cellHeight)
            loader = pageLoader
            adapter = PageAdapter(pageLoader)
            list.adapter = adapter
            (list.layoutManager as GridLayoutManager).spanSizeLookup =
                object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (rows[position] is Row.Header) 4 else 1
                }
            loading.isVisible = false
            list.isVisible = true
        }
    }

    override fun dismiss() {
        release()
        super.dismiss()
    }

    private fun release() {
        if (dismissed) return
        dismissed = true
        activity.lifecycle.removeObserver(hostObserver)
        opening.cancel()
        scope.cancel()
        loader?.clear()
        loader = null
        opened?.let { src -> cleanupScope.launch { runCatching { src.close() } } }
        opened = null
    }

    // ── 跳页 ───────────────────────────────────────────────

    private fun askJump() {
        val total = opened?.pageCount ?: return
        val input = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = activity.getString(R.string.cover_picker_jump_hint, total)
            setSingleLine()
        }
        AppDialogBuilder(activity)
            .setTitle(R.string.cover_picker_jump_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n == null || n < 1 || n > total) {
                    android.widget.Toast.makeText(
                        activity,
                        activity.getString(R.string.cover_picker_jump_invalid, total),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                scrollToPage(n - 1)
            }
            .show()
    }

    private fun scrollToPage(page: Int) {
        val position = rows.indexOfFirst { it is Row.Page && it.index == page }
        if (position < 0) return
        (list.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(position, dp(4))
    }

    // ── 行数据 ─────────────────────────────────────────────

    private sealed class Row {
        class Header(val ordinal: Int, val title: String, val pages: Int) : Row()
        class Page(val index: Int) : Row()
    }

    private fun buildRows(src: BookSource) {
        val out = ArrayList<Row>(src.pageCount + src.chapters.size)
        src.chapters.forEachIndexed { index, chapter ->
            val start = src.firstPageOfChapter(index)
            out += Row.Header(chapter.ordinal + 1, chapter.title, chapter.pageCount)
            for (k in 0 until chapter.pageCount) out += Row.Page(start + k)
        }
        // 章表为空或合计对不上时，至少把所有页平铺出来，不能一页都看不到
        if (out.none { it is Row.Page }) {
            for (p in 0 until src.pageCount) out += Row.Page(p)
        }
        rows = out
    }

    // ── 解码 ───────────────────────────────────────────────

    // ── 列表 ───────────────────────────────────────────────

    private inner class PageAdapter(private val loader: CoverPageLoader) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_PAGE

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) HeaderHolder(headerView())
            else PageHolder(PageCell(activity), loader)

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Page -> (holder as PageHolder).bind(row.index, row.index == book.coverPage) {
                    onPick(row.index)
                    dismiss()
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            (holder as? PageHolder)?.attach()
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            (holder as? PageHolder)?.detach()
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? PageHolder)?.release()
        }

        private fun headerView(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private inner class HeaderHolder(private val line: LinearLayout) :
        RecyclerView.ViewHolder(line) {
        private val title = TextView(activity).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.shell_ink_muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val count = TextView(activity).apply {
            textSize = 10f
            setTextColor(activity.getColor(R.color.shell_ink_faint))
        }

        init {
            line.addView(title)
            line.addView(count)
        }

        fun bind(row: Row.Header) {
            val name = row.title.trim()
            title.text = if (name.isEmpty()) {
                activity.getString(R.string.cover_picker_chapter_plain, row.ordinal)
            } else {
                activity.getString(R.string.cover_picker_chapter, row.ordinal, name)
            }
            count.text = activity.getString(R.string.cover_picker_pages, row.pages)
        }
    }

    private inner class PageHolder(
        private val cell: PageCell,
        private val loader: CoverPageLoader,
    ) : RecyclerView.ViewHolder(cell) {

        private var job: Job? = null
        private var boundIndex = -1
        private var attached = false

        fun bind(index: Int, selected: Boolean, onTap: () -> Unit) {
            stop()
            boundIndex = index
            cell.bind(index, selected, onTap)
            if (attached) start()
        }

        fun attach() {
            attached = true
            start()
        }

        private fun start() {
            val index = boundIndex
            if (index < 0 || job != null) return
            job = scope.launch {
                val bitmap = loader.load(index)
                if (attached && boundIndex == index) cell.show(bitmap)
            }
        }

        private fun stop() {
            job?.cancel()
            job = null
        }

        fun detach() {
            attached = false
            stop()
        }

        fun release() {
            detach()
            boundIndex = -1
            cell.reset()
        }
    }

    /**
     * 一格缩略图：固定 3:4，页码压在左下，选中时右上角一个橙底白勾。
     * 自绘而不是塞进 XML，是因为高度要跟着网格宽度算。
     */
    private inner class PageCell(context: Context) : FrameLayout(context) {
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        private val number = TextView(context).apply {
            textSize = 9f
            setTextColor(activity.getColor(R.color.shell_surface))
            background = activity.getDrawable(R.drawable.bg_page_no)
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        private val check = TextView(context).apply {
            text = "✓"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.shell_surface))
            background = activity.getDrawable(R.drawable.bg_check_circle)
            isVisible = false
        }
        private val failed = TextView(context).apply {
            text = activity.getString(R.string.cover_picker_failed_cell)
            textSize = 9f
            setTextColor(activity.getColor(R.color.shell_ink_faint))
            isVisible = false
        }

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(number, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(5), 0, 0, dp(5))
            })
            addView(check, LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(4), dp(4), 0)
            })
            addView(failed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(9)
                bottomMargin = dp(6)
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY),
            )
        }

        /** 这一格解出来了才允许点：点一个「取不出来」的格子会把封面改成一个不存在的页 */
        private var ready = false
        private var tap: (() -> Unit)? = null

        init {
            setOnClickListener { if (ready) tap?.invoke() }
        }

        fun bind(index: Int, selected: Boolean, onTap: () -> Unit) {
            setBackgroundResource(
                if (selected) R.drawable.bg_cover_cell_selected else R.drawable.bg_cover_cell
            )
            number.text = (index + 1).toString()
            number.isVisible = true
            check.isVisible = selected
            failed.isVisible = false
            image.setImageDrawable(null)
            ready = false
            tap = onTap
        }

        fun show(bitmap: Bitmap?) {
            if (bitmap == null) {
                ready = false
                image.setImageDrawable(null)
                number.isVisible = false
                failed.isVisible = true
            } else {
                ready = true
                number.isVisible = true
                failed.isVisible = false
                image.setImageBitmap(bitmap)
            }
        }

        fun reset() {
            ready = false
            tap = null
            image.setImageDrawable(null)
            number.isVisible = true
            failed.isVisible = false
            check.isVisible = false
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val TYPE_HEADER = 0
        const val TYPE_PAGE = 1
    }
}
