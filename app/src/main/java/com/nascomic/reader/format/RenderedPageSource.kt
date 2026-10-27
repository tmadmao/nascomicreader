package com.nascomic.reader.format

import android.graphics.Bitmap

/**
 * 页面不是现成图片、要按尺寸现场画出来的来源（PDF 这类）。
 *
 * [BookSource.openPage] 给的是图片字节流，PDF 没有这条路径，所以单独开一个口子：
 * 阅读页与封面生成先看来源是不是本接口，是就直接渲染。
 */
interface RenderedPageSource {

    /** 把第 [page] 页画成不超过 [width] × [height] 的位图；失败返回 null */
    fun renderPage(page: Int, width: Int, height: Int): Bitmap?
}
