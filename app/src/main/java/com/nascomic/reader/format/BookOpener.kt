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

    fun open(book: BookEntity, source: SourceEntity, chapters: List<ChapterEntity>): BookSource {
        // 落盘副本只在打开时才需要，顺手按总量收一次：进一本新书是天然的检查点
        runCatching { LocalCopies.trim(context) }
        return when (source.kind) {
            SourceEntity.KIND_WEBDAV -> {
                val client = WebDavClient(source.account, secrets.get(source.secretRef))
                openWith(WebDavBackend(client)) { openRemote(book, chapters, it) }
            }
            SourceEntity.KIND_SMB -> {
                val target = SmbTarget.parse(source.endpoint ?: error("这个来源没有地址"))
                val client = SmbClient(target, source.account, secrets.get(source.secretRef))
                openWith(SmbBackend(client)) { openRemote(book, chapters, it) }
            }
            else -> openLocal(
                book,
                source.treeUri ?: error("这个来源没有目录授权"),
                chapters,
            )
        }
    }

    /** 建好书源，并把 backend 的生命周期绑上去；中途失败也要把连接还掉 */
    private inline fun openWith(backend: RemoteBackend, open: (RemoteBackend) -> BookSource): BookSource =
        try {
            RemoteBookSource(open(backend), backend)
        } catch (e: Throwable) {
            runCatching { backend.close() }
            throw e
        }

    // ---- 远程 ----

    /**
     * 远端只需要这几件事；WebDAV 与 SMB 各实现一份，打开书的逻辑只写一遍。
     *
     * [close] 必须有：SMB 的每一次 open 都是一整个会话（TCP + 认证 + tree connect），
     * 书关掉时不还回去，NAS 上就会攒下一堆没人用的连接——书架滚一圈就是几十个，
     * 到它的会话上限就再也不让连了。
     */
    private interface RemoteBackend : java.io.Closeable {
        fun list(dir: String): List<RemoteItem>
        fun openStream(ref: String): InputStream
        fun randomAccess(ref: String): RandomAccessData
        fun download(ref: String, out: File)
    }

    private class RemoteItem(val ref: String, val name: String, val isDir: Boolean)

    private class WebDavBackend(private val client: WebDavClient) : RemoteBackend {
        override fun list(dir: String): List<RemoteItem> =
            client.list(dir).map { RemoteItem(it.url, it.name, it.isDirectory) }

        override fun openStream(ref: String): InputStream = client.openStream(ref)

        override fun randomAccess(ref: String): RandomAccessData =
            HttpRandomAccess(client, ref, client.size(ref))

        override fun download(ref: String, out: File) = client.download(ref, out)

        /** HTTP 走共享的连接池，没有需要还的东西 */
        override fun close() = Unit
    }

    private class SmbBackend(private val client: SmbClient) : RemoteBackend {
        override fun list(dir: String): List<RemoteItem> =
            client.list(dir).map { RemoteItem(it.path, it.name, it.isDirectory) }

        override fun openStream(ref: String): InputStream = client.openStream(ref)

        override fun randomAccess(ref: String): RandomAccessData =
            SmbRandomAccess(client, ref, client.size(ref))

        override fun download(ref: String, out: File) = client.download(ref, out)

        override fun close() = runCatching { client.close() }.let { }
    }

    /**
     * 书关掉时把远端会话一并还回去。
     *
     * 章与页的书源自己不持有连接（目录书源的 close 是空的），连接在 backend 手里，
     * 所以必须在这层把它们绑在一起。
     */
    private class RemoteBookSource(
        private val inner: BookSource,
        private val backend: RemoteBackend,
    ) : BookSource by inner {
        override fun close() {
            runCatching { inner.close() }
            runCatching { backend.close() }
        }
    }

    /**
     * 远程书：单文件按字节区间读，目录书逐页取流，章回体逐章拼。
     */
    private fun openRemote(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        backend: RemoteBackend,
    ): BookSource {
        if (book.format == BookEntity.FORMAT_FOLDER) {
            if (chapters.isEmpty()) return remoteFolder(backend, book.docRef, book.title)
            return openChaptered(chapters) { ch -> remoteChapter(backend, ch, book) }
        }
        return when (book.format) {
            BookEntity.FORMAT_ZIP, BookEntity.FORMAT_EPUB ->
                openArchiveSource(book) { backend.randomAccess(book.docRef) }
            // PDF 要文件描述符、7z/RAR 要文件路径，都得先落到本地
            else -> openLocalCopy(book) { backend.download(book.docRef, it) }
        }
    }

    /**
     * 只打开能给出封面那一页的最少内容。
     *
     * 生成封面只需要第一页，但 [open] 会把每一章都打开——远程三百章的书
     * 在书架上滚到一次就是三百次请求。章回体这里只建第一章，其余不碰。
     */
    fun openForCover(book: BookEntity, source: SourceEntity, chapters: List<ChapterEntity>): BookSource {
        if (book.format != BookEntity.FORMAT_FOLDER || chapters.size <= 1) {
            return open(book, source, chapters)
        }
        val first = chapters.sortedBy { it.ordinal }.first()
        return when (source.kind) {
            SourceEntity.KIND_WEBDAV -> {
                val client = WebDavClient(source.account, secrets.get(source.secretRef))
                openWith(WebDavBackend(client)) { remoteChapter(it, first, book) }
            }
            SourceEntity.KIND_SMB -> {
                val target = SmbTarget.parse(source.endpoint ?: error("这个来源没有地址"))
                val client = SmbClient(target, source.account, secrets.get(source.secretRef))
                openWith(SmbBackend(client)) { remoteChapter(it, first, book) }
            }
            else -> openLocalChapter(
                SafWalker(context, Uri.parse(source.treeUri ?: error("这个来源没有目录授权"))),
                first,
            )
        }
    }

    /** 一章可以是目录、压缩包，也可以是一份 PDF */
    private fun remoteChapter(
        backend: RemoteBackend,
        ch: ChapterEntity,
        book: BookEntity,
    ): BookSource = when (ch.chapterKind) {
        ChapterEntity.KIND_FOLDER -> remoteFolder(backend, ch.chapterRef, ch.title)
        ChapterEntity.KIND_PDF -> openPdfCopy(book.id, ch.chapterRef) { backend.download(ch.chapterRef, it) }
        else -> openRemoteArchive(backend, ch.chapterRef)
    }

    /** 远程压缩包：先按 zip 试，再按 tar 头；都不成再整份下回本地交给 libarchive */
    private fun openRemoteArchive(backend: RemoteBackend, ref: String): BookSource {
        runCatching { ZipBookSource(ZipCatalog.open(backend.randomAccess(ref))) }
            .getOrNull()?.let { return it }

        runCatching { TarBookSource(TarCatalog.open(backend.randomAccess(ref))) }
            .getOrNull()?.let { return it }

        val dir = LocalCopies.remoteDir(context)
        val local = File(dir, "a" + ref.hashCode().toUInt().toString(16))
        if (!local.exists() || local.length() == 0L) backend.download(ref, local)
        return LibArchiveBookSource(LibArchiveCatalog.open(local))
    }

    /** 远程目录书：递归收集图片，按文件名自然序由 [FolderBookSource] 排好 */
    private fun remoteFolder(backend: RemoteBackend, dir: String, title: String): BookSource {
        val entries = ArrayList<Pair<String, String>>()
        collectRemoteImages(backend, dir, "", entries, 0)
        return FolderBookSource.from(entries, title) { ref ->
            backend.openStream(ref)
        }
    }

    private fun collectRemoteImages(
        backend: RemoteBackend,
        dir: String,
        prefix: String,
        out: MutableList<Pair<String, String>>,
        depth: Int,
    ) {
        if (depth > 2) return
        for (item in backend.list(dir)) {
            val path = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
            when {
                item.isDir -> collectRemoteImages(backend, item.ref, path, out, depth + 1)
                ImageNames.isImage(item.name) -> out += path to item.ref
            }
        }
    }

    /** 远程 PDF 章：下一份到本地再交给系统渲染 */
    private fun openPdfCopy(bookId: Long, ref: String, save: (File) -> Unit): BookSource {
        val dir = LocalCopies.remoteDir(context)
        val local = File(dir, "p${bookId}_" + ref.hashCode().toUInt().toString(16))
        if (!local.exists() || local.length() == 0L) save(local)
        return PdfBookSource(
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
        )
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
        val dir = LocalCopies.remoteDir(context)
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
        val dir = LocalCopies.archivesDir(context)
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

    private fun openChaptered(walker: SafWalker, chapters: List<ChapterEntity>): BookSource =
        openChaptered(chapters) { ch -> openLocalChapter(walker, ch) }

    /** 本地的一章：目录、压缩包或 PDF */
    private fun openLocalChapter(walker: SafWalker, ch: ChapterEntity): BookSource = when (ch.chapterKind) {
        ChapterEntity.KIND_ARCHIVE -> openArchive(walker, ch.chapterRef)
        ChapterEntity.KIND_PDF -> openPdf(walker, ch.chapterRef)
        else -> openFolder(walker, ch.chapterRef, ch.title)
    }

    /**
     * 把若干章拼成一本书。
     *
     * 章的书源用到才建：库里有上次数出来的页数（[ChapterEntity.pageTotal]）就先信它，
     * 这样回来看第二遍不必把每一章都打开一次。第一次看没有页数，才会逐章打开数页。
     */
    private fun openChaptered(
        chapters: List<ChapterEntity>,
        open: (ChapterEntity) -> BookSource,
    ): BookSource {
        val parts = chapters.sortedBy { it.ordinal }.map { ch ->
            MultiChapterSource.Part(
                title = ch.title,
                declaredPages = ch.pageTotal.takeIf { it > 0 } ?: -1,
            ) { open(ch) }
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
        // 读不到就当作这一层没有图：阅读路径上页少一页比整个书打不开好，
        // 扫描路径用 SafWalker 的 null 自行判断要不要报错
        for (node in walker.list(dirId).orEmpty()) {
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
