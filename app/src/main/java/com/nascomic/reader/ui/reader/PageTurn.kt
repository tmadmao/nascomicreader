package com.nascomic.reader.ui.reader

/**
 * 翻页的算术：从当前页往后跨一屏，落在哪一页。
 *
 * 单行本最后一页之后没有内容，双页并排时一屏两页，所以「后面还有没有」要按
 * 一屏的步进算，不能只看当前页是不是最后一页。这里只算页码，解码与绘制在阅读页。
 */
object PageTurn {

    /**
     * 前进一屏的目标页。返回 null 表示本屏已经是最后一屏，后面没有内容了——
     * 调用方据此进「书的尾」。
     */
    fun nextPage(page: Int, step: Int, total: Int): Int? {
        if (total <= 0 || page >= total) return null
        val target = page + step.coerceAtLeast(1)
        return if (target >= total) null else target
    }
}
