package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.nascomic.reader.library.cover.ThumbnailStore
import java.util.concurrent.Executors

/**
 * 书架封面的内存缓存与后台解码。
 *
 * 封面文件本来就是小 JPEG，但 `BitmapFactory.decodeFile` 要读盘 + 解压，
 * 放在 `onBindViewHolder` 里就是主线程读盘：一行格子一串解码，滑动和搜索都会卡。
 * 这里把解码挪到一条专用线程上，解好的图按字节数缓存，滚回来直接命中。
 *
 * 尺寸变了（转屏、改列数）就整份作废：按旧尺寸解的图在新格子里会发虚。
 */
class CoverBitmaps(private val store: ThumbnailStore) {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shelf-cover").apply { isDaemon = true }
    }
    private val inFlight = HashSet<Long>()

    /** 上限取堆的八分之一：封面是锦上添花，不该跟正文抢内存 */
    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private var width = 0
    private var height = 0

    /** 列宽变了：缓存里的图尺寸不对了，整份清掉重来 */
    fun setTarget(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        cache.evictAll()
    }

    fun cached(bookId: Long): Bitmap? = cache.get(bookId)

    fun put(bookId: Long, bitmap: Bitmap) {
        cache.put(bookId, bitmap)
    }

    /**
     * 取一张封面。命中缓存直接回调（还是在主线程上）；没命中就去后台解，
     * 解完回主线程。同一本只排一次。
     */
    fun load(bookId: Long, onReady: (Long, Bitmap) -> Unit) {
        cache.get(bookId)?.let {
            onReady(bookId, it)
            return
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        synchronized(inFlight) {
            if (!inFlight.add(bookId)) return
        }
        executor.execute {
            val bitmap = runCatching { store.load(bookId, w, h) }.getOrNull()
            synchronized(inFlight) { inFlight.remove(bookId) }
            if (bitmap == null) return@execute
            // 尺寸在解码期间又变了：这张已经不对了，丢掉别往缓存里塞
            if (w != width || h != height) return@execute
            cache.put(bookId, bitmap)
            main.post { onReady(bookId, bitmap) }
        }
    }

    fun invalidate(bookId: Long) {
        cache.remove(bookId)
    }

    fun clear() {
        cache.evictAll()
    }
}
