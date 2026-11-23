package com.nascomic.reader.core.fs

import java.util.Locale

/**
 * 文件系统 / NAS 自动生成的系统目录。
 *
 * 这些目录不是用户的书库内容：Synology 的回收站 `#recycle`、QNAP 的 `@Recycle`、
 * Samba 默认的 `.recycle`、桌面回收站、缩略图缓存和可见快照都在这里。
 * 不排除它们时，回收站里的旧书会被当成新书重新入库，快照还会把同一本书复制很多份。
 *
 * 只按**完整目录名**判断，不做「隐藏目录一概跳过」：用户完全可能把漫画放在
 * `.` 开头的目录里，按名字精确匹配比按属性猜测可靠。大小写不敏感，
 * 因为 SMB / WebDAV 的目录名大小写由服务端决定。
 */
object SystemDirs {

    /**
     * 已知的系统目录名（均为小写）。
     *
     * 来源：Synology `#recycle` / `#snapshot` / `@eaDir`，QNAP `@Recycle` /
     * `@Recently-Snapshot`，Samba `vfs_recycle` 默认 `.recycle`，
     * Windows `$RECYCLE.BIN`，freedesktop / macOS 的 `.Trash`、`.Trashes`。
     */
    private val FIXED_NAMES = setOf(
        "#recycle",
        "@recycle",
        ".recycle",
        "\$recycle.bin",
        ".trash",
        ".trashes",
        "@eadir",
        "#snapshot",
        "@recently-snapshot",
    )

    /** freedesktop 的跨分区回收站：`.Trash-1000` 这类按 uid 分目录 */
    private const val TRASH_PREFIX = ".trash-"

    fun isIgnoredDirectory(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in FIXED_NAMES || lower.startsWith(TRASH_PREFIX)
    }

    /**
     * 从目录项里去掉系统目录，文件不受影响。
     *
     * 各协议只提供「名字 + 是否目录」，所以用两个取值函数适配，避免为三种列表类型各写一份。
     */
    fun <T> withoutIgnoredDirectories(
        items: List<T>,
        nameOf: (T) -> String,
        isDirectory: (T) -> Boolean,
    ): List<T> = items.filterNot { isDirectory(it) && isIgnoredDirectory(nameOf(it)) }
}
