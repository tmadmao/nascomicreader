package com.nascomic.reader.ui.widget

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 全应用对话框的统一构建器。
 *
 * 直接写 `AlertDialog.Builder` 会走 AppCompat 那套方角默认样式，主题里的圆角和纸白用不上；
 * 这里换成 Material 的构建器，样式统一从 `Theme.NasComic.MaterialDialog` 来
 * （纸白底、22dp 圆角、17sp 粗标题、墨色正文）。
 *
 * 调用方式与原来的 `AlertDialog.Builder(context)` 完全一样，只是换个类名。
 */
class AppDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {

    /**
     * 背景在创建时直接换掉：Material 3 默认的 surface 会叠一层主色调（泛粉），
     * 圆角也不一定是我们要的 22dp。窗口背景换成纸白圆角图，所有对话框一个样。
     */
    override fun create(): androidx.appcompat.app.AlertDialog {
        val dialog = super.create()
        dialog.window?.setBackgroundDrawableResource(com.nascomic.reader.R.drawable.bg_dialog_white)
        return dialog
    }
}
