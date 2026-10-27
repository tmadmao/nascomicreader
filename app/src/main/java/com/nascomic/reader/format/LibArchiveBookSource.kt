package com.nascomic.reader.format

import com.nascomic.reader.format.archive.LibArchiveCatalog
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 7z / RAR 书源。排页规则与 zip / tar 一致，见 [ArchivePaging]。
 *
 * 差异在读取：libarchive 是单向流，取第 N 页要重开一次走到第 N 个条目，
 * 所以这里按页读整块字节，不做按区间流式。
 */
class LibArchiveBookSource internal constructor(
    private val catalog: LibArchiveCatalog,
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

    override fun openPage(page: Int): InputStream =
        ByteArrayInputStream(catalog.read(layout.pages[page]))

    override fun chapterOf(page: Int): Int = layout.chapterOf(page)

    override fun firstPageOfChapter(chapter: Int): Int = layout.firstPageOfChapter(chapter)

    override fun close() = catalog.close()
}
