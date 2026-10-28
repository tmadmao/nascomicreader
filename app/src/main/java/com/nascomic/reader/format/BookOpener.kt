package com.nascomic.reader.format

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.nascomic.reader.format.archive.LibArchiveCatalog
import com.nascomic.reader.format.epub.EpubBookSource
import com.nascomic.reader.format.pdf.PdfBookSource
import com.nascomic.reader.format.tar.TarCatalog
import com.nascomic.reader.smb.SmbClient
import com.nascomic.reader.smb.SmbRandomAccess
import com.nascomic.reader.smb.SmbTarget
import com.nascomic.reader.webdav.HttpRandomAccess
import com.nascomic.reader.webdav.WebDavClient
import com.nascomic.reader.format.zip.RandomAccessData
import com.nascomic.reader.format.zip.ZipCatalog
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.scan.SafWalker
import java.io.File
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

    private val secrets by lazy { com.nascomic.reader.settings.SecretStore(context) }

    fun open(book: BookEntity, source: SourceEntity, chapters: List<ChapterEntity>): BookSource =
        when (source.kind) {
            SourceEntity.KIND_WEBDAV -> openWebDav(book, source)
            SourceEntity.KIND_SMB -> openSmb(book, source)
            else -> openLocal(
                book,
                source.treeUri ?: error("这个来源没有目录授权"),
                chapters,
            )
        }

    // ---- 远程 ----

    private fun openWebDav(book: BookEntity, source: SourceEntity): BookSource {
        val client = WebDavClient(source.account, secrets.get(source.secretRef))
        val url = book.docRef
        return when (book.format) {
            BookEntity.FORMAT_ZIP, BookEntity.FORMAT_EPUB ->
                openArchiveSource(book) { HttpRandomAccess(client, url, client.size(url)) }
            // PDF 要文件描述符、7z/RAR 要文件路径，都得先落到本地
            else -> openLocalCopy(book) { client.download(url, it) }
        }
    }

    private fun openSmb(book: BookEntity, source: SourceEntity): BookSource {
        val target = SmbTarget.parse(source.endpoint ?: error("这个来源没有地址"))
        val client = SmbClient(target, source.account, secrets.get(source.secretRef))
        val path = book.docRef
        return when (book.format) {
            BookEntity.FORMAT_ZIP, BookEntity.FORMAT_EPUB ->
                openArchiveSource(book) { SmbRandomAccess(client, path, client.size(path)) }
            else -> openLocalCopy(book) { client.download(path, it) }
        }
    }

    /**
     * 能按字节区间读的格式：zip / tar / epub 共用同一套解析，只是底层换成了
     * HTTP 区间请求或 SMB 读。两种来源的差别到此为止。
     */
    private fun openArchiveSource(book: BookEntity, data: () -> RandomAccessData): BookSource {
        val open = data()
        return when (book.format) {
            BookEntity.FORMAT_EPUB -> EpubBookSource(ZipCatalog.open(open))
            // 扩展名靠不住，按内容试：zip 中央目录认不出就按 tar 头读
            else -> runCatching { ZipBookSource(ZipCatalog.open(open)) }
                .onFailure { android.util.Log.d("BookOpener", "不是 zip，改试 tar：${book.title}") }
                .getOrNull()
                ?: TarBookSource(TarCatalog.open(open))
        }
    }

    /**
     * 必须落到本地文件的格式：PDF 走 `ParcelFileDescriptor`、libarchive 只认文件名。
     * 缓存名带书的 id 与大小，远端文件换了会重新下一份。
     */
    private fun openLocalCopy(book: BookEntity, save: (File) -> Unit): BookSource {
        val local = cachedCopy(book, save)
        return when (book.format) {
            BookEntity.FORMAT_PDF ->
                PdfBookSource(ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY))
            else -> LibArchiveBookSource(LibArchiveCatalog.open(local))
        }
    }

    private fun cachedCopy(book: BookEntity, save: (File) -> Unit): File {
        val dir = File(context.cacheDir, "remote").apply { mkdirs() }
        val name = "b${book.id}_" + book.byteSize.takeIf { it > 0 }
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return out
        save(out)
        return out
    }

    // ---- 本地（SAF）----

    private fun openLocal(book: BookEntity, treeUri: String, chapters: List<ChapterEntity>): BookSource {
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
     * 所以按内容判断：zip 中央目录 → tar 头 → 交给 libarchive（7z / RAR）。
     */
    private fun openArchive(walker: SafWalker, docId: String): BookSource {
        val uri = documentUri(walker, docId)

        val zip = runCatching { ZipBookSource(openCatalog(uri)) }.getOrNull()
        if (zip != null) return zip

        val pfd = openPfd(uri, docId)
        val tar = runCatching { TarBookSource(TarCatalog.open(ChannelRandomAccess(pfd))) }.getOrNull()
        if (tar != null) return tar
        runCatching { pfd.close() }

        // libarchive 只能按文件名重开，先把文件放到本地缓存
        val local = copyToCache(uri, docId)
        return LibArchiveBookSource(LibArchiveCatalog.open(local))
    }

    /**
     * 把 SAF 里的文件复制到 cacheDir。名字带上大小与修改时间，文件变了就换一份。
     */
    private fun copyToCache(uri: Uri, docId: String): File {
        val dir = File(context.cacheDir, "archives").apply { mkdirs() }
        val stamp = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "0"
                val size = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val mtime = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val s = if (size >= 0) cursor.getLong(size) else 0L
                val m = if (mtime >= 0) cursor.getLong(mtime) else 0L
                "${s}_$m"
            } ?: "0"
        }.getOrDefault("0")

        val name = docId.hashCode().toUInt().toString(16) + "_" + stamp
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return out

        val pfd = openPfd(uri, docId)
        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
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
