package com.nascomic.reader.format

import java.io.Closeable
import java.io.InterruptedIOException

/** One blocking operation: register transports before issuing I/O, including when no stream exists yet. */
class CancelSignal : Closeable {
    private val lock = Any()
    private val stops = LinkedHashSet<Closeable>()
    private val resources = HashMap<Any, Closeable>()
    @Volatile var isCancelled: Boolean = false
        private set

    fun attach(stop: Closeable): Closeable {
        synchronized(lock) {
            if (!isCancelled) {
                stops += stop
                return Closeable { synchronized(lock) { stops.remove(stop) } }
            }
        }
        runCatching { stop.close() }
        return Closeable { }
    }

    /** Own a transport for the duration of one decode, never across cells. Factory must not do I/O. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Closeable> resource(key: Any, create: () -> T): T = synchronized(lock) {
        if (isCancelled) throw InterruptedIOException("已取消")
        resources.getOrPut(key, create) as T
    }

    fun cancel() {
        val active = synchronized(lock) {
            isCancelled = true
            stops.toList().also { stops.clear() }
        }
        active.forEach { runCatching { it.close() } }
    }

    // Normal completion closes resources too, on the same worker that used them.
    override fun close() {
        cancel()
        val owned = synchronized(lock) { resources.values.toList().also { resources.clear() } }
        owned.forEach { runCatching { it.close() } }
    }

    internal fun <T> run(block: () -> T): T {
        if (isCancelled) throw InterruptedIOException("已取消")
        val old = active.get()
        active.set(this)
        try { return block() } finally { active.set(old) }
    }

    companion object {
        // ZIP/TAR/EPUB call RandomAccessData synchronously, so the blocking operation's
        // token follows those reads without being stored on a shared catalog or connection.
        private val active = ThreadLocal<CancelSignal>()
        internal val current: CancelSignal? get() = active.get()
    }
}
