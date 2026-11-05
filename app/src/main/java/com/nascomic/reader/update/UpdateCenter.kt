package com.nascomic.reader.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nascomic.reader.BuildConfig
import com.nascomic.reader.R
import com.nascomic.reader.settings.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 更新过程的几个状态。界面只认这个，不自己拼「是不是在检查」这类布尔量 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [forced] 表示当前构建号低于清单里的 minVersionCode，必须升 */
    data class Available(val manifest: UpdateManifest, val forced: Boolean) : UpdateState

    data object UpToDate : UpdateState
    data class Failed(val reason: String) : UpdateState

    /** 包已经下好并验过，等着拉起安装器 */
    data class Ready(val file: File, val versionName: String) : UpdateState

    /** 下到了但验不过，或者下载失败。包已经删掉 */
    data class Broken(val reason: String) : UpdateState
}

/**
 * 更新流程的唯一入口：检查 → 下载 → 校验 → 安装。
 *
 * 下载交给系统 DownloadManager，不自己拿 OkHttp 落盘：
 * 它自带通知、断网重试、以及其他应用正在下载时的排队，这些自己写一遍只会更差。
 * 代价是拿不到「已下多少」的细粒度回调，进度交给系统通知显示就够了。
 *
 * 状态放在 StateFlow 里，界面订阅它。不上 ViewModel：这个流程要在 Activity 之外活下来
 * （下载完成时界面可能已经不在了），ViewModel 跟着界面走，反而不合适。
 */
object UpdateCenter {

    /**
     * 自动检查的间隔。六小时够快——发版本来就稀疏，一天多查几次的代价也就是几个 GET。
     * 真正的约束是「别在用户正要用的时候抢资源」，所以间隔之外还有开屏延迟。
     */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /**
     * 开屏后等一会儿再发请求。
     *
     * 书架要查库、算进度、生成封面，都在启动那几秒里；这时候插一个网络请求进去，
     * 只会让首屏更慢，而它想拿的东西用户三秒之内根本不会想知道。
     */
    private const val START_DELAY_MS = 2500L

    private const val APK_MIME = "application/vnd.android.package-archive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一次检查：开屏和「关于」页可能同时触发 */
    private val checking = AtomicBoolean(false)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // ── 检查 ───────────────────────────────────────────────

