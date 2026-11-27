package com.nascomic.reader.library.author

import androidx.room.withTransaction
import com.nascomic.reader.library.data.AuthorAliasEntity
import com.nascomic.reader.library.data.ReaderDatabase
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pending merge is projected in the list for five seconds, then committed in one transaction. */
class AuthorMerger(private val db: ReaderDatabase) {
    class Pending internal constructor(val from: String, val canonical: String, private val deadline: Long) {
        fun remainingMs(): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    data class Window(val pending: Pending? = null, val committing: Boolean = false, val failed: Pending? = null)

    private val hold = synchronized(holds) { holds.getOrPut(db) { Hold() } }
    val window = hold.state.asStateFlow()

    suspend fun prepare(from: String, to: String): Pending? = withContext(Dispatchers.IO) {
        val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
        val canonical = AuthorAliases.resolve(to, aliases)
        if (from.isBlank() || canonical.isBlank() || from == canonical) return@withContext null
        synchronized(hold) {
            if (hold.state.value.pending != null) return@synchronized null
            val ticket = Pending(from, canonical, SystemClock.elapsedRealtime() + WINDOW_MS)
            hold.state.value = Window(pending = ticket)
            scope.launch {
                delay(ticket.remainingMs())
                commit(ticket)
            }
            ticket
        }
    }

    fun pending(): Pending? = window.value.pending

    fun cancelWindow(ticket: Pending): Boolean = synchronized(hold) {
        val state = hold.state.value
        if (state.pending !== ticket || state.committing || ticket.remainingMs() == 0L) return@synchronized false
        hold.state.value = Window()
        true
    }

    fun consumeFailure(ticket: Pending): Boolean = synchronized(hold) {
        if (hold.state.value.failed !== ticket) return@synchronized false
        hold.state.value = Window()
        true
    }

    internal suspend fun commit(ticket: Pending) {
        synchronized(hold) {
            if (hold.state.value.pending !== ticket || hold.state.value.committing) return
            hold.state.value = Window(pending = ticket, committing = true)
        }
        var failed = false
        try {
            db.withTransaction {
                val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
                val canonical = AuthorAliases.resolve(ticket.canonical, aliases)
                check(canonical.isNotBlank() && canonical != ticket.from)
                db.bookDao().renameAuthor(ticket.from, canonical)
                db.authorAliasDao().put(AuthorAliasEntity(fromName = ticket.from, toName = canonical, createdAt = System.currentTimeMillis()))
                db.authorAliasDao().repoint(ticket.from, canonical)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            android.util.Log.w("AuthorMerge", "合并作者失败", e)
        } finally {
            synchronized(hold) { hold.state.value = Window(failed = ticket.takeIf { failed }) }
        }
    }

    suspend fun candidates(): List<com.nascomic.reader.library.data.AuthorCount> =
        withContext(Dispatchers.IO) { db.bookDao().authorCounts() }

    private class Hold {
        val state = MutableStateFlow(Window())
    }

    companion object {
        const val WINDOW_MS = 5_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val holds = java.util.WeakHashMap<ReaderDatabase, Hold>()
    }
}
