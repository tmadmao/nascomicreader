package com.nascomic.reader.library.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 章序变了以后，进度与书签怎么搬。
 *
 * 书签在 (书, 章号, 章内页) 上有唯一约束，而章号是一起后移的：
 * 一条条挪的话，第一条要去的正是第二条还占着的章号，整轮扫描会回滚。
 */
@RunWith(AndroidJUnit4::class)
class ChapterWriterTest {

    private lateinit var db: ReaderDatabase
    private lateinit var writer: ChapterWriter
    private var bookId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = ChapterWriter(db)

        val sourceId = db.sourceDao().insert(
            com.nascomic.reader.library.data.SourceEntity(
                kind = com.nascomic.reader.library.data.SourceEntity.KIND_LOCAL,
                label = "测试",
                treeUri = "content://test",
                addedAt = 1000,
            )
        )
        bookId = db.bookDao().insert(
            BookEntity(
                sourceId = sourceId,
                docRef = "作品",
                title = "作品",
                titleSort = "zuopin",
                format = BookEntity.FORMAT_FOLDER,
                addedAt = 1000,
                modifiedAt = 1000,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `前面插一章_两章各有一枚书签也不撞索引`() = runBlocking {
        chapters("A" to 3, "B" to 3)
        val markA = mark(0, 0, "甲", 111)
        val markB = mark(1, 0, "乙", 222)
        db.progressDao().upsert(ProgressEntity(bookId, 1, 2, 5, false, 1000))

        writer.write(bookId, drafts("序" to "d/Z", "A" to "d/A", "B" to "d/B"))

        val marks = db.bookmarkDao().forBookNow(bookId).associateBy { it.id }
        assertEquals("A 的书签应当后移一位", 1, marks[markA]?.chapterOrdinal)
        assertEquals("B 的书签应当后移一位", 2, marks[markB]?.chapterOrdinal)
        // 书签本身不被重建：备注与创建时间都在
        assertEquals("甲", marks[markA]?.note)
        assertEquals(111L, marks[markA]?.createdAt)
        assertEquals("乙", marks[markB]?.note)
        // 进度跟着走：原来在第 2 章，现在是第 3 章
        assertEquals(2, db.progressDao().forBook(bookId)?.chapterOrdinal)
    }

    @Test
    fun `删掉一章_后面的书签前移`() = runBlocking {
        chapters("A" to 3, "B" to 3, "C" to 3)
        val markC = mark(2, 1, "丙", 333)

        writer.write(bookId, drafts("A" to "d/A", "C" to "d/C"))

        assertEquals(1, db.bookmarkDao().forBookNow(bookId).single().chapterOrdinal)
        assertEquals("丙", db.bookmarkDao().forBookNow(bookId).single().note)
        assertNotNull(db.bookmarkDao().forBookNow(bookId).firstOrNull { it.id == markC })
    }

    @Test
    fun `书签所在的章没了_书签跟着作废`() = runBlocking {
        chapters("A" to 3, "B" to 3, "C" to 3)
        val markA = mark(0, 0, "甲", 111)
        val markB = mark(1, 0, "乙", 222)
        // B 这一章被删掉了：它的书签指向的内容已经不存在
        writer.write(bookId, drafts("A" to "d/A", "C" to "d/C"))

        val marks = db.bookmarkDao().forBookNow(bookId).associateBy { it.id }
        assertNotNull("A 的还在", marks[markA])
        assertNull("B 的应当一并清掉，否则会挂到别的章上", marks[markB])
    }

    @Test
    fun `章序没变_书签一个都不动`() = runBlocking {
        chapters("A" to 3, "B" to 3)
        val markA = mark(0, 1, "甲", 111)

        writer.write(bookId, drafts("A" to "d/A", "B" to "d/B"))

        val mark = db.bookmarkDao().forBookNow(bookId).single()
        assertEquals(0, mark.chapterOrdinal)
        assertEquals(1, mark.pageInChapter)
        assertEquals("甲", mark.note)
    }

    @Test
    fun `一条条挪会撞唯一索引_所以必须两步`() = runBlocking {
        chapters("A" to 3, "B" to 3)
        val markA = mark(0, 0, "甲", 111)
        mark(1, 0, "乙", 222)

        // A 要去的正是 B 还占着的位置。这条断言把「为什么不能一条条挪」钉住：
        // 哪天有人图省事改回单条搬迁，这里会先红
        val error = runCatching { db.bookmarkDao().moveToChapter(markA, 1) }.exceptionOrNull()
        assertNotNull("单条搬迁必须撞唯一索引，否则两步就是多余的", error)
    }

    // ---- 造数据 ----

    private suspend fun chapters(vararg pairs: Pair<String, Int>) {
        db.chapterDao().replaceFor(
            bookId,
            pairs.mapIndexed { index, (title, pages) ->
                ChapterEntity(
                    bookId = bookId,
                    ordinal = index,
                    title = title,
                    chapterRef = "d/$title",
                    chapterKind = ChapterEntity.KIND_FOLDER,
                    pageTotal = pages,
                )
            },
        )
    }

    private fun drafts(vararg pairs: Pair<String, String>) = pairs.map { (title, ref) ->
        ChapterWriter.Draft(title, ref, ChapterEntity.KIND_FOLDER)
    }

    private suspend fun mark(chapter: Int, page: Int, note: String, at: Long): Long =
        db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookId,
                chapterOrdinal = chapter,
                pageInChapter = page,
                note = note,
                createdAt = at,
            )
        )
}
