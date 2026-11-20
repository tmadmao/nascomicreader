package com.nascomic.reader.ui.shelf

import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.library.SeriesRepository
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.ui.SeriesDetailActivity
import kotlinx.coroutines.launch

/**
 * 「这本书和作品系列的关系」这一组动作。
 *
 * 书架的长按菜单与书籍详情页的 ⋯ 都会用到，逻辑只写这一份：
 * 没归属 → 加入已有系列 / 新建系列；已归属 → 查看系列 / 移出。
 */
class SeriesActions(
    private val activity: AppCompatActivity,
    private val db: ReaderDatabase,
    /** 动作完成后通知调用方刷新 */
    private val onChanged: () -> Unit = {},
) {

    private val repo by lazy { SeriesRepository(db) }

    /** 单本入口：书架长按与书籍详情页用 */
    fun open(book: BookEntity) {
        activity.lifecycleScope.launch {
            val series = repo.seriesOf(book.id)
            if (series == null) pick(listOf(book)) else actions(book, series.id, series.name)
        }
    }

    /** 批量入口：书架多选后一次加入同一个系列 */
    fun openFor(books: List<BookEntity>) {
        if (books.isEmpty()) return
        if (books.size == 1) {
            open(books.first())
            return
        }
        activity.lifecycleScope.launch { pick(books) }
    }

    /** 选系列：带搜索的底部选择器，系列多了也能找；第一行是新建 */
    private fun pick(books: List<BookEntity>) {
        activity.lifecycleScope.launch {
            val all = repo.all()
            val items = ArrayList<SearchPicker.Item>()
            items += SearchPicker.Item(NEW_SERIES, activity.getString(R.string.series_join_new), "")
            all.forEach { entity ->
                items += SearchPicker.Item(
                    entity.id,
                    entity.name,
                    activity.getString(R.string.series_volume_count, repo.countOf(entity.id)),
                )
            }
            SearchPicker(
                context = activity,
                title = activity.getString(R.string.series_pick_title),
                hint = activity.getString(R.string.series_pick_hint),
                items = items,
                multi = false,
                onPick = { picked ->
                    if (picked.id == NEW_SERIES) create(books) else join(books, picked.id)
                },
            ).show()
        }
    }

    private fun create(books: List<BookEntity>) {
        val book = books.first()
        val input = EditText(activity).apply {
            setText(book.displayTitle)
            setSelection(text.length)
            hint = activity.getString(R.string.series_name_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.series_new_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.series_new_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                activity.lifecycleScope.launch {
                    val now = System.currentTimeMillis()
                    val id = repo.create(name, now)
                    books.forEach { repo.addBook(id, it.id, now) }
                    onChanged()
                    openDetail(id)
                }
            }
            .show()
    }

    private fun join(books: List<BookEntity>, seriesId: Long) {
        activity.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            books.forEach { repo.addBook(seriesId, it.id, now) }
            onChanged()
            openDetail(seriesId)
        }
    }

    private fun actions(book: BookEntity, seriesId: Long, seriesName: String) {
        val items = arrayOf(
            activity.getString(R.string.series_view),
            activity.getString(R.string.series_leave),
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.series_in, seriesName))
            .setItems(items) { _, which ->
                if (which == 0) {
                    openDetail(seriesId)
                } else {
                    activity.lifecycleScope.launch {
                        repo.removeBook(book.id)
                        onChanged()
                        Toast.makeText(activity, R.string.series_leave_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private companion object {
        /** 选系列列表里「新建」那一行的键，与真实系列 id 不冲突 */
        const val NEW_SERIES = -1L
    }

    private fun openDetail(seriesId: Long) {
        activity.startActivity(
            Intent(activity, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
        )
    }
}
