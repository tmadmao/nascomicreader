package com.nascomic.reader.ui.reader

/**
 * 当前进程里的内存页缓存。阅读页建一份放到这里，设置页才能读到「已缓存多少」并清得掉。
 *
 * 只放引用，不放 Context；阅读页销毁时 [release]，别把位图一直挂在单例上。
 */
object PageCacheHolder {

    @Volatile
    private var cache: PageCache? = null

    fun install(pageCache: PageCache) {
        cache = pageCache
    }

    fun current(): PageCache? = cache

    /** 阅读页销毁时调用：只有自己那一份才清掉，别误删后来者的 */
    fun release(pageCache: PageCache) {
        if (cache === pageCache) {
            pageCache.clear()
            cache = null
        }
    }

    fun bytes(): Long = cache?.bytes() ?: 0L
}
