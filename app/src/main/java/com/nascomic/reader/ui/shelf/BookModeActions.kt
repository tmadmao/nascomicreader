package com.nascomic.reader.ui.shelf

import com.nascomic.reader.ui.widget.AppDialogBuilder

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nascomic.reader.R
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.mode.BookModeBatch
import com.nascomic.reader.library.mode.BookModePlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 改书型的入口与确认流程。
 *
 * 先读一遍目录出预览，再让用户确认：预览里逐组写清「几本变几本」，
 * 并把没勾选但会被一起重建的书列出来——只勾了第 3 话却把同目录 12 话并成一本，
 * 不先讲清楚就没法接受。系列或自选封面有多项可选时，先让人选保留哪一个。
 *
 * 执行按组报告；失败的组留在结果框里可以单独重试，已成功的组不会重跑。
 * 一组抛异常只算这一组失败，后面的组照跑——某本书的压缩包坏了，不该让整批停在半路。
 */
internal class BookModeActions(
    private val activity: AppCompatActivity,
    private val batch: BookModeBatch,
    private val onChanged: () -> Unit,
) {

    /** 待执行的一组与它当时的选项 */
    private data class Pending(
        val group: BookModeBatch.Group,
        val choice: BookModePlanner.Choice,
        val target: BookModeBatch.Target,
    )

    fun choose(books: List<BookEntity>) {
        if (books.isEmpty()) return
        // 一册 zip / pdf / epub 本身就是一本书，拆并都无从谈起；入口就先挡掉
        val usable = books.filter { it.format == BookEntity.FORMAT_FOLDER }
        if (usable.isEmpty()) {
            toast(R.string.book_mode_only_folders)
            return
        }
        val labels = arrayOf(
            activity.getString(R.string.book_mode_to_single),
            activity.getString(R.string.book_mode_to_serial),
            activity.getString(R.string.book_mode_restore),
        )
        AppDialogBuilder(activity)
            .setTitle(R.string.book_mode_action_title)
            .setItems(labels) { _, which ->
                val target = when (which) {
                    0 -> BookModeBatch.Target.SINGLE
                    1 -> BookModeBatch.Target.SERIAL
                    else -> BookModeBatch.Target.RESTORE
                }
                preview(usable, target)
            }
            .show()
    }

    /**
     * @param onFinished 用户把这次预览处理完（确认执行完、或取消）后回调；
     *   重试要按目标书型排队重读目录，靠它一条条往下走
     */
    private fun preview(
        books: List<BookEntity>,
        target: BookModeBatch.Target,
        onFinished: () -> Unit = {},
    ) {
        val waiting = AppDialogBuilder(activity)
            .setTitle(R.string.book_mode_action_title)
            .setMessage(R.string.book_mode_reading)
            .setCancelable(false)
            .create()
        waiting.show()

        activity.lifecycleScope.launch {
            val result = runCatching { batch.preview(books.map { it.id }, target) }
            if (waiting.isShowing) waiting.dismiss()
            when (val p = result.getOrNull()) {
                null -> {
                    toast(R.string.book_mode_reading_failed)
                    onFinished()
                }
                is BookModeBatch.Preview.Failed -> {
                    toastText(p.message)
                    onFinished()
                }
                is BookModeBatch.Preview.Ready -> {
                    val choices = HashMap<String, BookModePlanner.Choice>()
                    askChoices(p.groups, 0, choices) { confirm(p.groups, choices, p.notes, target, onFinished) }
                }
            }
        }
    }

    // ── 冲突选择：系列 / 封面保留哪一本 ──────────────────────

    private fun askChoices(
        groups: List<BookModeBatch.Group>,
        groupIndex: Int,
        acc: MutableMap<String, BookModePlanner.Choice>,
        then: () -> Unit,
    ) {
        if (groupIndex >= groups.size) {
            then()
            return
        }
        askConflict(groups, groupIndex, 0, acc) { askChoices(groups, groupIndex + 1, acc, then) }
    }

    private fun askConflict(
        groups: List<BookModeBatch.Group>,
        groupIndex: Int,
        conflictIndex: Int,
        acc: MutableMap<String, BookModePlanner.Choice>,
        then: () -> Unit,
    ) {
        val group = groups[groupIndex]
        if (conflictIndex >= group.plan.conflicts.size) {
            then()
            return
        }
        val conflict = group.plan.conflicts[conflictIndex]
        val labels = conflict.options.map { it.label }.toTypedArray()
        val checked = conflict.options.indexOfFirst { it.bookId == conflict.defaultBookId }.coerceAtLeast(0)
        val title = if (conflict.kind == BookModePlanner.ConflictKind.SERIES) {
            R.string.book_mode_series_pick
        } else {
            R.string.book_mode_cover_pick
        }

        AppDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val picked = conflict.options[which]
                val prior = acc[group.rootRef] ?: BookModePlanner.Choice()
                acc[group.rootRef] = when (conflict.kind) {
                    BookModePlanner.ConflictKind.SERIES -> prior.copy(seriesFromBookId = picked.bookId)
                    BookModePlanner.ConflictKind.COVER -> prior.copy(coverFromBookId = picked.bookId)
                }
                askConflict(groups, groupIndex, conflictIndex + 1, acc, then)
            }
            .setOnCancelListener { then() }
            .show()
    }

    // ── 确认与执行 ─────────────────────────────────────────

    private fun confirm(
        groups: List<BookModeBatch.Group>,
        choices: Map<String, BookModePlanner.Choice>,
        notes: List<String>,
        target: BookModeBatch.Target,
        onFinished: () -> Unit,
    ) {
        val text = buildString {
            groups.forEach { append(groupText(it, choices[it.rootRef] ?: BookModePlanner.Choice())) }
            notes.forEach { append("· ").append(it).append('\n') }
        }
        AppDialogBuilder(activity)
            .setTitle(R.string.book_mode_preview_title)
            .setMessage(text.trimEnd())
            .setNegativeButton(R.string.action_cancel) { _, _ -> onFinished() }
            .setPositiveButton(R.string.book_mode_confirm) { _, _ ->
                run(groups.map { Pending(it, choices[it.rootRef] ?: BookModePlanner.Choice(), target) }, onFinished)
            }
            .setOnCancelListener { onFinished() }
            .show()
    }

    private fun groupText(group: BookModeBatch.Group, choice: BookModePlanner.Choice): String {
        val sb = StringBuilder()
        val before = group.oldIds.size
        val after = group.targets.size
        sb.append(
            if (before == after) {
                activity.getString(R.string.book_mode_preview_same, group.rootLabel, before)
            } else {
                activity.getString(R.string.book_mode_preview_group, group.rootLabel, before, after)
            }
        ).append('\n')

        val incoming = group.incoming
        if (incoming.isNotEmpty()) {
            val shown = incoming.take(5).joinToString("、") { "《${it.title}》" } +
                if (incoming.size > 5) "…等 ${incoming.size} 本" else ""
            sb.append(activity.getString(R.string.book_mode_incoming, shown)).append('\n')
        }

        group.plan.conflicts.forEach { conflict ->
            val id = if (conflict.kind == BookModePlanner.ConflictKind.SERIES) {
                choice.seriesFromBookId
            } else {
                choice.coverFromBookId
            }
            val label = conflict.options.firstOrNull { it.bookId == id }?.label
                ?: conflict.options.firstOrNull { it.bookId == conflict.defaultBookId }?.label
                ?: return@forEach
            val line = if (conflict.kind == BookModePlanner.ConflictKind.SERIES) {
                activity.getString(R.string.book_mode_series_keep, label)
            } else {
                activity.getString(R.string.book_mode_cover_keep, label)
            }
            sb.append(line).append('\n')
        }
        sb.append('\n')
        return sb.toString()
    }

    private fun run(pending: List<Pending>, onFinished: () -> Unit) {
        if (pending.isEmpty()) {
            onFinished()
            return
        }
        val waiting = AppDialogBuilder(activity)
            .setTitle(R.string.book_mode_action_title)
            .setMessage(R.string.book_mode_reading)
            .setCancelable(false)
            .create()
        waiting.show()

        activity.lifecycleScope.launch {
            val results = pending.map { p ->
                try {
                    batch.execute(p.group, p.choice)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 一组抛异常只算它自己失败：库还在、别的组还能继续
                    android.util.Log.w("BookMode", "改书型执行失败：${p.group.rootLabel}", e)
                    BookModeBatch.GroupResult(
                        p.group.rootLabel,
                        false,
                        activity.getString(R.string.book_mode_failed_generic),
                    )
                }
            }
            if (waiting.isShowing) waiting.dismiss()
            if (results.any { it.ok }) onChanged()
            showResults(results, pending, onFinished)
        }
    }

    private fun showResults(
        results: List<BookModeBatch.GroupResult>,
        pending: List<Pending>,
        onFinished: () -> Unit,
    ) {
        val failed = results.filterNot { it.ok }
        val text = results.joinToString("\n") { r ->
            if (r.ok) {
                activity.getString(R.string.book_mode_result_ok, r.rootLabel, r.message)
            } else {
                activity.getString(R.string.book_mode_result_fail, r.rootLabel, r.message)
            }
        }
        val builder = AppDialogBuilder(activity)
            .setTitle(R.string.book_mode_result_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> onFinished() }
            .setOnCancelListener { onFinished() }
        if (failed.isNotEmpty()) {
            // 结果与待执行列表一一对应，按位置挑失败的组，别拿书名去对
            val retry = pending.filterIndexed { index, _ -> !results[index].ok }
            builder.setNeutralButton(R.string.book_mode_retry_failed) { _, _ -> retryFailed(retry, onFinished) }
        }
        builder.show()
    }

    /**
     * 重试失败的组：重新读一遍目录再确认，而不是拿预览时的快照硬来一遍。
     *
     * 失败原因大多是「目录变了 / 库里的书变了」，旧快照再跑一次还是同一个失败；
     * 按目标书型分组重新预览，用户看到的书目就是此刻的真实情况。
     */
    private fun retryFailed(failed: List<Pending>, onFinished: () -> Unit) {
        val queue = failed.groupBy { it.target }.map { (target, list) ->
            target to list.flatMap { it.group.selectedIds.toList() }
        }
        fun step(index: Int) {
            if (index >= queue.size) {
                onFinished()
                return
            }
            val (target, ids) = queue[index]
            activity.lifecycleScope.launch {
                val books = batch.books(ids.distinct())
                if (books.isEmpty()) {
                    toast(R.string.book_mode_reading_failed)
                    onFinished()
                    return@launch
                }
                preview(books, target) { step(index + 1) }
            }
        }
        step(0)
    }

    private fun toast(id: Int) {
        Toast.makeText(activity, id, Toast.LENGTH_SHORT).show()
    }

    private fun toastText(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
