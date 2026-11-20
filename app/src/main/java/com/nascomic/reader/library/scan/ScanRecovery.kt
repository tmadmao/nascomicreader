package com.nascomic.reader.library.scan

import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity

/**
 * 冷启动时收拾上一次没走完的扫描。
 *
 * 扫描一开始就把来源标成「扫描中」，那是一笔单独提交——入库那轮要是没等到提交，
 * 书和章会原样回滚，状态却留在「扫描中」。界面照着这个状态显示，用户看到的是一个
 * 永远转不完的进度，重扫也进不去。这里把它改成「上次扫描中断 · 可重试」。
 *
 * 只在进程起来时跑一次：同一进程里正在跑的扫描不能被误标成中断。
 */
object ScanRecovery {

    suspend fun recover(db: ReaderDatabase) {
        db.sourceDao().failStuckScans(
            scanning = SourceEntity.STATE_SCANNING,
            failed = SourceEntity.STATE_FAILED,
            code = SourceEntity.ERROR_INTERRUPTED,
        )
    }
}
