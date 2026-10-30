package com.nascomic.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 页的磁盘缓存：解好的页留一份在应用私有目录，退出阅读再进来不必重新下载、重新解码。
 *
 * 只给远程书用——本地文件再读一遍本来就快，不值得占那份空间。
 * 目录按书分开；文件名里带目标尺寸、裁边参数、压缩质量与书的大小，
 * 参数一变就落到新键上，不会把按旧参数裁过的图当新的用。
 *
 * 上限由外面给（见 [com.nascomic.reader.settings.ReaderPrefs.diskBudgetBytes]）：
 * 总量默认 5 GB，单本不超过总量的四分之一，免得一本书把整个池子吃光。
 */
class DiskPageCache(
    context: Context,
    private val budgetBytes: Long = DEFAULT_TOTAL_BYTES,
) {

    private val root = File(context.filesDir, DIR)

    /** 单本上限：总量够大时按四分之一给，但至少留 256 MB，不然一本稍大的单行本就装不下 */
    private val perBookBytes: Long =
        (budgetBytes / 4).coerceIn(DEFAULT_PER_BOOK_BYTES, budgetBytes)

    /** 自上次清理以来写了几页：满 [TRIM_EVERY] 一收 */
    private val writes = java.util.concurrent.atomic.AtomicInteger(0)

    /** 读一页。命中就把修改时间往后推，淘汰时它不会那么早被挑中 */
    fun load(bookId: Long, key: String): Bitmap? = runCatching {
        val file = File(dirOf(bookId), key)
        if (!file.isFile || file.length() == 0L) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        file.setLastModified(System.currentTimeMillis())
        bitmap
    }.getOrNull()

    fun save(bookId: Long, key: String, bitmap: Bitmap, quality: Int = QUALITY): Boolean = runCatching {
        val dir = dirOf(bookId).apply { if (!exists()) mkdirs() }
        if (!dir.isDirectory) return false
        val target = File(dir, key)
        val tmp = File(dir, "$key.tmp")
        // 先写临时文件再改名：中途被杀掉不会留下半张图
        tmp.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return false
        }
        if (target.exists()) target.delete()
        val ok = tmp.renameTo(target)
        // 每写满一批收一次：每写一页都扫目录太亏，一直不收又会撑爆存储
        if (ok && writes.incrementAndGet() % TRIM_EVERY == 0) trim(bookId)
        ok
    }.getOrDefault(false)

    /**
     * 超上限就按最久没用丢一批，一次收到上限的八成，免得每写一页都把目录扫一遍。
     * 先管单本，再管总量。
     */
    fun trim(bookId: Long) {
        runCatching {
            val dir = dirOf(bookId)
            dir.listFiles()?.filter { it.isFile }?.map {
                CacheTrim.Item(it.name, it.length(), it.lastModified())
            }?.let { mine ->
                CacheTrim.victims(mine, perBookBytes).forEach { File(dir, it).delete() }
            }

            val all = ArrayList<CacheTrim.Item>()
            root.listFiles()?.forEach { book ->
                if (!book.isDirectory) return@forEach
                book.listFiles()?.forEach { file ->
                    if (file.isFile) all += CacheTrim.Item("${book.name}/${file.name}", file.length(), file.lastModified())
                }
            }
            CacheTrim.victims(all, budgetBytes).forEach { File(root, it).delete() }
        }
    }

    fun clearBook(bookId: Long) {
        dirOf(bookId).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    /** 磁盘上现在占了多少字节，设置页要显示 */
    fun totalBytes(): Long = runCatching {
        var sum = 0L
        root.listFiles()?.forEach { book ->
            if (book.isDirectory) {
                book.listFiles()?.forEach { if (it.isFile) sum += it.length() }
            } else if (book.isFile) {
                sum += book.length()
            }
        }
        sum
    }.getOrDefault(0L)

    /**
     * 清掉旧键格式的页。
     *
     * 0.7 及以前的文件名里没有压缩质量段，现在的键永远查不到它们——留着只是白占空间。
     * 升级后跑一次，把这份历史清掉。
     */
    fun purgeLegacy(): Int = runCatching {
        var removed = 0
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.contains("-q")) {
                    if (file.delete()) removed++
                }
            }
        }
        removed
    }.getOrDefault(0)

    /** 每本书占多少字节：缓存明细按书库汇总时用 */
    fun bytesByBook(): Map<Long, Long> = runCatching {
        val out = HashMap<Long, Long>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val id = dir.name.toLongOrNull() ?: return@forEach
            var sum = 0L
            dir.listFiles()?.forEach { if (it.isFile) sum += it.length() }
            if (sum > 0) out[id] = sum
        }
        out
    }.getOrDefault(emptyMap())

    private fun dirOf(bookId: Long) = File(root, bookId.toString())

    companion object {
        private const val DIR = "page-cache"

        /** JPEG 88：漫画页的色块与线稿都撑得住，再高只是白占空间 */
        const val QUALITY = 88

        /** 原图显示时用的质量：放大看要经得住，二次压缩的痕迹不能比原图明显 */
        const val QUALITY_ORIGINAL = 96

        /** 默认的单本上限（总量 512 MB 时用）。总上限变大后会跟着放大到总量的四分之一 */
        const val DEFAULT_PER_BOOK_BYTES = 256L * 1024 * 1024

        /** 默认的总上限，调用方一般会按可用空间给一个更大的值 */
        const val DEFAULT_TOTAL_BYTES = 512L * 1024 * 1024

        /** 每写这么多页收一次。按一页 0.4 MB 算，约 16 MB 检查一次目录 */
        private const val TRIM_EVERY = 40
    }
}

/**
 * 文件名口径：页号 + 目标尺寸 + 裁边参数 + 压缩质量 + 书的大小戳。
 *
 * 拆出来是因为这部分不碰文件系统，能在 JVM 上直接测。
 * 书的大小戳（[stamp]）是为了远程书被替换成另一份内容时不再命中旧页——
 * 同名不同内容的重扫不会换 bookId，只有这个戳能区分。
 */
object PageCacheKey {
    fun of(
        index: Int,
        width: Int,
        height: Int,
        trim: Boolean,
        level: Int,
        quality: Int = DiskPageCache.QUALITY,
        stamp: Long = 0L,
    ): String {
        val prefix = if (stamp > 0L) "s$stamp-" else ""
        return "$prefix" + "p%05d-%dx%d-t%s%d-q%d.jpg".format(
            index, width, height, if (trim) "1" else "0", level, quality,
        )
    }
}

/**
 * 淘汰规则：按最久没用先删，直到总量降到上限的八成。
 *
 * 只挑出该删的路径，不碰文件——真正的删除留给调用方。
 * 全是纯算术，单测直接喂一组「大小 + 最后使用时间」就能验。
 */
object CacheTrim {

    class Item(val path: String, val bytes: Long, val lastUsed: Long)

    fun victims(items: List<Item>, keepBytes: Long): List<String> {
        var total = items.sumOf { it.bytes }
        if (total <= keepBytes) return emptyList()

        // 一次多删一点：收到八成，接下来一段时间都不用再扫
        val target = keepBytes * 8 / 10
        val out = ArrayList<String>()
        for (item in items.sortedBy { it.lastUsed }) {
            if (total <= target) break
            out += item.path
            total -= item.bytes
        }
        return out
    }
}
