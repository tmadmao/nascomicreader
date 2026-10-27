package com.nascomic.reader.format

import com.nascomic.reader.format.tar.TarCatalog
import java.io.InputStream

/**
 * tar 书源。排页规则与 zip 一致，见 [ArchivePaging]；差别只在读条目的方式。
 */
class TarBookSource internal constructor(
    private val catalog: TarCatalog,
    fallbackTitle: String = "",
) : BookSource {

    private val layout = ArchivePaging.layout(
        items = catalog.entries,
        nameOf = { it.name },
        isDirectory = { it.isDirectory },
        fallbackTitle = fallbackTitle,
    )

    override val chapters: List<ChapterInfo> get() = layout.chapters

    override val pageCount: Int get() = layout.pages.size

    override fun pageName(page: Int): String = layout.pages[page].name

    override fun openPage(page: Int): InputStream = catalog.open(layout.pages[page])

    override fun chapterOf(page: Int): Int = layout.chapterOf(page)

    override fun firstPageOfChapter(chapter: Int): Int = layout.firstPageOfChapter(chapter)

    override fun close() = catalog.close()

    companion object {
        fun open(data: com.nascomic.reader.format.zip.RandomAccessData, fallbackTitle: String = "") =
            TarBookSource(TarCatalog.open(data), fallbackTitle)
    }
}
