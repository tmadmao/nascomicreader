package com.nascomic.reader.library.scan

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ChapterEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SeriesBookEntity
import com.nascomic.reader.library.data.SeriesEntity
import com.nascomic.reader.library.data.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 远端扫描的入库规则：空清单不清库、明确删除只删那一本、章表为空也要清。
 *
 * 用内存库 + 假的列目录函数，把「真 NAS 很难按需变空」的那几种情况直接造出来。
 */
@RunWith(AndroidJUnit4::class)
class RemoteScanRulesTest {

    private lateinit var db: ReaderDatabase
    private lateinit var scanner: LibraryScanner

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scanner = LibraryScanner(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `空清单_书库与进度书签系列原样保留`() = runBlocking {
        val source = newSource()
        val bookId = newBook(source.id, "漫画/a.zip", "甲", format = BookEntity.FORMAT_ZIP)
        db.progressDao().upsert(ProgressEntity(bookId, 0, 3, 7, false, 2000))
        db.bookmarkDao().insert(BookmarkEntity(bookId = bookId, chapterOrdinal = 0, pageInChapter = 3, createdAt = 2000))
        val seriesId = db.seriesDao().insert(SeriesEntity(name = "甲系列", nameSort = "jia", createdAt = 1000))
        db.seriesDao().putMember(SeriesBookEntity(seriesId = seriesId, bookId = bookId, ordinal = 0, addedAt = 1000))

        // 挂载暂时不可见：列目录「正常结束」但一个子项都没有
        val result = scanner.scanRemoteForTest(source, source.endpoint!!) { emptyList() }

        assertTrue("应走「整库保留」这条路", result.keptAll)
        assertEquals(0, result.removed)
        assertEquals(1, db.bookDao().count())
        assertEquals(7, db.progressDao().forBook(bookId)?.pageGlobal)
        assertEquals(1, db.bookmarkDao().forBookNow(bookId).size)
        assertNotNull(db.seriesDao().membershipOf(bookId))

        val after = db.sourceDao().get(source.id)
        assertEquals(SourceEntity.STATE_FAILED, after?.scanState)
        assertEquals(SourceEntity.ERROR_EMPTY_SCAN, after?.errorCode)
    }

    @Test
    fun `清单里真的少了一本_只删那一本`() = runBlocking {
        val source = newSource()
        val keep = newBook(source.id, "漫画/b.zip", "乙", format = BookEntity.FORMAT_ZIP)
        val gone = newBook(source.id, "漫画/a.zip", "甲", format = BookEntity.FORMAT_ZIP)
        db.progressDao().upsert(ProgressEntity(gone, 0, 1, 1, false, 2000))

        val result = scanner.scanRemoteForTest(source, source.endpoint!!) {
            listOf(LibraryScanner.RemoteEntry("漫画/b.zip", "b.zip", false, 100))
        }

        assertEquals(1, db.bookDao().count())
        assertNotNull(db.bookDao().get(keep))
        assertNull("被删的书连进度一起走（外键级联）", db.bookDao().get(gone))
        assertEquals(0, db.progressDao().all().size)
        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(source.id)?.scanState)
        assertEquals(1, result.removed)
    }

    @Test
    fun `章回体变散图_旧章被清掉`() = runBlocking {
        val source = newSource()
        val bookId = newBook(source.id, "漫画/c", "丙", format = BookEntity.FORMAT_FOLDER)
        db.chapterDao().replaceFor(
            bookId,
            listOf(
                ChapterEntity(bookId = bookId, ordinal = 0, title = "第1话", chapterRef = "漫画/c/第1话", chapterKind = ChapterEntity.KIND_FOLDER, pageTotal = 3),
                ChapterEntity(bookId = bookId, ordinal = 1, title = "第2话", chapterRef = "漫画/c/第2话", chapterKind = ChapterEntity.KIND_FOLDER, pageTotal = 3),
            ),
        )
        db.progressDao().upsert(ProgressEntity(bookId, 1, 1, 4, false, 2000))

        // 同一路径现在直接放图：分类结果变成「散图夹」，没有章
        scanner.scanRemoteForTest(source, source.endpoint!!) { dir ->
            when (dir) {
                source.endpoint -> listOf(LibraryScanner.RemoteEntry("漫画/c", "c", true, 0))
                else -> listOf(LibraryScanner.RemoteEntry("漫画/c/01.jpg", "01.jpg", false, 10))
            }
        }

        assertEquals("旧章必须清掉", 0, db.chapterDao().forBook(bookId).size)
        // 进度留着但钳到第一页：章号已经失去意义
        val p = db.progressDao().forBook(bookId)
        assertNotNull(p)
        assertEquals(0, p?.chapterOrdinal)
        assertEquals(0, p?.pageGlobal)
    }

    @Test
    fun `正常扫描_书与章与完成状态一起落库`() = runBlocking {
        val source = newSource()

        val result = scanner.scanRemoteForTest(source, source.endpoint!!) { dir ->
            when (dir) {
                source.endpoint -> listOf(LibraryScanner.RemoteEntry("漫画/d", "d", true, 0))
                else -> listOf(
                    LibraryScanner.RemoteEntry("漫画/d/第1话", "第1话", true, 0),
                    LibraryScanner.RemoteEntry("漫画/d/第2话.zip", "第2话.zip", false, 100),
                )
            }
        }

        assertEquals(1, result.found)
        val book = db.bookDao().bySourceNow(source.id).single()
        assertEquals(2, book.chapterTotal)
        assertEquals(0, book.isSingleVolume)
        assertEquals(2, db.chapterDao().forBook(book.id).size)
        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(source.id)?.scanState)
        assertNull(db.sourceDao().get(source.id)?.errorCode)
    }

    // ---- 造数据 ----

    private suspend fun newSource(): SourceEntity {
        val source = SourceEntity(
            kind = SourceEntity.KIND_WEBDAV,
            label = "联调",
            endpoint = "http://127.0.0.1:5399/dav/",
            addedAt = 1000,
        )
        return source.copy(id = db.sourceDao().insert(source))
    }

    private suspend fun newBook(
        sourceId: Long,
        docRef: String,
        title: String,
        format: Int,
    ): Long = db.bookDao().insert(
        BookEntity(
            sourceId = sourceId,
            docRef = docRef,
            title = title,
            titleSort = title,
            format = format,
            byteSize = 100,
            addedAt = 1000,
            modifiedAt = 1000,
        )
    )
}
