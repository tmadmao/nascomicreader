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

    fun open(book: BookEntity) {
        activity.lifecycleScope.launch {
            val series = repo.seriesOf(book.id)
            if (series == null) pick(book) else actions(book, series.id, series.name)
        }
    }

    private fun pick(book: BookEntity) {
        activity.lifecycleScope.launch {
            val all = repo.all()
            val labels = all.map { it.name }.toMutableList()
            labels += activity.getString(R.string.series_join_new)
            AlertDialog.Builder(activity)
                .setTitle(R.string.series_join_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < all.size) {
                        join(book, all[which].id)
                    } else {
                        create(book)
                    }
                }
                .show()
        }
    }

    private fun create(book: BookEntity) {
        val input = EditText(activity).apply {
            setText(book.title)
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
                    val id = repo.create(name, System.currentTimeMillis())
                    repo.addBook(id, book.id, System.currentTimeMillis())
                    onChanged()
                    openDetail(id)
                }
            }
            .show()
    }

    private fun join(book: BookEntity, seriesId: Long) {
        activity.lifecycleScope.launch {
            repo.addBook(seriesId, book.id, System.currentTimeMillis())
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

    private fun openDetail(seriesId: Long) {
        activity.startActivity(
            Intent(activity, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, seriesId)
        )
    }
}
