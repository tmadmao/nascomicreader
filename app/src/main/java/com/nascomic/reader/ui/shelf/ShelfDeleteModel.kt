package com.nascomic.reader.ui.shelf

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.nascomic.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 删除的 5 秒撤销窗口，放在 ViewModel 里而不是 Activity 里：
 * 改字体、换语言这类配置变化会重建 Activity，窗口和计时必须跟着活过去，
 * 否则「已删除」的书会在重建后的书架上重新出现。
 *
 * 时间一律用 [SystemClock.uptimeMillis]，与 Handler 的延时同一时间源；
 * 墙上时间会被用户或网络校时拨动，不能拿来量一段 5 秒。
 *
 * 到点提交走进程级 IO 作用域：页面销毁、ViewModel 回收都不取消它，
 * 取走的 id 一定会有一个「删成 / 删败」的落点，不会悬空。
 */
class ShelfDeleteModel(app: Application) : AndroidViewModel(app) {

    private val undo = DeleteUndo()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { commit() }

    /** 不随页面或 ViewModel 取消：删除一旦取走 id 就要执行完 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 提交落定后通知当前页面刷新（参数：删成的 id、没删成的 id）。
     * 页面正在重建时为空——提示照发，刷新交给新页面自己按库排。
     */
    var listener: ((removed: List<Long>, failed: List<Long>) -> Unit)? = null

    val pending: List<Long> get() = undo.pending

    /** 记下一批删除并起表；连续删除并进同一窗口，从这一刻重算 */
    fun schedule(ids: List<Long>) {
        if (ids.isEmpty()) return
        undo.begin(ids, SystemClock.uptimeMillis())
        arm(undo.window)
    }

    /** 撤销：窗口内成立，返回是否真的放回了 */
    fun undoNow(): Boolean {
        val restored = undo.undo(SystemClock.uptimeMillis())
        if (restored.isEmpty()) return false
        handler.removeCallbacks(tick)
        return true
    }

    /** 离开书架时立刻收尾，不等表走完 */
    fun flush() {
        handler.removeCallbacks(tick)
        val doomed = undo.flush()
        if (doomed.isNotEmpty()) delete(doomed)
    }

    private fun arm(delayMs: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs.coerceAtLeast(1L))
    }

    private fun commit() {
        val now = SystemClock.uptimeMillis()
        val doomed = undo.due(now)
        if (doomed.isEmpty()) {
            // 表响了但窗口还没到：按剩余时间重排，不能让待删项悬着
            val rest = undo.remaining(now)
            if (rest > 0L) arm(rest)
            return
        }
        delete(doomed)
    }

    private fun delete(ids: List<Long>) {
        ioScope.launch {
            val failed = BookRemover.remove(getApplication(), ids)
            handler.post {
                val app = getApplication<Application>()
                val msg = if (failed.isEmpty()) R.string.shelf_delete_done else R.string.shelf_delete_failed
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                listener?.invoke(ids - failed.toSet(), failed)
            }
        }
    }

    override fun onCleared() {
        handler.removeCallbacks(tick)
        val doomed = undo.flush()
        if (doomed.isNotEmpty()) delete(doomed)
    }
}
