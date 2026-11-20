package com.nascomic.reader.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 落盘副本按书作废。
 *
 * 副本名只带书 id 与条目引用：来源改了地址、或同路径的文件被换掉，名字一字不变，
 * 留着就会被当成「已经下好了」，一直显示旧内容。所以改名换址之后要能整本作废。
 */
@RunWith(AndroidJUnit4::class)
class LocalCopiesEvictTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val touched = ArrayList<File>()

    @Before
    fun setUp() {
        LocalCopies.clear(context)
    }

    @After
    fun tearDown() {
        LocalCopies.clear(context)
    }

    @Test
    fun `只删这本书的三类副本_别的书不动`() {
        val mine = listOf(
            file(LocalCopies.remoteDir(context), "b7_1024"),
            file(LocalCopies.remoteDir(context), "a7_beef"),
            file(LocalCopies.remoteDir(context), "p7_cafe"),
            file(LocalCopies.archivesDir(context), "b7_2048"),
        )
        val others = listOf(
            // 12 不能被 7 的规则误伤：前缀必须带下划线
            file(LocalCopies.remoteDir(context), "b12_1024"),
            file(LocalCopies.remoteDir(context), "a12_beef"),
        )

        LocalCopies.evictBooks(context, listOf(7L))

        mine.forEach { assertFalse("应当被删：${it.name}", it.exists()) }
        others.forEach { assertTrue("不该动：${it.name}", it.exists()) }
    }

    @Test
    fun `空列表什么都不做`() {
        val one = file(LocalCopies.remoteDir(context), "b1_9")
        LocalCopies.evictBooks(context, emptyList())
        assertTrue(one.exists())
    }

    private fun file(dir: File, name: String): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeBytes(ByteArray(8) { 1 })
            touched += this
        }
    }
}
