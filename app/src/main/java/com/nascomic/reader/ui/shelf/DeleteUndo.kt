package com.nascomic.reader.ui.shelf

/**
 * 删除的 5 秒窗口：窗口之内不动库，书架先把这几本藏起来；
 * 撤销就是把它们放回来，等于什么也没发生。窗口一过才真删。
 *
 * 不做无限期软删除：记录长期留在库里，扫描、系列、计数都要绕开它，
 * 数据只会越来越脏。所以撤销只在明处的那几秒里成立。
 *
 * 一次没到点又来一次删除，就并进同一个窗口：条上写「已删除 N 本」，
 * 撤销一次全放回，不留两条并行的计时器。
 *
 * [now] 由调用方传入，须与定时器同一时间源（单调时钟，见 ShelfDeleteModel）；
 * 这里不做任何取时，便于脱离 Android 验证窗口边界。
 */
class DeleteUndo(private val windowMs: Long = WINDOW_MS) {

    private var ids: List<Long> = emptyList()
    private var startedAt = 0L

    /** 正等着到点的这些书。书架按它把卡片先藏起来 */
    val pending: List<Long> get() = ids

    val window: Long get() = windowMs

    /** 记下一批新的删除；窗口从这一刻重新算 */
    fun begin(newIds: List<Long>, now: Long) {
        if (newIds.isEmpty()) return
        ids = (ids + newIds).distinct()
        startedAt = now
    }

    /** 撤销：窗口内把待删的全部放回；过点或本来就没有，返回空 */
    fun undo(now: Long): List<Long> {
        if (ids.isEmpty() || now >= startedAt + windowMs) return emptyList()
        val restored = ids
        clear()
        return restored
    }

    /** 窗口到点要真删的 id；没到点返回空，状态不动 */
    fun due(now: Long): List<Long> {
        if (ids.isEmpty() || now < startedAt + windowMs) return emptyList()
        val doomed = ids
        clear()
        return doomed
    }

    /** 离开书架时立刻收尾：返回要真删的 id */
    fun flush(): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val doomed = ids
        clear()
        return doomed
    }

    /** 这一刻离到点还有多久；没有待删的就是 0 */
    fun remaining(now: Long): Long {
        if (ids.isEmpty()) return 0L
        return (startedAt + windowMs - now).coerceAtLeast(0L)
    }

    private fun clear() {
        ids = emptyList()
        startedAt = 0L
    }

    companion object {
        /** 撤销窗口：够点一下撤销，又不至于让「已删除」迟迟不落地 */
        const val WINDOW_MS = 5000L
    }
}
