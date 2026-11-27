package com.nascomic.reader.ui.shelf

import com.nascomic.reader.ui.widget.AppDialogBuilder

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
    private val onCoverReady: (BookEntity, Bitmap) -> Unit,
    /** 改完（改名 / 换封面 / 删除）通知调用方刷新 */
    private val onChanged: () -> Unit,
    private val coverWidth: () -> Int,
    /**
     * 删除交给调用方安排：不为空时确认之后不立刻落库，
     * 由书架先藏起来、过 5 秒再真删（见 [DeleteUndo]）。为空就是当场删，详情页用这种。
     */
    private val deferDelete: ((BookEntity) -> Unit)? = null,
) {

    fun rename(book: BookEntity) {
        val input = EditText(activity).apply {
            setText(book.displayTitle)
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
        AppDialogBuilder(activity)
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
                    db.bookDao().rename(book.id, name, NaturalSort.key(name))
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
        AppDialogBuilder(activity)
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

    /**
     * 换封面：打开全屏挑页面板。
     *
     * 原来的「输入第几页」只适合已经知道页号的人；从书里挑要直观得多。
     * 面板自己负责开书与取缩略图，点一页回调回来，这里只写库与重生成缩略图。
     */
    fun changeCover(book: BookEntity) {
        val src = sourceOf(book.sourceId)
        if (src == null) {
            Toast.makeText(activity, R.string.shelf_cover_failed, Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) { db.chapterDao().forBook(book.id) }
            CoverPickerSheet(
                activity = activity,
                book = book,
                source = src,
                chapters = chapters,
                opener = opener,
                onPick = { page -> applyCover(book, page) },
            ).show()
        }
    }

    /**
     * 先生成新封面，成了才写回页号。
     *
     * 反过来写（先落页号再生成）一旦生成失败，库里记着新页、缩略图还是老的：
     * 界面上一张旧图，点进去却是另一页，而且再打开详情也不会自愈。
     */
    private fun applyCover(book: BookEntity, page: Int) {
        activity.lifecycleScope.launch {
            val current = db.bookDao().get(book.id)
            if (current == null || current.coverPage != book.coverPage || current.contentVersion != book.contentVersion) {
                Toast.makeText(activity, R.string.shelf_cover_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val updated = current.copy(coverPage = page)
            val src = sourceOf(book.sourceId)
            val chapters = db.chapterDao().forBook(book.id)
            val file = if (src == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { thumbs.generate(updated, src, chapters, opener) }.getOrNull()
                }
            }
            val saved = file != null && runCatching {
                db.bookDao().updateCoverIfUnchanged(book.id, current.coverPage, current.contentVersion, page) == 1
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.w("Cover", "保存封面页失败", it)
                false
            }
            if (!saved) {
                Toast.makeText(activity, R.string.shelf_cover_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val width = coverWidth().takeIf { it > 0 } ?: dp(112)
            val bitmap = withContext(Dispatchers.IO) { thumbs.load(updated, width, width * 4 / 3) }
            if (bitmap != null) onCoverReady(updated, bitmap)
            onChanged()
            Toast.makeText(activity, R.string.shelf_cover_done, Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(book: BookEntity) {
        AppDialogBuilder(activity)
            .setTitle(R.string.shelf_delete_title)
            .setMessage(activity.getString(R.string.shelf_delete_message, book.displayTitle))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.shelf_delete_confirm) { _, _ ->
                val defer = deferDelete
                if (defer != null) {
                    // 撤销窗口由调用方掌握，这里不落库、也不弹「已删除」
                    defer(book)
                    return@setPositiveButton
                }
                activity.lifecycleScope.launch {
                    val failed = remove(listOf(book.id))
                    onChanged()
                    val msg = if (failed.isEmpty()) R.string.shelf_delete_done else R.string.shelf_delete_failed
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /**
     * 真删：记录、页缓存、缩略图一起清。源文件一个字节都不动。
     * 返回没删成的 id；失败的书缓存原样留着。单本与详情页走的都是这一条。
     */
    suspend fun remove(ids: List<Long>): List<Long> = BookRemover.remove(activity, ids, db)

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
