package com.nascomic.reader.format

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.nascomic.reader.format.epub.EpubBookSource
import com.nascomic.reader.format.pdf.PdfBookSource
import com.nascomic.reader.format.tar.TarCatalog
import com.nascomic.reader.format.zip.RandomAccessData
import com.nascomic.reader.format.zip.ZipCatalog
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.scan.SafWalker
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 把数据库里的一条书记录变成可读的 [BookSource]。
 *
 * 两种书型在这里合流：
 * - 单行本：压缩包 → [ZipBookSource]；目录 → [FolderBookSource]
 * - 章回体：逐章构造（目录或压缩包），再拼成 [MultiChapterSource]
 */
class BookOpener(private val context: Context) {

    fun open(book: BookEntity, treeUri: String, chapters: List<ChapterEntity>): BookSource {
        val walker = SafWalker(context, Uri.parse(treeUri))
        return when (book.format) {
            BookEntity.FORMAT_ZIP -> openArchive(walker, book.docRef)
            BookEntity.FORMAT_PDF -> openPdf(walker, book.docRef)
            BookEntity.FORMAT_EPUB -> EpubBookSource(openCatalog(documentUri(walker, book.docRef)))
            BookEntity.FORMAT_FOLDER ->
                if (chapters.isEmpty()) {
                    openFolder(walker, book.docRef, book.title)
                } else {
                    openChaptered(walker, chapters)
                }
            else -> throw UnsupportedOperationException("暂不支持这种格式：${book.format}")
        }
    }

    /**
     * 压缩包类书源。扩展名不可靠（cbz 里装 rar、tar 改名 .zip 都常见），
     * 所以按内容判断：先按 zip 的中央目录读，失败再按 tar 的头读。
     */
    private fun openArchive(walker: SafWalker, docId: String): BookSource {
        val uri = documentUri(walker, docId)
        val zip = runCatching { ZipBookSource(openCatalog(uri)) }.getOrNull()
        if (zip != null) return zip

        val pfd = openPfd(uri, docId)
        return try {
            TarBookSource(TarCatalog.open(ChannelRandomAccess(pfd)))
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }
    }

    private fun documentUri(walker: SafWalker, docId: String): Uri =
        android.provider.DocumentsContract.buildDocumentUriUsingTree(
            Uri.parse(walker.treeUriString), docId
        )

    private fun openPfd(uri: Uri, docId: String): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("打不开文件：$docId")

    /** 打开为压缩包目录（epub 也是 zip） */
    private fun openCatalog(uri: Uri): ZipCatalog {
        val pfd = openPfd(uri, uri.toString())
        return try {
            ZipCatalog.open(ChannelRandomAccess(pfd))
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }
    }

    private fun openPdf(walker: SafWalker, docId: String): BookSource =
        PdfBookSource(openPfd(documentUri(walker, docId), docId))

    private fun openFolder(walker: SafWalker, docId: String, title: String): BookSource {
        val entries = listImages(walker, docId, prefix = "")
        return FolderBookSource.from(entries, title) { ref ->
            walker.open(ref) ?: throw IllegalStateException("打不开图片：$ref")
        }
    }

    private fun openChaptered(walker: SafWalker, chapters: List<ChapterEntity>): BookSource {
        val parts = chapters.sortedBy { it.ordinal }.map { ch ->
            val source = if (ch.chapterKind == ChapterEntity.KIND_ARCHIVE) {
                openArchive(walker, ch.chapterRef)
            } else {
                openFolder(walker, ch.chapterRef, ch.title)
            }
            MultiChapterSource.Part(ch.title, source)
        }
        return MultiChapterSource(parts)
    }

    /**
     * 递归收集目录下的图片，返回 (相对路径, docId)。
     * 只走两层：章目录 → 图片；再深的结构当作普通图片序列合并。
     */
    private fun listImages(
        walker: SafWalker,
        dirId: String,
        prefix: String,
        depth: Int = 0,
    ): List<Pair<String, String>> {
        if (depth > 2) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (node in walker.list(dirId)) {
            val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
            when {
                node.isDir -> out += listImages(walker, node.docId, path, depth + 1)
                ImageNames.isImage(node.name) -> out += path to node.docId
            }
        }
        return out
    }
}

/** 用文件描述符做随机读，压缩包按需取字节，不整包进内存 */
private class ChannelRandomAccess(pfd: ParcelFileDescriptor) : RandomAccessData {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel

    override val size: Long get() = channel.size()

    override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        val buf = ByteBuffer.wrap(dest, destOffset, length)
        return channel.read(buf, offset)
    }

    override fun close() {
        runCatching { stream.close() }
    }
}
