package com.nascomic.reader.ui.history

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivityHistoryBinding
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.RelativeTime
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.ui.detail.DetailActivity
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读历史：按最近打开倒序，点一行回详情页接着看。
 * 长按去掉这本书的记录，右上角「清空」去掉全部；两者都不动书与进度。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val db by lazy { ReaderDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()
        binding.back.setOnClickListener { finish() }
        binding.clear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) { db.historyDao().recentBooks(200) }
            val opened = withContext(Dispatchers.IO) { db.historyDao().lastOpened() }
                .associate { it.bookId to it.at }
            val progress = withContext(Dispatchers.IO) { db.progressDao().all() }
                .associateBy { it.bookId }
            val sources = withContext(Dispatchers.IO) { db.sourceDao().all() }
                .associateBy { it.id }
            render(books, opened, progress, sources)
        }
    }

    private fun render(
        books: List<BookEntity>,
        opened: Map<Long, Long>,
        progress: Map<Long, ProgressEntity>,
        sources: Map<Long, SourceEntity>,
    ) {
        val list = binding.list
        list.removeAllViews()

        val empty = books.isEmpty()
        binding.scroll.isVisible = !empty
        binding.empty.isVisible = empty
        binding.clear.isVisible = !empty
        if (empty) return

        for (book in books) {
            list.addView(
                row(
                    book = book,
                    openedAt = opened[book.id] ?: 0L,
                    p = progress[book.id],
                    sourceLabel = sources[book.sourceId]?.label.orEmpty(),
                )
            )
        }
    }

    private fun row(
        book: BookEntity,
        openedAt: Long,
        p: ProgressEntity?,
        sourceLabel: String,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener {
                startActivity(
                    Intent(this@HistoryActivity, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_BOOK_ID, book.id)
                )
            }
            setOnLongClickListener {
                confirmRemove(book)
                true
            }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this@HistoryActivity).apply {
            text = book.title
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.shell_ink))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (p != null && book.pageTotal > 0) {
            val percent = readPercent(p.pageGlobal, book.pageTotal, p.finished)
            head.addView(TextView(this).apply {
                text = getString(R.string.shelf_read_percent, percent)
                textSize = 11f
                setTextColor(getColor(R.color.brand_accent))
            })
        }
        card.addView(head)

        val meta = ArrayList<String>(2)
        if (sourceLabel.isNotEmpty()) meta += sourceLabel
        if (openedAt > 0) meta += RelativeTime.dateOf(openedAt)
        card.addView(TextView(this).apply {
            text = meta.joinToString(" · ")
            textSize = 11f
            setTextColor(getColor(R.color.shell_ink_faint))
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    private fun confirmRemove(book: BookEntity) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.history_remove_confirm, book.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.historyDao().clearFor(book.id) }
                    load()
                }
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_clear_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.historyDao().clear() }
                    load()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
