package com.nascomic.reader.ui.widget

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 让页面内容躲开状态栏与导航条。
 *
 * Android 15 起目标版本够高的应用会被强制边到边——系统不再替应用收边，
 * 不自己让位就会出现「标题压在状态栏下面」。这里按系统栏的实际高度补 padding，
 * 老系统上系统已经收过边、这两个值是 0，补上去也没有副作用。
 */
fun View.padBySystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}
