package com.nascomic.reader.ui.author

import com.nascomic.reader.ui.widget.AppDialogBuilder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.nascomic.reader.library.author.AuthorMerger
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.databinding.ActivityAuthorListBinding
import com.nascomic.reader.databinding.ItemAuthorBinding
import com.nascomic.reader.databinding.ItemAuthorLetterBinding
import com.nascomic.reader.library.author.AuthorIndex
import com.nascomic.reader.library.data.AuthorCount
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.ui.MainActivity
import com.nascomic.reader.ui.widget.AppDialogs
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作家列表：按索引字母分节，右侧一条索引条快速跳。
 *
 * 作者名从左边的拼音首字母来（汉字按中文读、假名按罗马音），规则都在 [AuthorIndex] 里；
 * 认不出字母的归到最后一节「#」，其中最上面一行是「未知作者」——书名里没写作者的那些。
 */
class AuthorListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthorListBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val merger by lazy { AuthorMerger(db) }
    private lateinit var adapter: Adapter

    private var all: List<AuthorCount> = emptyList()
    private var unknownTotal = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthorListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        adapter = Adapter(
            onClick = { name, unknown ->
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (unknown) {
                    intent.putExtra(MainActivity.EXTRA_AUTHOR_UNKNOWN, true)
                } else {
                    intent.putExtra(MainActivity.EXTRA_AUTHOR, name)
                }
                startActivity(intent)
            },
            onLongClick = { name -> showAuthorMenu(name) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(StickyLetter(this, adapter))

        binding.back.setOnClickListener { finish() }
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            render()
        }
        binding.indexBar.onLetter = { letter ->
            scrollTo(letter)
            showBubble(letter)
        }

        // 高亮跟着列表走：滚到哪一节，索引条就亮哪个字母
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = (rv.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: return
                if (first == RecyclerView.NO_POSITION) return
                adapter.letterAt(first)?.let { binding.indexBar.setCurrent(it) }
            }
        })

        readExtraQuery()
        observeMerge()
    }

    private fun readExtraQuery() {
        val q = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (q.isNotEmpty()) {
            binding.search.setText(q)
            binding.search.setSelection(q.length)
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { db.bookDao().authorCounts() }
            val unknown = withContext(Dispatchers.IO) { db.bookDao().booksWithoutAuthor() }
            all = data
            unknownTotal = unknown
            render()
        }
    }

    /**
     * 还没提交的合并先在列表上「预演」出来。
     *
     * 5 秒窗口内库里还没改，但用户点了合并就得立刻看到结果。
     */
    private fun withPendingMerge(list: List<AuthorCount>): List<AuthorCount> {
        val p = merger.pending() ?: return list
        val moved = list.firstOrNull { it.name == p.from } ?: return list
        val rest = list.filterNot { it.name == p.from }
        val hit = rest.firstOrNull { it.name == p.canonical }
        return if (hit == null) {
            // 正名是新起的名字：补一行
            rest + AuthorCount(p.canonical, moved.total)
        } else {
            rest.map { if (it.name == p.canonical) AuthorCount(p.canonical, it.total + moved.total) else it }
        }
    }

    private fun render() {
        val merged = withPendingMerge(all)
        binding.subtitle.text = getString(
            R.string.author_page_subtitle,
            merged.size,
            merged.sumOf { it.total } + unknownTotal,
        )
        val visible = if (query.isEmpty()) {
            merged
        } else {
            merged.filter { it.name.contains(query, ignoreCase = true) }
        }
        val sections = visible
            .groupBy { AuthorIndex.letter(it.name) }
            .toSortedMap(compareBy<String> { if (it == AuthorIndex.OTHER) 1 else 0 }.thenBy { it })
            .mapValues { (_, list) -> list.sortedWith(compareBy { NaturalSort.key(it.name) }) }

        val rows = ArrayList<Row>()
        for ((letter, list) in sections) {
            rows += Row.Letter(letter, list.size)
            list.forEach { rows += Row.Author(it.name, it.total) }
            // 未知作者挂在 # 里；没有别的 # 作者时也要单独起一节，
            // 否则这批书在页面上彻底消失
            if (letter == AuthorIndex.OTHER && query.isEmpty() && unknownTotal > 0) {
                rows += Row.Author(getString(R.string.author_unknown), unknownTotal, unknown = true)
            }
        }
        if (query.isEmpty() && unknownTotal > 0 && AuthorIndex.OTHER !in sections) {
            rows += Row.Letter(AuthorIndex.OTHER, 1)
            rows += Row.Author(getString(R.string.author_unknown), unknownTotal, unknown = true)
        }

        adapter.submit(rows)
        binding.list.isVisible = rows.isNotEmpty()
        binding.indexBar.isVisible = rows.isNotEmpty()
        binding.empty.isVisible = rows.isEmpty() && query.isEmpty()
        // 索引条只认「页面上真有的节」：# 节是上面另外补出来的一行，
        // 不并进来，全是未知作者时条子就是一条空胶囊，按不动也看不见
        val barLetters = sections.keys.toMutableList()
        if (query.isEmpty() && unknownTotal > 0 && AuthorIndex.OTHER !in barLetters) {
            barLetters += AuthorIndex.OTHER
        }
        binding.indexBar.setLetters(barLetters, null)
    }

    /** 长按作者：改名，或把这条并到另一位作者名下 */
    private fun showAuthorMenu(name: String) {
        val actions = arrayOf(
            getString(R.string.author_menu_rename),
            getString(R.string.author_menu_merge),
        )
        AppDialogs.menu(this, name, actions.toList()) { which ->
            if (which == 0) askRename(name) else askMerge(name)
        }
    }

    /**
     * 合并面板：选已有作者或输入新名字。
     *
     * 名单用页面上已经读到的全量作者（不受搜索框过滤影响）——合并的目标不该被
     * 当前页面的搜索范围限制。
     */
    private fun askMerge(old: String) {
        AuthorMergeSheet(
            context = this,
            source = old,
            candidates = all,
            onConfirm = { target -> mergeInto(old, target) },
        ).show()
    }

    /**
     * 5 秒撤销窗口：界面先按「合并后」显示，到点才落库。
     *
     * 窗口内点撤销等于取消这次合并——库里一个字都没改，所以不存在「回退到哪一份快照」
     * 的问题：窗口里扫进来的书和用户早先手动改过的书都不会被误伤。
     */
    private fun mergeInto(from: String, to: String) {
        lifecycleScope.launch {
            if (merger.prepare(from, to) == null) {
                val message = if (merger.pending() != null) R.string.author_merge_busy
                    else R.string.author_merge_need_target
                android.widget.Toast.makeText(this@AuthorListActivity, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeMerge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var bar: Snackbar? = null
                var shown: AuthorMerger.Pending? = null
                try {
                    merger.window.collect { state ->
                        render()
                        val pending = state.pending?.takeUnless { state.committing }
                        if (shown !== pending) {
                            bar?.dismiss()
                            shown = pending
                            bar = pending?.let { ticket ->
                                val remaining = ticket.remainingMs().toInt()
                                if (remaining == 0) null else Snackbar.make(binding.root,
                                    getString(R.string.author_merge_done, ticket.from, ticket.canonical), remaining)
                                    .setAction(R.string.author_merge_undo) {
                                        if (merger.cancelWindow(ticket)) {
                                            android.widget.Toast.makeText(this@AuthorListActivity,
                                                R.string.author_merge_undo_done, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }.also { it.show() }
                            }
                        }
                        if (state.pending == null || all.isEmpty()) load()
                        state.failed?.takeIf(merger::consumeFailure)?.let {
                            android.widget.Toast.makeText(this@AuthorListActivity,
                                R.string.author_merge_failed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    bar?.dismiss()
                }
            }
        }
    }

    /** 长按作者：整批改名，别让同一个人分成两条 */
    private fun askRename(old: String) {
        val input = android.widget.EditText(this).apply {
            setText(old)
            setSelection(text.length)
            setSingleLine()
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(android.widget.TextView(this@AuthorListActivity).apply {
                text = getString(R.string.author_rename_desc)
                textSize = 12f
                setTextColor(getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AppDialogBuilder(this)
            .setTitle(R.string.author_rename_title)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val fresh = input.text.toString().trim()
                if (fresh.isEmpty() || fresh == old) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.bookDao().renameAuthor(old, fresh) }
                    android.widget.Toast.makeText(
                        this@AuthorListActivity,
                        R.string.author_rename_done,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    load()
                }
            }
            .show()
    }

    /** 拖动时中间浮一个大字母，停手 500ms 自己收起来 */
    private fun showBubble(letter: String) {
        binding.letterBubble.text = letter
        binding.letterBubble.visibility = View.VISIBLE
        binding.letterBubble.animate().cancel()
        binding.letterBubble.alpha = 1f
        binding.letterBubble.postDelayed({
            binding.letterBubble.animate().alpha(0f).setDuration(180)
                .withEndAction { binding.letterBubble.visibility = View.GONE }
                .start()
        }, 500)
    }

    private fun scrollTo(letter: String) {
        val pos = adapter.positionOfLetter(letter) ?: return
        val lm = binding.list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(pos, 0)
    }

    override fun onResume() {
        super.onResume()
        // 从作者页回来时作者数可能变了（补过作者）；空库也要重读——
        // 否则第一次进来是空库，之后新扫了书也不会长出来
        load()
    }

    // ── 行 ─────────────────────────────────────────────────

    private sealed class Row {
        class Letter(val letter: String, val people: Int) : Row()
        class Author(val name: String, val total: Int, val unknown: Boolean = false) : Row()
    }

    private class Adapter(
        private val onClick: (String, Boolean) -> Unit,
        private val onLongClick: (String) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = ArrayList<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun positionOfLetter(letter: String): Int? =
            rows.indexOfFirst { it is Row.Letter && it.letter == letter }.takeIf { it >= 0 }

        fun letterAt(position: Int): String? {
            for (i in position downTo 0) {
                (rows.getOrNull(i) as? Row.Letter)?.let { return it.letter }
            }
            return null
        }

        fun isLetter(position: Int): Boolean = rows.getOrNull(position) is Row.Letter

        override fun getItemViewType(position: Int): Int = if (isLetter(position)) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) {
                LetterHolder(ItemAuthorLetterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                AuthorHolder(ItemAuthorBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Letter -> (holder as LetterHolder).bind(row)
                is Row.Author -> (holder as AuthorHolder).bind(
                    row,
                    onClick = { onClick(row.name, row.unknown) },
                    onLongClick = { if (!row.unknown) onLongClick(row.name) },
                )
            }
        }

        class LetterHolder(val binding: ItemAuthorLetterBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Letter) {
                binding.letter.text = row.letter
                binding.count.text = binding.root.context
                    .getString(R.string.author_letter_count, row.people)
            }
        }

        class AuthorHolder(val binding: ItemAuthorBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Author, onClick: () -> Unit, onLongClick: () -> Unit) {
                binding.name.text = row.name
                binding.name.setTextColor(
                    binding.root.context.getColor(
                        if (row.unknown) R.color.shell_ink_faint else R.color.shell_ink
                    )
                )
                binding.count.text = binding.root.context
                    .getString(R.string.author_row_count, row.total)
                binding.root.setOnClickListener { onClick() }
                binding.root.setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    /**
     * 吸附的字母：某节滚过顶部时，把它的字母画在列表顶端。
     * 用装饰器而不是往列表里插项，避免滚动时需要额外维护。
     */
    private class StickyLetter(
        private val activity: AuthorListActivity,
        private val adapter: Adapter,
    ) : RecyclerView.ItemDecoration() {

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = activity.resources.displayMetrics.scaledDensity * 12f
            isFakeBoldText = true
            color = activity.getColor(R.color.shell_ink_muted)
        }
        private val bg = android.graphics.Paint().apply {
            color = activity.getColor(R.color.shell_background)
        }

        override fun onDrawOver(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val first = (parent.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
                ?: return
            if (first == RecyclerView.NO_POSITION) return
            val letter = adapter.letterAt(first) ?: return
            val y = parent.paddingTop + paint.textSize + activity.dp(4)
            c.drawRect(0f, 0f, parent.width.toFloat(), y + activity.dp(6), bg)
            c.drawText(letter, activity.dp(8).toFloat(), y, paint)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 从书架搜索的「作家」范围进来时带的关键字 */
        const val EXTRA_QUERY = "query"
    }
}
