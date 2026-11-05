package com.nascomic.reader.ui.update

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.nascomic.reader.R
import com.nascomic.reader.databinding.DialogUpdateBinding
import com.nascomic.reader.settings.AppPrefs
import com.nascomic.reader.update.UpdateCenter
import com.nascomic.reader.update.UpdateManifest

/**
 * 两个弹窗：发现新版、安装包已就绪。
 *
 * 放在一处是因为书架和「关于」页都可能触发，文案和按钮顺序必须一致——
 * 同一个功能在两个入口长得不一样，用户会以为是两回事。
 *
 * 按钮只有三个，各自的含义要分得清：
 *   「稍后」这次不问，下次启动再说；
 *   「跳过此版本」记进偏好，这个构建号以后都不提；
 *   「下载并安装」交给 DownloadManager，进度去系统通知里看。
 */
object UpdateDialogs {

    /**
     * @param forced 低于最低支持版本。此时不给「稍后」也不给返回键——
     *               旧版本已经连不上服务端时，让用户继续用只是把问题往后拖。
     */
    fun showAvailable(
        context: Context,
        manifest: UpdateManifest,
        forced: Boolean,
        onDismissed: () -> Unit,
    ) {
        val binding = DialogUpdateBinding.inflate(LayoutInflater.from(context))
        binding.version.text = context.getString(
            R.string.update_version_line,
            manifest.versionName,
            manifest.versionCode,
        )
        binding.meta.text = metaText(context, manifest)
        binding.notes.text = manifest.notes.ifBlank { context.getString(R.string.update_notes_empty) }

        val titleRes =
            if (forced) R.string.update_title_forced else R.string.update_title_available

        val builder = AlertDialog.Builder(context)
            .setTitle(context.getString(titleRes, manifest.versionName))
            .setView(binding.root)
            .setPositiveButton(R.string.update_now) { _, _ ->
                val started = UpdateCenter.download(context, manifest)
                val text = if (started) R.string.update_download_started else R.string.update_download_failed
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }

        if (!forced) {
            builder.setNegativeButton(R.string.update_later, null)
            builder.setNeutralButton(R.string.update_skip) { _, _ ->
                AppPrefs(context).updateIgnoredVersion = manifest.versionCode
            }
        }

        builder.setCancelable(!forced)
        builder.setOnDismissListener { onDismissed() }
        builder.show()
    }

    fun showReady(
        context: Context,
        versionName: String,
        onDismissed: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.update_ready_title)
            .setMessage(context.getString(R.string.update_ready_message, versionName))
            // 不给返回键：包已经在本地了，要么装要么明确放弃，别留个悬念
            .setCancelable(false)
            .setNegativeButton(R.string.update_discard) { _, _ -> UpdateCenter.discard(context) }
            .setPositiveButton(R.string.update_install) { _, _ ->
                if (!UpdateCenter.install(context)) {
                    Toast.makeText(context, R.string.update_install_permission, Toast.LENGTH_LONG)
                        .show()
                }
            }
            .setOnDismissListener { onDismissed() }
            .show()
    }

    private fun metaText(context: Context, manifest: UpdateManifest): String {
        val current = currentVersionName(context)
        return if (manifest.apkSize > 0L) {
            context.getString(R.string.update_meta, formatSize(manifest.apkSize), current)
        } else {
            context.getString(R.string.update_meta_no_size, current)
        }
    }

    private fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "?" }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
    }
}
