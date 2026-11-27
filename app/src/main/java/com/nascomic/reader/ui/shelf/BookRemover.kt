package com.nascomic.reader.ui.shelf

import android.content.Context
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.ui.reader.DiskPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真删一本书：库记录、页缓存、缩略图一起清。源文件一个字节都不动。
 *
 * 返回值是**没删成**的 id：删库抛错就记失败，且不清那本书的缓存——
 * 记录还在，缓存跟着留，下次打开不用重解。调用方只对成功的部分报「已删除」。
 *
 * 只用 applicationContext：撤销窗口到点时页面可能正在重建，
 * 删除不能绑在某个 Activity 的存活上。
 */
object BookRemover {

    /**
     * [db] 默认取进程里那一份；留参数是为了让隔离探针能注入内存库，
     * 对着「删库抛错」这条真路径验证失败传播。
     */
    suspend fun remove(
        context: Context,
        ids: List<Long>,
        db: ReaderDatabase = ReaderDatabase.get(context.applicationContext),
    ): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            val cache = DiskPageCache(app)
            val thumbs = ThumbnailStore(app)
            val failed = mutableListOf<Long>()
            ids.forEach { id ->
                val ok = runCatching { db.bookDao().delete(id) }.isSuccess
                if (ok) {
                    runCatching { cache.clearBook(id) }
                    runCatching { thumbs.invalidate(id) }
                } else {
                    failed += id
                }
            }
            failed
        }
    }
}
