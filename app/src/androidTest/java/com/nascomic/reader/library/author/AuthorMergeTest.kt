package com.nascomic.reader.library.author

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 作者合并：5 秒窗口，到点才落库。
 *
 * 合并必须同时改书与记别名：只改书，下次扫进来的旧写法又长出一条；
 * 只记别名，现有书架不跟着变。窗口内撤销等于取消这次合并——库里一个字都没改。
 */
@RunWith(AndroidJUnit4::class)
class AuthorMergeTest {

    private lateinit var db: ReaderDatabase
    private lateinit var merger: AuthorMerger

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        merger = AuthorMerger(db)

        val sourceId = db.sourceDao().insert(
            SourceEntity(kind = SourceEntity.KIND_LOCAL, label = "测试", treeUri = "content://test", addedAt = 1)
        )
        listOf(1L to "多写法", 2L to "多写法", 3L to "正名").forEach { (id, author) ->
            db.bookDao().insert(
                BookEntity(
                    id = id,
                    sourceId = sourceId,
                    docRef = "书$id",
                    title = "书$id",
                    titleSort = "shu$id",
                    author = author,
                    format = BookEntity.FORMAT_FOLDER,
                    addedAt = id,
                    modifiedAt = id,
                )
            )
        }
    }

    @After
    fun tearDown() {
        merger.pending()?.let(merger::cancelWindow)
        db.close()
    }

    private suspend fun merge(from: String, to: String) {
        val p = merger.prepare(from, to) ?: return
        merger.commit(p)
    }

    @Test
    fun `到点提交后书与别名一起变`() = runBlocking {
        val p = merger.prepare("多写法", "正名")
        assertNotNull(p)
        // 窗口内不写库：界面上是预演的，库里还是原样
        assertEquals(2, db.bookDao().byAuthor("多写法").size)
        assertNull(db.authorAliasDao().targetOf("多写法"))

        merger.commit(p!!)
        assertEquals(3, db.bookDao().byAuthor("正名").size)
        assertEquals("正名", db.authorAliasDao().targetOf("多写法"))
    }

    @Test
    fun `窗口内撤销_库里一个字都不改`() = runBlocking {
        merger.prepare("多写法", "正名")
        assertNotNull(merger.pending())

        assertTrue(merger.pending()?.let(merger::cancelWindow) == true)

        assertNull(merger.pending())
        assertEquals(2, db.bookDao().byAuthor("多写法").size)
        assertEquals(1, db.bookDao().byAuthor("正名").size)
        assertNull(db.authorAliasDao().targetOf("多写法"))
    }

    /** 撤销不该碰「本来就归在正名下」的书：它跟这次合并无关 */
    @Test
    fun `撤销不动原本就属于正名的书`() = runBlocking {
        // 3 号书本来就归在正名下，撤销前后都必须留在那儿
        merger.prepare("多写法", "正名")
        merger.pending()?.let(merger::cancelWindow)
        assertEquals(1, db.bookDao().byAuthor("正名").size)
    }

    @Test
    fun `A到B再到C_新扫到的A归到C`() = runBlocking {
        merge("多写法", "正名")
        merge("正名", "最终名")

        val aliases = db.authorAliasDao().all().associate { it.fromName to it.toName }
        assertEquals("最终名", AuthorAliases.resolve("多写法", aliases))
        assertEquals("最终名", AuthorAliases.resolve("正名", aliases))
        // 表里没有两层：两个旧名都直接指向最终名
        assertEquals("最终名", db.authorAliasDao().targetOf("多写法"))
        assertEquals(3, db.bookDao().byAuthor("最终名").size)
    }

    @Test
    fun `第二次合并提交后再撤销第二次的窗口_只留在上一步`() = runBlocking {
        merge("多写法", "正名")
        merger.prepare("正名", "最终名")
        merger.pending()?.let(merger::cancelWindow)
        // 第一次合并没有被一起撤掉
        assertEquals(3, db.bookDao().byAuthor("正名").size)
        assertEquals("正名", db.authorAliasDao().targetOf("多写法"))
        assertNull(db.authorAliasDao().targetOf("正名"))
    }

    @Test
    fun `合并到不存在的名字等于改名`() = runBlocking {
        merge("多写法", "新名字")
        assertEquals(2, db.bookDao().byAuthor("新名字").size)
        assertEquals("新名字", db.authorAliasDao().targetOf("多写法"))
    }

    /** 窗口里扫进来的书也按旧名落库，提交时一起改名——这正是延迟提交的好处 */
    @Test
    fun `窗口内扫进来的书在提交时一起改名`() = runBlocking {
        val source = db.sourceDao().all().first()
        merger.prepare("多写法", "正名")

        db.bookDao().insert(
            BookEntity(
                sourceId = source.id,
                docRef = "书9",
                title = "书9",
                titleSort = "shu9",
                author = "多写法",
                format = BookEntity.FORMAT_FOLDER,
                addedAt = 9,
                modifiedAt = 9,
            )
        )
        merger.commit(merger.pending()!!)

        assertEquals(0, db.bookDao().byAuthor("多写法").size)
        assertEquals(4, db.bookDao().byAuthor("正名").size)
    }
    @Test
    fun recreatedMergerKeepsTicketAndRejectsAnOldUndo() = runBlocking {
        val first = merger.prepare("多写法", "正名")!!
        val recreated = AuthorMerger(db)
        assertTrue(first === recreated.pending())
        assertNull(recreated.prepare("正名", "新名"))
        assertTrue(recreated.cancelWindow(first))
        val second = recreated.prepare("多写法", "新名")!!
        assertTrue(!merger.cancelWindow(first))
        kotlinx.coroutines.withTimeout(8_000) {
            recreated.window.first { it.pending == null }
        }
        assertTrue(!recreated.cancelWindow(second))
        assertEquals("新名", db.authorAliasDao().targetOf("多写法"))
    }

}
