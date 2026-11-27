package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.nascomic.reader.library.cover.ThumbnailStore
import java.util.concurrent.Executors
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.cover.ThumbnailStore.Key

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
    private val inFlight = HashSet<Key>()

    /** 上限取堆的八分之一：封面是锦上添花，不该跟正文抢内存 */
    private val cache = object : LruCache<Key, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
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

    fun key(book: BookEntity): Key = store.key(book)

    fun cached(book: BookEntity): Bitmap? = cache.get(key(book))

    fun put(book: BookEntity, bitmap: Bitmap) {
        cache.put(key(book), bitmap)
    }

    fun load(book: BookEntity, onReady: (BookEntity, Bitmap) -> Unit) {
        val key = key(book)
        cache.get(key)?.let {
            onReady(book, it)
            return
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        executor.execute {
            val bitmap = runCatching { store.load(key, w, h) }.getOrNull()
            synchronized(inFlight) { inFlight.remove(key) }
            if (bitmap == null || w != width || h != height) return@execute
            cache.put(key, bitmap)
            main.post { onReady(book, bitmap) }
        }
    }

    fun invalidate(bookId: Long) {
        cache.snapshot().keys.filter { it.bookId == bookId }.forEach { cache.remove(it) }
    }

    fun clear() {
        cache.evictAll()
    }
}
