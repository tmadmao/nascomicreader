package com.nascomic.reader.update

import android.content.Context
import android.os.Build
import com.nascomic.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 拉清单、比构建号。只管「有没有新版」，不碰下载与安装。
 *
 * 网络仍用项目里已有的 OkHttp：不要为了一个 GET 请求再引一套 HTTP 客户端。
 * 超时给得短——更新检查是后台顺带做的事，卡住十秒不如直接放弃，下次启动再说。
 */
class UpdateChecker(private val context: Context) {

    sealed interface Result {
        data class Available(val manifest: UpdateManifest) : Result
        data class UpToDate(val latest: UpdateManifest?) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(sources: List<String>): Result = withContext(Dispatchers.IO) {
        var best: UpdateManifest? = null
        var lastError = ""

        for (source in sources) {
            try {
                val manifest = UpdateManifest.parse(fetch(source))
                if (manifest == null) {
                    lastError = context.getString(R.string.update_error_format)
                    continue
                }
                // 多个源可能不同步：取构建号最大的那个，免得备用源上的旧清单把更新挡回去
                if (best == null || manifest.versionCode > best.versionCode) best = manifest
            } catch (e: UpdateHttpException) {
                lastError = context.getString(R.string.update_error_http, e.code)
            } catch (e: IOException) {
                lastError = context.getString(R.string.update_error_network)
            } catch (e: Exception) {
                lastError = e.message ?: context.getString(R.string.update_error_unknown)
            }
        }

        val manifest = best
            ?: return@withContext Result.Failed(
                lastError.ifBlank { context.getString(R.string.update_error_unknown) }
            )

        if (manifest.versionCode > currentVersionCode(context)) {
            Result.Available(manifest)
        } else {
            Result.UpToDate(manifest)
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateHttpException(response.code)
            return response.body?.string().orEmpty()
        }
    }

    private class UpdateHttpException(val code: Int) : Exception("HTTP $code")

    private companion object {
        /**
         * 单独一个客户端：更新检查要的是「快失败」，和 WebDAV 那种要慢慢传大文件的口径不同。
         * 清单只有几百字节，十秒还没拿到就是拿不到了，没必要让用户等更久。
         * 连接池各有一份的代价可以忽略——更新检查几小时才跑一次。
         */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** 当前安装包的构建号；取不到算 0，那样任何清单都算「有新版」——宁可多提示也别漏 */
internal fun currentVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}.getOrDefault(0L)
