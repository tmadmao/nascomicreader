package com.nascomic.reader.format

import android.content.Context
import com.nascomic.reader.ui.reader.CacheTrim
import java.io.File

/**
 * 为了让格式能打开而落到本地缓存的那几份副本。
 *
 * 两种来源：SAF 里的 7z / RAR（libarchive 只认文件路径）与远端的 PDF / 7z / RAR（要文件描述符）。
 * 它们都不在「页缓存」的账上——页缓存管的是解好的图，这里管的是原文件。
 * 漫画包体积不小，不设上限的话一个书库就能把应用私有目录塞满。
 *
 * 两份目录一起算总量，超了按最久没用先删；删掉不影响正确性，下次打开重新拷一份。
 */
object LocalCopies {

    /**
     * 两份副本加起来的软上限。
     *
     * 给得比页缓存小，但也不能小气：7z / RAR 必须整份落到本地才能读，
     * 7z / RAR 必须整份落到本地，上限要能同时容下几本单行本才够用。
     */
    const val DEFAULT_BYTES = 2L * 1024 * 1024 * 1024

    /** 上限由设置里的「格式副本上限」决定，默认 2 GB */
    fun limitBytes(context: Context): Long =
        com.nascomic.reader.settings.ReaderPrefs(context).copyCacheGb * 1024L * 1024L * 1024L

    fun archivesDir(context: Context): File = File(context.cacheDir, "archives").apply { mkdirs() }

    fun remoteDir(context: Context): File = File(context.cacheDir, "remote").apply { mkdirs() }

    /** 超上限就按最久没用删一批（[CacheTrim] 会一次收到上限的八成） */
    fun trim(context: Context, limit: Long = limitBytes(context)) {
        runCatching {
            val items = ArrayList<CacheTrim.Item>()
            for (dir in listOf(archivesDir(context), remoteDir(context))) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        // 路径带上目录名：两个目录里可能有同名文件，删的时候要能找回原处
                        items += CacheTrim.Item("${dir.name}/${file.name}", file.length(), file.lastModified())
                    }
                }
            }
            CacheTrim.victims(items, limit).forEach { path ->
                val dir = if (path.startsWith("archives/")) archivesDir(context) else remoteDir(context)
                runCatching { File(dir, path.substringAfter('/')).delete() }
            }
        }
    }

    /**
     * 删掉某几本书的落盘副本。
     *
     * 来源改了地址、或这本书重新扫过之后，旧副本就该作废：副本名只带书 id 与条目引用，
     * 地址换了、同路径的文件换了，名字都还是那一个，留着就会被当成「已经下好了」继续用。
     */
    fun evictBooks(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        runCatching {
            for (dir in listOf(archivesDir(context), remoteDir(context))) {
                dir.listFiles()?.forEach { file ->
                    if (ids.any { file.name.belongsTo(it) }) runCatching { file.delete() }
                }
            }
        }
    }

    /** 副本命名：`b<书 id>_…`（整本）、`a<书 id>_…`（章压缩包）、`p<书 id>_…`（章 PDF） */
    private fun String.belongsTo(bookId: Long): Boolean =
        startsWith("b${bookId}_") || startsWith("a${bookId}_") || startsWith("p${bookId}_")

    /** 删来源、清缓存时整体清掉：这些副本都能重新生成 */
    fun clear(context: Context) {
        runCatching { archivesDir(context).deleteRecursively() }
        runCatching { remoteDir(context).deleteRecursively() }
    }

    fun totalBytes(context: Context): Long = runCatching {
        var sum = 0L
        for (dir in listOf(archivesDir(context), remoteDir(context))) {
            dir.listFiles()?.forEach { if (it.isFile) sum += it.length() }
        }
        sum
    }.getOrDefault(0L)
}
