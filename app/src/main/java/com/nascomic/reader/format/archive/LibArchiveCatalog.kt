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
 * - 读某一页：重开一次，走到第 N 个条目再把数据读出来
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

    /** 读出第 [entry] 个条目的全部字节。图片一般几百 KB，整块读比流式简单也够快 */
    fun read(entry: Entry): ByteArray {
        if (entry.size <= 0 || entry.size > MAX_ENTRY_BYTES) return ByteArray(0)
        return openArchive { archive ->
            var current = 0
            var header = Archive.readNextHeader(archive)
            while (header != 0L) {
                if (current == entry.index) return@openArchive readAll(archive, entry.size)
                Archive.readDataSkip(archive)
                current++
                header = Archive.readNextHeader(archive)
            }
            ByteArray(0)
        }
    }

    fun close() = Unit

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
