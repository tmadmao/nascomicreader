package com.nascomic.reader.ui.shelf

import android.graphics.Bitmap
import android.util.LruCache
import com.nascomic.reader.format.BookSource
import com.nascomic.reader.format.CancelSignal
import com.nascomic.reader.format.RenderedPageSource
import com.nascomic.reader.ui.reader.PageBitmapLoader
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * 挑封面的缩略图解码器。
 *
 * 四条约束：
 * 1. **只解到格子大小**——按目标宽高降采样，原图不落地；
 * 2. **最多三路并行**——一屏十几格同时解会抢满 IO，滑动会卡；
 * 3. **可取消**——滑出屏幕或关窗时协程被取消，没解完的不会再把图交给已经复用的格子；
 * 4. **取消要真的让出槽位**——读远端是阻塞调用，协程取消不会立刻返回。
 *    只等它自己醒，三次快速滑走就把三个槽全占死，后面的页再也进不来。
 *    所以取消时先把这一页正在读的流关掉（socket 一关，阻塞的 read 就抛），
 *    再把槽位还回去；线程真卡死时也不会拖住整个网格。
 *
 * 解不出来返回 null（坏页、远端断开），由界面显示占位，绝不回退成「用第 1 页」之类的猜测。
 */
internal class CoverPageLoader(
    private val source: BookSource,
    private val width: Int,
    private val height: Int,
) {

    private val gate = Semaphore(3)

    private val cache = object : LruCache<Int, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(index: Int): Bitmap? {
        cache.get(index)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.acquire()
            val released = AtomicBoolean(false)
            val release = {
                if (released.compareAndSet(false, true)) gate.release()
                Unit
            }
            // 这一页正在读的流。取消时从看门协程关掉它，卡在 read 上的线程才会醒。
            val stream = AtomicReference<Closeable?>(null)
            // 还没拿到流时（请求已发出、响应头没回来）也要能断：把取消登记给来源，
            // 远端实现会在请求一建好时就挂上 Call
            val signal = CancelSignal()
            val watcher = watchRemoval {
                signal.cancel()
                release()
                runCatching { stream.getAndSet(null)?.close() }
            }
            try {
                // 排队期间已经被取消的，别再开流
                currentCoroutineContext().ensureActive()
                cache.get(index)?.let { return@withContext it }
                val bitmap = runCatching { signal.run {
                    (source as? RenderedPageSource)?.renderPage(index, width, height)
                        ?: source.openPage(index, signal).use { input ->
                            stream.set(input)
                            PageBitmapLoader.load(input, width, height, Bitmap.Config.RGB_565)
                        }
                } }.getOrNull()
                currentCoroutineContext().ensureActive()
                if (bitmap != null) cache.put(index, bitmap)
                bitmap
            } finally {
                watcher.cancel()
                stream.getAndSet(null)
                signal.close()
                release()
            }
        }
    }

    /**
     * 起一个「被取消时立刻跑清理」的看门协程。
     *
     * 不能挂在当前协程的 `invokeOnCompletion` 上：那个回调要等协程真正结束才跑，
     * 而卡在阻塞读里的协程恰恰结束不了。子协程的取消是立刻传到的，
     * 它的 finally 才是取消时刻唯一能跑到的位置。
     */
    private fun kotlinx.coroutines.CoroutineScope.watchRemoval(onCancel: () -> Unit): Job =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { onCancel() }
            }
        }

    /** 关窗时丢掉缓存；界面退出后这些图不再有人用 */
    fun clear() {
        cache.evictAll()
    }
}
