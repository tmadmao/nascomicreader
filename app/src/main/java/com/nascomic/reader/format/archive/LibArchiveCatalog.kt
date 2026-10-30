package com.nascomic.reader.format.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 7z / RAR 这类需要 libarchive 才能读的压缩包。
 *
 * libarchive 是**单向流**：开了只能往前读，不能回头。所以：
 * - 列目录：开一次，从头 `readNextHeader` 走到底
 * - 读页：留一个会话，从当前位置往前走；只有当请求的页排在当前位置之前才重开
 *
 * 早先是每读一页都从包首重走，读到第 200 页就要重走 200 个条目——
 * 7z 的条目是连着压的，跳过也得解一遍，于是翻页越来越慢。留一个会话以后，
 * 正常往后读是 O(1)，往回翻才重开一次。
 *
 * 输入用文件而不是 fd：同一个 fd 被复制会共享偏移，重开时偏移不对；
 * 文件可以随时按名重开。调用方负责把这个文件放到能读的位置（通常是 cacheDir）。
 */
class LibArchiveCatalog private constructor(
    private val file: File,
    val entries: List<Entry>,
) {

    data class Entry(
        val name: String,
        val size: Long,
        val index: Int,
        val isDirectory: Boolean,
    )

    /** 读会话与它的锁。两个预载 worker 同时要读时排队，7z 本来也没法并行 */
    private val lock = Any()
    private var session = 0L

    /** 会话当前停在第几个条目的头上；-1 = 没开或已走完 */
    private var cursor = -1

    /** 当前条目的数据还没取走（取走后要先 readNextHeader 才能到下一个） */
    private var dataPending = false

    /** 读出第 [entry] 个条目的全部字节。图片一般几百 KB，整块读比流式简单也够快 */
    fun read(entry: Entry): ByteArray {
        if (entry.size <= 0 || entry.size > MAX_ENTRY_BYTES) return ByteArray(0)
        synchronized(lock) {
            val first = runCatching { readUsingSession(entry) }.getOrNull()
            if (first != null) return first
            // 会话走岔了就整段重来一次：单次失败不值得把整本书判死
            resetSession()
            return runCatching { readUsingSession(entry) }.getOrNull() ?: ByteArray(0)
        }
    }

    private fun readUsingSession(entry: Entry): ByteArray? {
        // 没开会话，或者要读的排在当前位置之前（流回不去）：重开
        if (session == 0L || cursor < 0 || cursor > entry.index) openSession()
        if (session == 0L) return null

        while (cursor < entry.index) {
            if (!advance()) return null
        }
        if (cursor != entry.index || !dataPending) return null

        val bytes = readAll(session, entry.size)
        // 数据取走了，下一个条目要先读头；位置留在原地，下次 advance 会读到下一个
        dataPending = false
        return bytes
    }

    /** 跳过当前条目的数据，读下一个头 */
    private fun advance(): Boolean {
        if (session == 0L) return false
        if (dataPending) {
            Archive.readDataSkip(session)
            dataPending = false
        }
        val header = Archive.readNextHeader(session)
        if (header == 0L) {
            cursor = -1
            return false
        }
        cursor++
        dataPending = true
        return true
    }

    private fun openSession() {
        resetSession()
        val archive = Archive.readNew()
        try {
            Archive.setCharset(archive, "UTF-8".toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(), 16384)
            val header = Archive.readNextHeader(archive)
            if (header == 0L) {
                runCatching { Archive.free(archive) }
                return
            }
            session = archive
            cursor = 0
            dataPending = true
        } catch (e: Exception) {
            runCatching { Archive.free(archive) }
            throw e
        }
    }

    private fun resetSession() {
        val old = session
        session = 0L
        cursor = -1
        dataPending = false
        if (old != 0L) runCatching { Archive.free(old) }
    }

    fun close() {
        synchronized(lock) { resetSession() }
    }

    private fun <T> openArchive(block: (Long) -> T): T {
        val archive = Archive.readNew()
        try {
            Archive.setCharset(archive, "UTF-8".toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, file.absolutePath.toByteArray(), 16384)
            return block(archive)
        } finally {
            runCatching { Archive.free(archive) }
        }
    }

    /**
     * 读完一个条目的数据。`readData` 会把 buffer.position 设成本次读到的字节数，
     * 读到底时 position 为 0（不抛异常）——这是实测出来的，见《工程环境》的探针记录。
     */
    private fun readAll(archive: Long, expected: Long): ByteArray {
        val total = expected.toInt()
        val out = ByteArray(total)
        val buffer = ByteBuffer.allocateDirect(minOf(total, CHUNK).coerceAtLeast(1))
        var written = 0
        while (written < total) {
            buffer.clear()
            val code = try {
                Archive.readData(archive, buffer)
                0
            } catch (e: Exception) {
                Archive.ERRNO_FATAL
            }
            val got = buffer.position()
            if (code != 0 || got <= 0) break
            buffer.position(0)
            buffer.get(out, written, got)
            written += got
        }
        return if (written == total) out else out.copyOf(written)
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        fun open(file: File): LibArchiveCatalog {
            val catalog = LibArchiveCatalog(file, emptyList())
            val entries = catalog.list()
            if (entries.isEmpty()) throw IOException("压缩包是空的或格式不认识")
            return LibArchiveCatalog(file, entries)
        }

        private fun LibArchiveCatalog.list(): List<Entry> = openArchive { archive ->
            val out = ArrayList<Entry>()
            var index = 0
            var header = Archive.readNextHeader(archive)
            while (header != 0L && out.size < 200_000) {
                val name = ArchiveEntry.pathname(header)?.toString(Charsets.UTF_8).orEmpty()
                val stat = ArchiveEntry.stat(header)
                val isDir = ArchiveEntry.filetype(header) == ArchiveEntry.AE_IFDIR
                if (name.isNotEmpty()) {
                    out += Entry(name.trimEnd('/'), stat.stSize, index, isDir)
                }
                Archive.readDataSkip(archive)
                index++
                header = Archive.readNextHeader(archive)
            }
            out
        }
    }
}
