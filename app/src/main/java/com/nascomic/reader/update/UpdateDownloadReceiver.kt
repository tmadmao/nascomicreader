package com.nascomic.reader.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 下载完成的入口。
 *
 * 声明在清单里而不是只在本页注册：包下完时用户很可能已经切走了，
 * 只在 Activity 里注册的话这一刻没人接，回来还得自己猜下到哪了。
 * 广播里只有一个 id，别的什么都不能信——真正的校验在 [UpdateCenter] 里做。
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        UpdateCenter.onDownloadFinished(context, id)
    }
}
