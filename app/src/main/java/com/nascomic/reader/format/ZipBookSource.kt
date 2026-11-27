package com.nascomic.reader.format

import com.nascomic.reader.format.comic.ComicInfoReader
import com.nascomic.reader.format.comic.ComicMeta
import com.nascomic.reader.format.zip.ZipCatalog
import java.io.InputStream

/**
 * 压缩包书源（zip / cbz）。排页规则见 [ArchivePaging]。
 */
class ZipBookSource internal constructor(
    private val catalog: ZipCatalog,
    fallbackTitle: String = "",
    private val rootOnly: Boolean = false,
) : BookSource {

    private val layout = ArchivePaging.layout(
        items = catalog.entries,
        nameOf = { it.name },
        isDirectory = { it.isDirectory },
        fallbackTitle = fallbackTitle,
        rootOnly = rootOnly,
    )

    override val chapters: List<ChapterInfo> get() = layout.chapters

    override val pageCount: Int get() = layout.pages.size

    override fun pageName(page: Int): String = layout.pages[page].name

    internal fun pageIdentity(page: Int): Pair<String, String> = layout.pages[page].let {
        it.name to "${it.crc32}:${it.uncompressedSize}"
    }

    override fun openPage(page: Int): InputStream = catalog.open(layout.pages[page])

    override fun chapterOf(page: Int): Int = layout.chapterOf(page)

    override fun firstPageOfChapter(chapter: Int): Int = layout.firstPageOfChapter(chapter)

    override fun close() = catalog.close()

    /** 包里的 ComicInfo.xml；读过一次就记住，翻页时不再重复解析 */
    override val meta: ComicMeta? by lazy {
        ComicInfoReader.read(catalog.entries.map { it.name }) { name ->
            catalog.entries.firstOrNull { it.name == name }?.let { catalog.open(it) }
        }
    }

    companion object {
        fun open(data: com.nascomic.reader.format.zip.RandomAccessData, fallbackTitle: String = "") =
            ZipBookSource(ZipCatalog.open(data), fallbackTitle)
    }
}
