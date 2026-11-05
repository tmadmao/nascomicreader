package com.nascomic.reader.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 安装包校验。两道关，缺一不可：
 *
 * 1. SHA-256 对上清单——挡住传输途中被截断、被换掉的文件；
 * 2. 签名和当前安装的包一致——挡住「清单和安装包一起被换掉」。
 *
 * 第二道比第一道更要紧：apk 地址是平台给的，清单也是放在平台上的，两者同时沦陷时只有
 * 签名能说话。系统安装时本来也会拦一道，但那是在用户点了安装之后；这里提前拦，
 * 用户看到的是「包不对」，不是一句莫名其妙的「应用未安装」。
 */
internal object ApkVerifier {

    /** 流式算，不把十几兆读进内存 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** 从 apk 里读出来的东西：构建号，以及它是不是和本机这个包同源 */
    data class Summary(val versionCode: Long, val sameSigner: Boolean)

    /** 读不出来（文件坏了、不是 apk）返回 null */
    fun inspect(context: Context, file: File): Summary? {
        val pm = context.packageManager
        val flag = signingFlag()
        val archive = runCatching {
            pm.getPackageArchiveInfo(file.absolutePath, flag)
        }.getOrNull() ?: return null

        val installed = runCatching {
            pm.getPackageInfo(context.packageName, flag)
        }.getOrNull() ?: return null

        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        val same = archiveSigners.isNotEmpty() && archiveSigners.any { it in installedSigners }

        return Summary(versionCode(archive), same)
    }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** 只认当前签名证书。签名历史里带着换过的旧证书，拿它比会把「旧密钥签的包」也放进来 */
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.mapTo(HashSet(signatures.size)) {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