    /**
     * 开屏时调用：距上次检查不到六小时就什么都不做。
     * 失败一律静默——用户没问，弹一句「检查更新失败」只会让人以为应用坏了。
     */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        val elapsed = System.currentTimeMillis() - prefs.updateLastCheckAt
        if (elapsed in 0 until CHECK_INTERVAL_MS) return
        startCheck(app, manual = false, delayMs = START_DELAY_MS)
    }

    /**
     * @param manual 用户主动点的。手动查要有明确结果：是最新、还是失败在哪一步。
     *               手动查不延迟——用户点了就在等。
     */
    fun check(context: Context, manual: Boolean) {
        startCheck(context.applicationContext, manual, delayMs = 0L)
    }

    private fun startCheck(app: Context, manual: Boolean, delayMs: Long) {
        val prefs = AppPrefs(app)

        // 调试包不能装正式包（签名不同），自动检查只会提示一个装不上的更新
        if (!manual && BuildConfig.DEBUG) return

        if (!checking.compareAndSet(false, true)) return

        scope.launch {
            if (delayMs > 0) delay(delayMs)

            // 时间戳在真正要发请求时才写：延迟期间用户退了应用，下次进来还能再查
            prefs.updateLastCheckAt = System.currentTimeMillis()
            _state.value = UpdateState.Checking

            val result = UpdateChecker(app).check(UpdateSources.resolve(prefs))
            checking.set(false)

            when (result) {
                is UpdateChecker.Result.Available -> {
                    val manifest = result.manifest
                    val forced = currentVersionCode(app) < manifest.minVersionCode
                    val ignored = prefs.updateIgnoredVersion == manifest.versionCode
                    // 用户点过「跳过此版本」就别再问；强制更新和手动检查除外
                    if (ignored && !forced && !manual) {
                        _state.value = UpdateState.Idle
                    } else {
                        _state.value = UpdateState.Available(manifest, forced)
                    }
                }

                is UpdateChecker.Result.UpToDate -> {
                    _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                }

                is UpdateChecker.Result.Failed -> {
                    _state.value = if (manual) UpdateState.Failed(result.reason) else UpdateState.Idle
                }
            }
        }
    }

    /** 界面处理完一个状态后调一次，免得下次回到前台又弹一遍 */
    fun consume() {
        if (_state.value is UpdateState.Ready) return
        _state.value = UpdateState.Idle
    }

    // ── 下载 ───────────────────────────────────────────────

    /**
     * 入队下载。只负责把任务交给系统，进度和失败重试都不在这里管。
     * 返回 false 表示系统不接这个任务（没有 DownloadManager、外部目录不可用、地址不合法）。
     */
    fun download(context: Context, manifest: UpdateManifest): Boolean {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        val root = app.getExternalFilesDir(null) ?: return false

        // 清掉上一版留下的包和半截文件：按构建号命名，不清就会一版一版堆下去
        File(root, DIR).listFiles()?.forEach { it.delete() }

        val relative = "$DIR/nascomic-${manifest.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle(app.getString(R.string.update_download_title, manifest.versionName))
            .setDescription(app.getString(R.string.update_download_desc))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(app, null, relative)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val previous = prefs.updatePendingDownloadId
        if (previous > 0L) runCatching { dm.remove(previous) }

        val id = runCatching { dm.enqueue(request) }.getOrNull() ?: return false

        prefs.updatePendingDownloadId = id
        prefs.updatePendingVersionCode = manifest.versionCode
        prefs.updatePendingVersionName = manifest.versionName
        prefs.updatePendingSha256 = manifest.sha256
        prefs.updateReadyPath = ""
        return true
    }

    /**
     * 下载完成回调（广播可能把进程拉起来，此时界面通常不在）。
     *
     * 校验放在这里而不是安装前：校验不过的包没有留着的价值，早点删掉省空间，
     * 也不会出现「点安装才发现包是坏的」。
     */
    fun onDownloadFinished(context: Context, id: Long) {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        if (id <= 0L || id != prefs.updatePendingDownloadId) return

        val expectedSha = prefs.updatePendingSha256
        val expectedCode = prefs.updatePendingVersionCode
        val versionName = prefs.updatePendingVersionName

        scope.launch {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                reject(app, prefs, app.getString(R.string.update_error_unknown))
                return@launch
            }

            val file = downloadedFile(dm, id)
            if (file == null) {
                reject(app, prefs, app.getString(R.string.update_download_failed))
                return@launch
            }

            if (!ApkVerifier.sha256(file).equals(expectedSha, ignoreCase = true)) {
                file.delete()
                reject(app, prefs, app.getString(R.string.update_verify_failed))
                return@launch
            }

            val summary = ApkVerifier.inspect(app, file)
            if (summary == null) {
                file.delete()
                reject(app, prefs, app.getString(R.string.update_verify_failed))
                return@launch
            }

            if (summary.versionCode != expectedCode) {
                file.delete()
                reject(app, prefs, app.getString(R.string.update_package_mismatch))
                return@launch
            }

            if (!summary.sameSigner) {
                file.delete()
                val reason = if (BuildConfig.DEBUG) {
                    app.getString(R.string.update_debug_no_install)
                } else {
                    app.getString(R.string.update_signer_mismatch)
                }
                reject(app, prefs, reason)
                return@launch
            }

            prefs.updateReadyPath = file.absolutePath
            prefs.updatePendingDownloadId = -1L
            _state.value = UpdateState.Ready(file, versionName)
        }
    }

    /** 进程被系统收走之后回来：把「已验过、还没装」这件事从偏好里捞回来 */
    fun restoreReady(context: Context) {
        if (_state.value is UpdateState.Ready) return
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        val path = prefs.updateReadyPath
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) {
            prefs.updateReadyPath = ""
            return
        }

        /*
         * 这版可能已经装上了。
         *
         * 用户完全可以从下载器的通知栏直接点安装——那条路不经过我们，装完包被替换、
         * 进程重启，而这条「待安装」的记录还在。只判断「文件存在」就会把一个已经装好的包
         * 再端到用户面前，点下去还要一次安装权限，像是应用没搞清楚自己是什么版本。
         *
         * 所以要比构建号：待装的不比当前新，就没有「待安装」这回事，包删掉、记录清掉。
         */
        if (prefs.updatePendingVersionCode <= currentVersionCode(app)) {
            file.delete()
            prefs.updateReadyPath = ""
            prefs.updatePendingDownloadId = -1L
            return
        }

        _state.value = UpdateState.Ready(
            file,
            prefs.updatePendingVersionName.ifBlank { file.nameWithoutExtension },
        )
    }

    // ── 安装 ───────────────────────────────────────────────

    /**
     * 拉起系统安装器。返回 false 说明没拉起来，原因只有两种：权限没给、或者包不在了。
     * 权限没给时顺手把用户送到「安装未知应用」的设置页——比让他在设置里自己找快得多。
     */
    fun install(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        val path = prefs.updateReadyPath
        if (path.isBlank()) return false

        val file = File(path)
        if (!file.exists()) {
            prefs.updateReadyPath = ""
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !app.packageManager.canRequestPackageInstalls()
        ) {
            requestInstallPermission(app)
            return false
        }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.update", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { app.startActivity(intent) }.isSuccess
        if (started) prefs.updateReadyPath = ""
        return started
    }

    /** 用户放弃这次安装：包删掉，下次重新下，不留一份没用的安装包 */
    fun discard(context: Context) {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        prefs.updateReadyPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        prefs.updateReadyPath = ""
        consume()
    }

    private fun requestInstallPermission(app: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    // ── 内部 ───────────────────────────────────────────────

    /** 下载成功返回落盘的文件，失败或还没完成返回 null（顺带把半截文件清掉） */
    private fun downloadedFile(dm: DownloadManager, id: Long): File? {
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null
            val uri = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            ) ?: return null
            return runCatching { File(Uri.parse(uri).path ?: return null) }
                .getOrNull()
                ?.takeIf { it.exists() }
        }
    }

    private fun reject(app: Context, prefs: AppPrefs, reason: String) {
        prefs.updatePendingDownloadId = -1L
        prefs.updateReadyPath = ""
        _state.value = UpdateState.Broken(reason)
    }

    /** 相对外部私有目录的子目录，和 res/xml/update_paths.xml 里声明的一致 */
    private const val DIR = "update"
}
