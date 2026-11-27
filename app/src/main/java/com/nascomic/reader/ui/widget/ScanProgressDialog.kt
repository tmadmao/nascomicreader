package com.nascomic.reader.ui.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.nascomic.reader.R

/**
 * 扫描进度：列目录和写入时可取消；提交时关闭取消入口。
 */
class ScanProgressDialog(
    private val context: Context,
    /** 用户点「取消扫描」；扫描在下一个检查点停下，库不动 */
    private val onCancel: (() -> Unit)? = null,
) {

    private val main = Handler(Looper.getMainLooper())
    private var progressLocked = false

    private val label = TextView(this.context).apply {
        textSize = 13f
        setTextColor(context.getColor(R.color.shell_ink_muted))
        gravity = Gravity.CENTER
    }

    private val bar = ProgressBar(
        this.context, null, android.R.attr.progressBarStyleHorizontal
    ).apply {
        isIndeterminate = true
        max = 100
        indeterminateTintList = android.content.res.ColorStateList.valueOf(
            context.getColor(R.color.brand_accent)
        )
        progressTintList = android.content.res.ColorStateList.valueOf(
            context.getColor(R.color.brand_accent)
        )
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
            context.getColor(R.color.shell_divider)
        )
    }

    private val dialog = AppDialogBuilder(this.context)
        .setTitle(R.string.scan_title)
        .setView(
            LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
                addView(label)
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(14) },
                )
            }
        )
        .setNegativeButton(R.string.scan_cancel, null)
        .setCancelable(false)
        .create()

    /** 取消按钮：只置标记，真正的停手由扫描器在下一个检查点完成 */
    private fun wireCancel() {
        val button = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
        button.isEnabled = onCancel != null
        button.setOnClickListener {
            if (onCancel == null) return@setOnClickListener
            progressLocked = true
            button.isEnabled = false
            label.text = context.getString(R.string.scan_cancelling)
            onCancel.invoke()
        }
    }

    fun showListing(name: String) = onMain {
        if (progressLocked) return@onMain
        label.text = context.getString(R.string.scan_listing, name)
        bar.isIndeterminate = true
        if (!dialog.isShowing) {
            dialog.show()
            wireCancel()
        }
    }

    fun progress(done: Int, total: Int) = onMain {
        if (total <= 0 || progressLocked) return@onMain
        bar.isIndeterminate = false
        bar.max = total
        bar.progress = done
        label.text = context.getString(R.string.scan_progress, done, total)
    }

    /** 由扫描器在主线程同步调用。 */
    fun committing() {
        progressLocked = true
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        label.setText(R.string.scan_committing)
    }

    fun dismiss() = onMain {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
