package com.nascomic.reader.ui.widget

import android.content.Context

/**
 * 列表式菜单的统一入口。
 *
 * 页面里原来用 AppCompat 的 `AlertDialog.Builder`，它只认平台那套方角背景，
 * 主题里配的 `Shape.Dialog`（22dp 圆角）根本用不上——所以长按菜单看着像系统默认。
 * 换成 Material 的构建器，圆角、白面、墨色文字都从 `Theme.NasComic.Dialog` 来；
 * 以后菜单样式要改，也只动主题这一处。
 */
object AppDialogs {

    fun menu(
        context: Context,
        title: CharSequence?,
        items: List<CharSequence>,
        onPick: (Int) -> Unit,
    ) {
        if (items.isEmpty()) return
        AppDialogBuilder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .show()
    }
}
