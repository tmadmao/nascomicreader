package com.nascomic.reader.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemDirsTest {

    @Test
    fun `回收站与系统目录大小写不敏感`() {
        listOf(
            "#recycle",
            "#RECYCLE",
            "@recycle",
            "@Recycle",
            ".recycle",
            ".Recycle",
            "\$recycle.bin",
            "\$RECYCLE.BIN",
            ".trash",
            ".Trash",
            ".trashes",
            ".Trashes",
            ".trash-1000",
            ".Trash-1000",
            "@eadir",
            "@eaDir",
            "#snapshot",
            "#Snapshot",
            "@recently-snapshot",
            "@Recently-Snapshot",
        ).forEach { name ->
            assertTrue("应忽略：$name", SystemDirs.isIgnoredDirectory(name))
        }
    }

    @Test
    fun `只匹配完整目录名或明确前缀`() {
        // 不能按子串过滤：用户自己的目录名里完全可能出现这些词
        assertFalse(SystemDirs.isIgnoredDirectory("我的#recycle合集"))
        assertFalse(SystemDirs.isIgnoredDirectory("recycle"))
        assertFalse(SystemDirs.isIgnoredDirectory("#recycle-old"))
        assertFalse(SystemDirs.isIgnoredDirectory(".trashcan"))
        assertFalse(SystemDirs.isIgnoredDirectory("trash"))
        assertFalse(SystemDirs.isIgnoredDirectory("Snapshots"))
    }

    @Test
    fun `只过滤目录，同名文件保留`() {
        data class Item(val name: String, val isDir: Boolean)

        val items = listOf(
            Item("#recycle", true),
            Item("#recycle", false),
            Item("正常漫画", true),
            Item("@eaDir", true),
            Item("@eaDir", false),
        )

        assertEquals(
            listOf("#recycle", "正常漫画", "@eaDir"),
            SystemDirs.withoutIgnoredDirectories(
                items = items,
                nameOf = { it.name },
                isDirectory = { it.isDir },
            ).map { it.name },
        )
    }
}
