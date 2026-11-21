package com.nascomic.reader.format

import java.io.Closeable
import java.io.InputStream
import com.nascomic.reader.format.comic.ComicMeta

/**
 * 一本书的页序列来源。上层（阅读器、扫描器）只认这个接口，不关心书是压缩包还是目录。
 *
 * 页码一律 **0 基全局页号**；章号也是 0 基。章与章之间首尾相接，跨章不打断。
 */
interface BookSource : Closeable {

    /** 总页数（已剔除识别不了的条目） */
    val pageCount: Int

    /** 章列表，按阅读顺序。没有子目录时只有一个元素 */
    val chapters: List<ChapterInfo>

    /** 第 [page] 页在容器里的名字，用于调试与日志 */
    fun pageName(page: Int): String

    /** 打开第 [page] 页的字节流。调用方负责关闭 */
    fun openPage(page: Int): InputStream

    /** 第 [page] 页属于哪一章（0 基章号） */
    fun chapterOf(page: Int): Int

    /** 第 [chapter] 章的第一页全局页号 */
    fun firstPageOfChapter(chapter: Int): Int

    /**
     * 包内嵌的元数据（`ComicInfo.xml`）。只有单文件压缩包会读；
     * 目录书、PDF、EPUB、章回体合集一律返回 null——它们要么没有这个文件，要么章与章各带一份，
     * 认哪一份都是猜。
     */
    val meta: ComicMeta?
        get() = null
}

data class ChapterInfo(
    val ordinal: Int,
    val title: String,
    val pageCount: Int,
)
