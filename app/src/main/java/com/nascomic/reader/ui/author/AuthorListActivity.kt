package com.nascomic.reader.ui.author

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
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
    private lateinit var adapter: Adapter

    private var all: List<AuthorCount> = emptyList()
    private var unknownTotal = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthorListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        adapter = Adapter { name ->
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTHOR, name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(StickyLetter(this, adapter))

        binding.back.setOnClickListener { finish() }
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            render()
        }
        binding.indexBar.onLetter = { letter -> scrollTo(letter) }

        readExtraQuery()
        load()
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
            binding.subtitle.text = getString(
                R.string.author_page_subtitle,
                data.size,
                data.sumOf { it.total } + unknown,
            )
            render()
        }
    }

    private fun render() {
        val visible = if (query.isEmpty()) {
            all
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) }
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
        binding.indexBar.setLetters(sections.keys.toList(), null)
    }

    private fun scrollTo(letter: String) {
        val pos = adapter.positionOfLetter(letter) ?: return
        val lm = binding.list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(pos, 0)
    }

    override fun onResume() {
        super.onResume()
        // 从作者页回来时作者数可能变了（补过作者），重读一次
        if (all.isNotEmpty()) load()
    }

    // ── 行 ─────────────────────────────────────────────────

    private sealed class Row {
        class Letter(val letter: String, val people: Int) : Row()
        class Author(val name: String, val total: Int, val unknown: Boolean = false) : Row()
    }

    private class Adapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                is Row.Author -> (holder as AuthorHolder).bind(row) { onClick(row.name) }
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
            fun bind(row: Row.Author, onClick: () -> Unit) {
                binding.name.text = row.name
                binding.name.setTextColor(
                    binding.root.context.getColor(
                        if (row.unknown) R.color.shell_ink_faint else R.color.shell_ink
                    )
                )
                binding.count.text = binding.root.context
                    .getString(R.string.author_row_count, row.total)
                binding.root.setOnClickListener { onClick() }
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
