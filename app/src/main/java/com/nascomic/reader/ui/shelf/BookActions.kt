package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.core.text.NaturalSort
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.ui.reader.DiskPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一本书上能做的那几件事：重命名、换封面、删除。
 *
 * 书架的「长按菜单」和「书籍详情页右上角 ⋯」是同一组动作，逻辑只写这一份；
 * 两个页面各自决定菜单长什么样，动作本身不重复。
 */
class BookActions(
    private val activity: AppCompatActivity,
    private val db: ReaderDatabase,
    private val thumbs: ThumbnailStore,
    private val opener: BookOpener,
    private val sourceOf: (Long) -> SourceEntity?,
    /** 封面尺寸变了之后要喂回去，由调用方决定怎么显示 */
    private val onCoverReady: (Long, Bitmap) -> Unit,
    /** 改完（改名 / 换封面 / 删除）通知调用方刷新 */
    private val onChanged: () -> Unit,
    private val coverWidth: () -> Int,
) {

    fun rename(book: BookEntity) {
        val input = EditText(activity).apply {
            setText(book.title)
            setSelection(text.length)
            setSingleLine()
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.shelf_rename_hint)
                textSize = 12f
                setTextColor(activity.getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.shelf_rename_title)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, R.string.shelf_rename_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                activity.lifecycleScope.launch {
                    db.bookDao().updateTitle(book.id, name, NaturalSort.key(name))
                    onChanged()
                    Toast.makeText(activity, R.string.shelf_rename_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 手动补/改作者；留空就是清掉，之后归到「未知作者」 */
    fun setAuthor(book: BookEntity) {
        val input = EditText(activity).apply {
            setText(book.author.orEmpty())
            setSelection(text.length)
            setSingleLine()
            hint = activity.getString(R.string.book_author_hint)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.book_author_desc)
                textSize = 12f
                setTextColor(activity.getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.book_author_title)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { null }
                activity.lifecycleScope.launch {
                    db.bookDao().setAuthor(book.id, name)
                    onChanged()
                    Toast.makeText(activity, R.string.book_author_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    fun changeCover(book: BookEntity) {
        val pages = book.pageTotal
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = activity.getString(R.string.shelf_cover_input_hint)
            setText((book.coverPage + 1).toString())
            setSelection(text.length)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.shelf_cover_hint, pages.coerceAtLeast(1))
                textSize = 12f
                setTextColor(activity.getColor(R.color.shell_ink_muted))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.shelf_cover_title, book.title))
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                val upper = if (pages > 0) pages else Int.MAX_VALUE
                if (n == null || n < 1 || n > upper) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.shelf_cover_invalid, minOf(upper, pages.coerceAtLeast(1))),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                applyCover(book, n - 1)
            }
            .show()
    }

    /** 写回取图页号，删掉旧缩略图，重新生成一次再喂给界面 */
    private fun applyCover(book: BookEntity, page: Int) {
        activity.lifecycleScope.launch {
            db.bookDao().updateCoverPage(book.id, page)
            val updated = book.copy(coverPage = page)
            val src = sourceOf(book.sourceId)
            val chapters = db.chapterDao().forBook(book.id)
            val file = if (src == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { thumbs.generate(updated, src, chapters, opener) }.getOrNull()
                }
            }
            if (file == null) {
                Toast.makeText(activity, R.string.shelf_cover_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val width = coverWidth().takeIf { it > 0 } ?: dp(112)
            val bitmap = withContext(Dispatchers.IO) { thumbs.load(book.id, width, width * 4 / 3) }
            if (bitmap != null) onCoverReady(book.id, bitmap)
            onChanged()
            Toast.makeText(activity, R.string.shelf_cover_done, Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(book: BookEntity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.shelf_delete_title)
            .setMessage(activity.getString(R.string.shelf_delete_message, book.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.shelf_delete_confirm) { _, _ ->
                activity.lifecycleScope.launch {
                    db.bookDao().delete(book.id)
                    withContext(Dispatchers.IO) {
                        // 这本书的页缓存与缩略图也一并清掉，不然没人再用它们
                        DiskPageCache(activity).clearBook(book.id)
                        thumbs.invalidate(book.id)
                    }
                    onChanged()
                    Toast.makeText(activity, R.string.shelf_delete_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
