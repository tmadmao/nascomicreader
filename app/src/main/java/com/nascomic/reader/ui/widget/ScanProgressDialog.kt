package com.nascomic.reader.ui.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.nascomic.reader.R

/**
 * 扫描进度：一个小弹窗，先说「正在列目录」，列完再报「已读 120 / 1600 本」。
 *
 * 远端书一本本地数，一千多本要好一会儿；没有反馈用户只会以为卡死了。
 * 弹窗不可取消——扫描中途掐断会留下半本书架。
 */
class ScanProgressDialog(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

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

    private val dialog = AlertDialog.Builder(this.context)
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
        .setCancelable(false)
        .create()

    fun showListing(name: String) = onMain {
        label.text = context.getString(R.string.scan_listing, name)
        bar.isIndeterminate = true
        if (!dialog.isShowing) dialog.show()
    }

    fun progress(done: Int, total: Int) = onMain {
        if (total <= 0) return@onMain
        bar.isIndeterminate = false
        bar.max = total
        bar.progress = done
        label.text = context.getString(R.string.scan_progress, done, total)
        if (!dialog.isShowing) dialog.show()
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
