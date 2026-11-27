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
import com.nascomic.reader.core.fs.SystemDirs
import com.nascomic.reader.format.CancelSignal
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SMB 上的一层薄封装：连上共享，能列目录、能按偏移读、能整份下载。
 *
 * 协议交给 smbj（SMB2/3），我们只关心「列一层名字」和「读一段字节」——
 * 这两件事够了，上面所有格式解析都走 [com.nascomic.reader.format.zip.RandomAccessData]。
 */
class SmbClient private constructor(
    private val target: SmbTarget,
    private val account: String?,
    private val password: String?,
    private val socketFactory: RequestSocketFactory?,
) : Closeable {
    constructor(target: SmbTarget, account: String?, password: String?) : this(target, account, password, null)

    private fun requestClient(signal: CancelSignal? = CancelSignal.current): SmbClient? =
        if (signal == null || socketFactory != null) null else signal.resource(this) {
            SmbClient(target, account, password, RequestSocketFactory(signal))
        }


    data class Entry(
        /** 共享内的路径，作为书的 docRef */
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long = 0,
    )

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .apply { socketFactory?.let { withSocketFactory(it) } }
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

    /** 列一层。路径用共享内相对路径，共享根传空串；NAS 的系统目录不返回 */
    fun list(dirPath: String): List<Entry> {
        requestClient()?.let { return it.list(dirPath) }
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
                    lastModified = info.lastWriteTime.toEpochMillis(),
                )
            }
            .filterNot { it.isDirectory && SystemDirs.isIgnoredDirectory(it.name) }
            .toList()
    }

    internal fun fileVersion(path: String): String = share().getFileInformation(path).let {
        "${it.standardInformation.endOfFile}:${it.basicInformation.lastWriteTime.toEpochMillis()}"
    }

    fun size(path: String): Long {
        requestClient()?.let { return it.size(path) }
        val file = open(path)
        try {
            return file.length
        } catch (e: Exception) {
            throw classify(e, "取文件信息失败：$path")
        }
    }

    /**
     * 从 [offset] 起读最多 [length] 字节。读不到返回 null。
     * smbj 的 read 在越过末尾时返回 -1，这里统一成 null 让调用方少判断一种情况。
     */
    fun read(path: String, offset: Long, length: Int): ByteArray? {
        requestClient()?.let { return it.read(path, offset, length) }
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

    /**
     * 整份下载到本地文件（PDF 与 7z/RAR 需要文件路径）。
     *
     * 与 WebDAV 同一套做法：先写 `.part`，核过长度再改名。
     * 直写最终文件时，连接一断就留下半份，而打开端只判「存在且非空」——
     * 那本书从此打不开，也不会自己重下。
     *
     * smbj 的 `read` 没有取消入口，所以起一个看门线程；用户按取消时直接断开会话，
     * 让阻塞中的读立刻失败。只查循环标志的话，服务器不发数据时会一直卡在那里。
     */
    fun download(
        path: String,
        out: File,
        isCancelled: () -> Boolean = { false },
    ) {
        requestClient()?.let { return it.download(path, out, isCancelled) }
        val file = open(path)
        val expected = runCatching { file.length }.getOrDefault(-1L)
        val tmp = File(out.parentFile, out.name + ".part")
        runCatching { tmp.delete() }

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread({
            while (!finished.get()) {
                if (isCancelled()) {
                    // 先掐传输层：file.close() 要等服务端回 close 响应，服务器不发数据时
                    // 它自己也可能卡住；直接关 socket 才能让阻塞中的 read 立刻失败
                    runCatching { client.close() }
                    return@Thread
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "smb-download-cancel").apply {
            isDaemon = true
            start()
        }

        try {
            var count = 0L
            val counter = object : java.io.FilterOutputStream(tmp.outputStream()) {
                override fun write(b: Int) {
                    super.write(b)
                    count++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    count += len
                }
            }
            counter.use { file.read(it) }
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            if (expected >= 0 && count != expected) {
                runCatching { tmp.delete() }
                throw SmbException("下载不完整：$count / $expected 字节（$path）")
            }
            if (out.exists()) runCatching { out.delete() }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                runCatching { tmp.delete() }
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            throw classify(e, "下载失败：$path")
        } finally {
            finished.set(true)
            watcher.interrupt()
        }
    }

    /** Each cancellable decode owns its socket from connect through the last byte. */
    fun openStream(path: String, cancel: CancelSignal? = CancelSignal.current): java.io.InputStream {
        requestClient(cancel)?.let { return it.openStream(path, cancel) }
        val file = open(path)
        return try {
            file.inputStream
        } catch (e: Exception) {
            throw classify(e, "读取失败：$path")
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

/** SMBJ uses this factory before negotiate/authenticate, when no SMB handle exists yet. */
private class RequestSocketFactory(private val signal: CancelSignal) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { signal.attach(it) }
    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket = createSocket().also {
        try {
            if (local != null) it.bind(local)
            it.connect(remote, 15_000)
        } catch (e: Throwable) {
            it.close()
            throw e
        }
    }
    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
}

/** SMB 侧的失败：只区分「账号不对」和「其它（连不上、路径不存在）」 */
class SmbException(message: String, val authFailed: Boolean = false) : Exception(message)
