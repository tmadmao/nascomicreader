package com.nascomic.reader.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SMB 上的一层薄封装：连上共享，能列目录、能按偏移读、能整份下载。
 *
 * 协议交给 smbj（SMB2/3），我们只关心「列一层名字」和「读一段字节」——
 * 这两件事够了，上面所有格式解析都走 [com.nascomic.reader.format.zip.RandomAccessData]。
 */
class SmbClient(
    private val target: SmbTarget,
    private val account: String?,
    private val password: String?,
) : Closeable {

    data class Entry(
        /** 共享内的路径，作为书的 docRef */
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    private var share: DiskShare? = null

    /** 缓一个刚打开的文件：一页的读取常是连续几次小块，重开一次是白费 */
    private var openPath: String? = null
    private var openFile: SmbFile? = null

    private fun share(): DiskShare {
        share?.let { return it }
        val session = try {
            val connection = client.connect(target.host, target.port)
            connection.authenticate(auth())
        } catch (e: Exception) {
            throw classify(e, "连不上 ${target.host}")
        }

        val opened = try {
            session.connectShare(target.share) as DiskShare
        } catch (e: Exception) {
            throw classify(e, "打不开共享 ${target.share}")
        }
        share = opened
        return opened
    }

    /** 账号可以写成 `域\账号`；留空就按匿名连（很多 NAS 允许访客读） */
    private fun auth(): AuthenticationContext {
        val raw = account?.trim().orEmpty()
        if (raw.isEmpty()) return AuthenticationContext.anonymous()

        val domain = if (raw.contains('\\')) raw.substringBefore('\\') else ""
        val user = if (raw.contains('\\')) raw.substringAfter('\\') else raw
        return AuthenticationContext(user, password.orEmpty().toCharArray(), domain)
    }

    /** 列一层。路径用共享内相对路径，共享根传空串 */
    fun list(dirPath: String): List<Entry> {
        val entries = try {
            share().list(dirPath)
        } catch (e: Exception) {
            throw classify(e, "列目录失败：$dirPath")
        }

        return entries.asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                Entry(
                    path = if (dirPath.isEmpty()) info.fileName else "$dirPath/${info.fileName}",
                    name = info.fileName,
                    isDirectory = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = info.endOfFile,
                )
            }
            .toList()
    }

    fun size(path: String): Long = open(path).let { file ->
        try {
            file.length
        } catch (e: Exception) {
            throw classify(e, "取文件信息失败：$path")
        }
    }

    /**
     * 从 [offset] 起读最多 [length] 字节。读不到返回 null。
     * smbj 的 read 在越过末尾时返回 -1，这里统一成 null 让调用方少判断一种情况。
     */
    fun read(path: String, offset: Long, length: Int): ByteArray? {
        val file = open(path)
        val buffer = ByteArray(length)
        var filled = 0
        // 服务器可以只给一部分（协商的 MaxReadSize 就那么大），所以要接着要，直到填满或到末尾
        while (filled < length) {
            val n = try {
                file.read(buffer, offset + filled, filled, length - filled)
            } catch (e: Exception) {
                throw classify(e, "读取失败：$path")
            }
            if (n <= 0) break
            filled += n
        }
        if (filled == 0) return null
        return if (filled == length) buffer else buffer.copyOf(filled)
    }

    /** 整份下载到本地文件（PDF 与 7z/RAR 需要文件路径） */
    fun download(path: String, out: File) {
        val file = open(path)
        try {
            out.outputStream().use { sink -> file.read(sink) }
        } catch (e: Exception) {
            throw classify(e, "下载失败：$path")
        }
    }

    private fun open(path: String): SmbFile {
        openFile?.let { if (openPath == path) return it }
        closeOpenFile()

        val file = try {
            share().openFile(
                path,
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (e: Exception) {
            throw classify(e, "打不开文件：$path")
        }
        openPath = path
        openFile = file
        return file
    }

    private fun closeOpenFile() {
        runCatching { openFile?.close() }
        openFile = null
        openPath = null
    }

    /**
     * smbj 的异常种类多，上层只需要知道两件事：是不是账号不对，其余都算连不上。
     * 登录失败在 SMB2 上是 STATUS_LOGON_FAILURE / STATUS_ACCESS_DENIED。
     */
    private fun classify(e: Exception, prefix: String): SmbException {
        if (e is SmbException) return e
        val authFailed = generateSequence(e as Throwable?) { it.cause }
            .take(6)
            .filterIsInstance<SMBApiException>()
            .any { it.status == NtStatus.STATUS_LOGON_FAILURE || it.status == NtStatus.STATUS_ACCESS_DENIED }
        return SmbException("$prefix：${e.message ?: e.javaClass.simpleName}", authFailed)
    }

    override fun close() {
        closeOpenFile()
        runCatching { share?.close() }
        share = null
        runCatching { client.close() }
    }

}

/** SMB 侧的失败：只区分「账号不对」和「其它（连不上、路径不存在）」 */
class SmbException(message: String, val authFailed: Boolean = false) : Exception(message)
