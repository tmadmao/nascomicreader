package com.nascomic.reader.library.scan

import com.nascomic.reader.format.ImageNames

/**
 * 扫描时的判定规则。抽成纯函数方便单测——真机跑一遍要几十秒，这里几毫秒能试完边界。
 *
 * 两种书型（按用户的实际书库结构定义）：
 *
 * **单行本**：一本就是一个压缩包，或一个「里面直接是图片」的文件夹。没有章。
 *
 * **章回体**：一个文件夹，它的子项才是章——每个子文件夹一章，或每个压缩包一章。
 * 例：某作品/第1话/（一堆图）、某作品/第2话.zip 都算同一本书的章。
 */
object ScanRules {

    private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "tar", "7z", "rar", "cbr")
    private val PDF_EXTENSIONS = setOf("pdf")

    fun isArchive(name: String): Boolean = extensionOf(name) in ARCHIVE_EXTENSIONS

    /** PDF 自成一本书，不参与章回体划分 */
    fun isPdf(name: String): Boolean = extensionOf(name) in PDF_EXTENSIONS

    /** EPUB 同样自成一本书 */
    fun isEpub(name: String): Boolean = extensionOf(name) == "epub"

    /** 能直接当一本书的单个文件 */
    fun isSingleBookFile(name: String): Boolean = isArchive(name) || isPdf(name) || isEpub(name)

    fun isImage(name: String): Boolean = ImageNames.isImage(name)

    /** 目录里直接有图片 */
    fun hasImages(children: List<ScanNode>): Boolean =
        children.any { !it.isDir && isImage(it.name) }

    /**
     * 目录属于哪一种。
     *
     * - [FolderKind.DIRECT_PAGES]：直接含图片 → 这是单行本，别再往下钻
     * - [FolderKind.BY_CHAPTER]：不含图片，但子项是子目录或压缩包 → 这是章回体的一本书
     * - [FolderKind.CONTAINER]：只装着下级目录，既不是书也不是章回体 → 继续往下找
     */
    fun folderKind(children: List<ScanNode>): FolderKind {
        if (children.isEmpty()) return FolderKind.EMPTY
        if (hasImages(children)) return FolderKind.DIRECT_PAGES

        val hasChapterUnit = children.any { it.isDir } || children.any { !it.isDir && isArchive(it.name) }
        return if (hasChapterUnit) FolderKind.BY_CHAPTER else FolderKind.EMPTY
    }

    /** 章回体目录里，哪些子项构成章；按名字自然序返回 */
    fun chapterUnitsOf(children: List<ScanNode>): List<ScanNode> =
        children
            .filter { it.isDir || isArchive(it.name) }
            .sortedWith { a, b ->
                com.nascomic.reader.core.text.NaturalSort.nameKey(a.name)
                    .compareTo(com.nascomic.reader.core.text.NaturalSort.nameKey(b.name))
            }

    /** 目录的直接子项里，可作为「书」的条目 */
    fun bookCandidates(children: List<ScanNode>): List<ScanNode> =
        children.filter { it.isDir || isArchive(it.name) }

    /**
     * 显示名。文件去掉扩展名；**目录保留原名**——
     * 目录叫「5.1」时不能把 .1 当扩展名切掉。
     */
    fun titleOf(node: ScanNode): String =
        if (node.isDir) node.name else ImageNames.baseName(node.name)

    /** 已知是文件时用这个 */
    fun titleOf(name: String): String = ImageNames.baseName(name)

    enum class FolderKind {
        /** 直接是图片页：单行本 */
        DIRECT_PAGES,

        /** 子项是章：章回体 */
        BY_CHAPTER,

        /** 空目录 */
        EMPTY,
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }
}

/** 扫描途中对文件系统的一项的描述，与平台 API 解耦 */
data class ScanNode(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
)
