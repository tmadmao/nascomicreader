package com.nascomic.reader.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nascomic.reader.R
import com.nascomic.reader.databinding.ActivitySeriesBinding
import com.nascomic.reader.databinding.ItemSeriesVolumeBinding
import com.nascomic.reader.format.readPercent
import com.nascomic.reader.library.SeriesRepository
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.ui.detail.DetailActivity
import com.nascomic.reader.ui.reader.ReadingActivity
import com.nascomic.reader.ui.widget.padBySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作品系列详情。
 *
 * 骨架与书籍详情页一致：封面 + 信息 + 一个主动作 + 目录。
 * 区别只在目录列的是各分卷——系列被当成一本连载的书来读，不另立一套导航。
 */
class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private val db by lazy { ReaderDatabase.get(this) }
    private val repo by lazy { SeriesRepository(db) }
    private val prefs by lazy { AppPrefs(this) }
    private val thumbs by lazy { ThumbnailStore(this) }

    private lateinit var adapter: VolumeAdapter
    private var seriesId = 0L
    private var volumes: List<BookEntity> = emptyList()
    private var selecting = false
    private val selectedIds = LinkedHashSet<Long>()
    private var progress: Map<Long, ProgressEntity> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padBySystemBars()

        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, 0L)
        if (seriesId <= 0L) {
            finish()
            return
        }

        adapter = VolumeAdapter(
            onClick = { book -> if (selecting) toggleSelection(book.id) else openVolume(book) },
            onLongClick = { book -> enterSelection(book.id) },
        )
        binding.volumeList.layoutManager = LinearLayoutManager(this)
        binding.volumeList.adapter = adapter

        binding.back.setOnClickListener { finish() }
        binding.more.setOnClickListener { showMenu() }
        binding.primary.setOnClickListener { openLatest() }
        binding.addVolumes.setOnClickListener { pickVolumes() }
        binding.selectCancel.setOnClickListener { exitSelection() }
        binding.selectRemove.setOnClickListener { confirmRemove() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val series = repo.get(seriesId)
            if (series == null) {
                finish()
                return@launch
            }
            volumes = repo.volumes(seriesId)
            val all = db.progressDao().all().associateBy { it.bookId }
            progress = all

            binding.title.text = series.name
            binding.meta.text = getString(
                R.string.series_meta,
                volumes.size,
                volumes.sumOf { it.pageTotal },
            )
            binding.volumeCount.text = getString(R.string.series_volume_count, volumes.size)

            val (readingBook, readingProgress) = latestRead()
            binding.readInfo.text = if (readingBook != null && readingProgress != null) {
                val index = volumes.indexOfFirst { it.id == readingBook.id } + 1
                getString(
                    R.string.series_read_position,
                    index,
                    readPercent(readingProgress.pageGlobal, readingBook.pageTotal, readingProgress.finished),
                )
            } else {
                getString(R.string.detail_not_started)
            }
            binding.primary.text = if (readingBook != null) {
                getString(R.string.series_continue, volumes.indexOfFirst { it.id == readingBook.id } + 1)
            } else {
                getString(R.string.series_start)
            }
            binding.primary.isEnabled = volumes.isNotEmpty()

            adapter.submit(volumes, all)

            val empty = volumes.isEmpty()
            binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.volumeList.visibility = if (empty) View.GONE else View.VISIBLE
            loadCover(volumes.firstOrNull())
        }
    }

    /** 读到哪一卷：按进度更新时间取最近的那卷 */
    private fun latestRead(): Pair<BookEntity?, ProgressEntity?> {
        val latest = volumes.mapNotNull { book -> progress[book.id]?.let { book to it } }
            .maxByOrNull { it.second.updatedAt }
        return latest?.first to latest?.second
    }

    private fun openLatest() {
        val (book, _) = latestRead()
        val target = book ?: volumes.firstOrNull() ?: return
        openVolume(target)
    }

    /** 点一卷：按「跳过详情页」的偏好决定直接读还是进那一卷的详情 */
    private fun openVolume(book: BookEntity) {
        val intent = if (prefs.skipDetail) {
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_BOOK_ID, book.id)
        } else {
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_BOOK_ID, book.id)
        }
        startActivity(intent)
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.series_menu_rename),
            getString(R.string.series_menu_delete),
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) askRename() else confirmDelete()
            }
            .show()
    }

    private fun askRename() {
        val input = EditText(this).apply {
            setText(binding.title.text)
            setSelection(text.length)
            hint = getString(R.string.series_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.series_rename_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.series_rename_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    repo.rename(seriesId, name)
                    load()
                }
            }
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.series_delete_title)
            .setMessage(getString(R.string.series_delete_message, binding.title.text, volumes.size))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.series_delete_ok) { _, _ ->
                lifecycleScope.launch {
                    repo.delete(seriesId)
                    Toast.makeText(this@SeriesDetailActivity, R.string.series_delete_done, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    // ── 卷的多选 ───────────────────────────────────────────

    private fun enterSelection(firstId: Long) {
        selecting = true
        selectedIds.clear()
        selectedIds += firstId
        renderSelection()
    }

    private fun exitSelection() {
        selecting = false
        selectedIds.clear()
        renderSelection()
    }

    private fun toggleSelection(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        renderSelection()
    }

    private fun renderSelection() {
        adapter.setSelection(selecting, selectedIds)
        binding.selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.addVolumes.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.selectCount.text = getString(R.string.series_selected_count, selectedIds.size)
        binding.selectRemove.isEnabled = selectedIds.isNotEmpty()
        binding.selectRemove.alpha = if (selectedIds.isEmpty()) 0.5f else 1f
    }

    /** 移出系列：只解关联，书还在书库里 */
    private fun confirmRemove() {
        if (selectedIds.isEmpty()) return
        val doomed = selectedIds.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.series_remove_title)
            .setMessage(getString(R.string.series_remove_message, doomed.size))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.series_leave) { _, _ ->
                lifecycleScope.launch {
                    doomed.forEach { repo.removeBook(it) }
                    exitSelection()
                    load()
                    Toast.makeText(this@SeriesDetailActivity, R.string.series_remove_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 加入其它卷：只列还不属于这个系列的书，带搜索，可一次多选 */
    private fun pickVolumes() {
        lifecycleScope.launch {
            val inSeries = volumes.map { it.id }.toHashSet()
            val candidates = db.bookDao().allSorted()
                .filter { it.id !in inSeries }
                .map {
                    com.nascomic.reader.ui.shelf.SearchPicker.Item(
                        id = it.id,
                        title = it.title,
                        subtitle = getString(R.string.series_volume_pages, it.pageTotal),
                    )
                }
            if (candidates.isEmpty()) {
                Toast.makeText(this@SeriesDetailActivity, R.string.series_add_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            com.nascomic.reader.ui.shelf.SearchPicker(
                context = this@SeriesDetailActivity,
                title = getString(R.string.series_add_pick_title),
                hint = getString(R.string.series_add_pick_hint),
                items = candidates,
                multi = true,
                onPick = {},
                onMultiPick = { picked ->
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        picked.forEach { repo.addBook(seriesId, it.id, now) }
                        load()
                    }
                },
            ).show()
        }
    }

    private fun loadCover(book: BookEntity?) {
        if (book == null) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val width = (92 * resources.displayMetrics.density).toInt()
                runCatching { thumbs.load(book.id, width, width * 4 / 3) }.getOrNull()
            }
            if (bitmap != null) binding.cover.setImageBitmap(bitmap)
        }
    }

    /** 卷列表：序号 · 封面 · 卷名与页数进度 · 箭头 */
    private class VolumeAdapter(
        private val onClick: (BookEntity) -> Unit,
        private val onLongClick: (BookEntity) -> Unit,
    ) : RecyclerView.Adapter<VolumeAdapter.Holder>() {

        private var books: List<BookEntity> = emptyList()
        private var progress: Map<Long, ProgressEntity> = emptyMap()
        private var selecting = false
        private var selectedIds: Set<Long> = emptySet()

        /** 同书架：调用方传的是同一个可变集合，必须另存一份再比，否则勾选不重绑 */
        fun setSelection(selecting: Boolean, ids: Set<Long>) {
            val incoming = HashSet(ids)
            if (this.selecting == selecting && this.selectedIds == incoming) return
            this.selecting = selecting
            this.selectedIds = incoming
            notifyItemRangeChanged(0, books.size)
        }

        fun submit(newBooks: List<BookEntity>, newProgress: Map<Long, ProgressEntity>) {
            books = newBooks
            progress = newProgress
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemSeriesVolumeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSeriesVolumeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = books.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val book = books[position]
            val p = progress[book.id]
            holder.binding.ordinal.text = (position + 1).toString().padStart(2, '0')
            holder.binding.title.text = book.title
            holder.binding.meta.text = if (p == null) {
                holder.binding.root.context.getString(R.string.series_volume_pages, book.pageTotal)
            } else {
                holder.binding.root.context.getString(
                    R.string.series_volume_progress,
                    book.pageTotal,
                    readPercent(p.pageGlobal, book.pageTotal, p.finished),
                )
            }
            holder.binding.check.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.binding.check.text = if (book.id in selectedIds) "☑" else "☐"
            holder.binding.root.alpha = if (selecting && book.id !in selectedIds) 0.55f else 1f
            holder.binding.root.setOnClickListener { onClick(book) }
            holder.binding.root.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
    }
}
