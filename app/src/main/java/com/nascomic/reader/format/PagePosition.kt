package com.nascomic.reader.format

/**
 * 页码的展示口径。
 *
 * 内部一律用 0 基全局页号，只有给人看的时候才换算成本类：
 * 章回体看「本章第几页 / 本章共几页」，单行本看全书页数。
 */
data class PagePosition(
    /** 0 基章号；单行本恒为 0 */
    val chapterOrdinal: Int,
    val chapterTitle: String,
    /** 1 基，章内页号 */
    val page: Int,
    /** 该章总页数；单行本为全书页数 */
    val total: Int,
    /** 是否按章展示 */
    val chaptered: Boolean,
)

/**
 * 换算 [page] 的展示位置。
 *
 * @param chaptered 这本书是否有章的概念。由书的形态决定（单行本没有章），
 *   不能靠 [BookSource.chapters] 的个数判断——单行本也会有一个占位章。
 */
fun BookSource.pagePosition(page: Int, chaptered: Boolean): PagePosition {
    if (!chaptered) {
        return PagePosition(0, "", page + 1, pageCount, false)
    }
    val index = chapterOf(page)
    val info = chapters.getOrNull(index)
    val start = firstPageOfChapter(index)
    return PagePosition(
        chapterOrdinal = index,
        chapterTitle = info?.title.orEmpty(),
        page = page - start + 1,
        total = info?.pageCount ?: (pageCount - start),
        chaptered = true,
    )
}
