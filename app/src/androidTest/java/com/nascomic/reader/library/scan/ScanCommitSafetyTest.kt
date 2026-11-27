package com.nascomic.reader.library.scan

import android.database.MatrixCursor
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ScanCommitSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ReaderDatabase
    private lateinit var scanner: LibraryScanner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        scanner = LibraryScanner(context, db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun cancelDuringListingRestoresSourceAndKeepsUserData() = runBlocking {
        val fixture = fixture(remote = true)
        val cancelled = AtomicBoolean()
        expectCancelled {
            scanner.scanRemoteForTest(fixture.source, ROOT, isCancelled = cancelled::get) {
                cancelled.set(true)
                emptyList()
            }
        }
        assertUnchanged(fixture)
    }

    @Test
    fun remoteCancelDuringWriteRollsBackAndNextScanCanCommit() = runBlocking {
        val fixture = fixture(remote = true)
        val cancelled = AtomicBoolean()
        val listener = object : LibraryScanner.Listener {
            override fun onProgress(done: Int, total: Int) {
                if (total > 0) cancelled.set(true)
            }
        }
        expectCancelled {
            scanner.scanRemoteForTest(fixture.source, ROOT, listener, cancelled::get) { remoteReplacement() }
        }
        assertUnchanged(fixture)

        var committing = false
        val commitListener = object : LibraryScanner.Listener {
            override fun onCommitting() {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                committing = true
            }
        }
        val result = scanner.scanRemoteForTest(fixture.source, ROOT, commitListener) { remoteReplacement() }
        assertTrue(committing)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertNull(db.progressDao().forBook(fixture.progress.bookId))
        assertTrue(db.bookmarkDao().forBookNow(fixture.progress.bookId).isEmpty())
        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(fixture.source.id)?.scanState)
    }

    @Test
    fun localCancelAtCommitBoundaryRollsBackEvenAfterDelete() = runBlocking {
        val fixture = fixture()
        val cancelled = AtomicBoolean()
        var reachedBoundary = false
        val listener = object : LibraryScanner.Listener {
            override fun onCommitting() {
                reachedBoundary = true
                cancelled.set(true)
            }
        }
        val walker = walker(mapOf(ROOT to listOf(file("new.cbz"))))
        expectCancelled { scanner.scanLocal(fixture.source, listener, cancelled::get) { walker } }
        assertTrue(reachedBoundary)
        assertUnchanged(fixture)
    }

    @Test
    fun localCompleteListingsRemoveMissingFilesAndKeepNestedStandaloneBook() = runBlocking {
        val fixture = fixture()
        val nested = book(fixture.source, "Series/Ch 1/bonus.epub", BookEntity.FORMAT_EPUB)
        val gonePdf = book(fixture.source, "Series/Ch 1/gone.pdf", BookEntity.FORMAT_PDF)
        val goneEpub = book(fixture.source, "gone.epub", BookEntity.FORMAT_EPUB)
        db.progressDao().upsert(ProgressEntity(nested, 0, 2, 2, false, 1))
        db.bookmarkDao().insert(BookmarkEntity(bookId = nested, chapterOrdinal = 0,
            pageInChapter = 2, createdAt = 1, note = "keep"))
        val walker = walker(mapOf(
            ROOT to listOf(dir("Series"), file("new.cbz")),
            "$ROOT/Series" to listOf(dir("Series/Ch 1")),
            "$ROOT/Series/Ch 1" to listOf(file("Series/Ch 1/001.jpg"), file("Series/Ch 1/bonus.epub")),
        ))

        val result = scanner.scanLocal(fixture.source, null, { false }) { walker }
        assertEquals(2, result.added)
        assertEquals(3, result.removed)
        assertEquals(setOf("$ROOT/Series", "$ROOT/Series/Ch 1/bonus.epub", "$ROOT/new.cbz"),
            db.bookDao().bySourceNow(fixture.source.id).map { it.docRef }.toSet())
        assertEquals(2, db.progressDao().forBook(nested)?.pageGlobal)
        assertEquals("keep", db.bookmarkDao().forBookNow(nested).single().note)
        for (id in listOf(fixture.progress.bookId, gonePdf, goneEpub)) assertNull(db.bookDao().get(id))
        assertNull(db.progressDao().forBook(fixture.progress.bookId))
        assertTrue(db.bookmarkDao().forBookNow(fixture.progress.bookId).isEmpty())
        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(fixture.source.id)?.scanState)
    }

    @Test
    fun localUnreadableOrIncompleteParentPreservesBooksAndCanRetry() = runBlocking {
        val fixture = fixture(oldPath = "Series/Ch 1/old.cbz")
        val parent = "$ROOT/Series/Ch 1"
        val listings = mapOf(
            ROOT to listOf(dir("Series"), file("new.cbz")),
            "$ROOT/Series" to listOf(dir("Series/Ch 1")),
        )
        for (fault in listOf(null, Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) },
            Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "unmounted") })) {
            val walker = if (fault == null) walker(listings) else walker(listings + (parent to emptyList()), fault)
            val failure = runCatching { scanner.scanLocal(fixture.source, null, { false }) { walker } }.exceptionOrNull()
            assertTrue(failure?.message, failure != null)
            assertUserData(fixture)
            val state = db.sourceDao().get(fixture.source.id)
            assertEquals(SourceEntity.STATE_FAILED, state?.scanState)
            assertEquals(SourceEntity.ERROR_TREE_PERMISSION_LOST, state?.errorCode)
        }
        val walker = walker(listings + (parent to listOf(file("Series/Ch 1/old.cbz"))))
        val result = scanner.scanLocal(fixture.source, null, { false }) { walker }
        assertEquals(0, result.removed)
        assertEquals(fixture.progress, db.progressDao().forBook(fixture.progress.bookId))
        assertEquals(fixture.bookmarks, db.bookmarkDao().forBookNow(fixture.progress.bookId))
        assertEquals(SourceEntity.STATE_DONE, db.sourceDao().get(fixture.source.id)?.scanState)
    }

    private data class Fixture(
        val source: SourceEntity,
        val books: List<BookEntity>,
        val progress: ProgressEntity,
        val bookmarks: List<BookmarkEntity>,
    )

    private suspend fun fixture(remote: Boolean = false, oldPath: String = "old.cbz"): Fixture {
        val source = SourceEntity(
            kind = if (remote) SourceEntity.KIND_WEBDAV else SourceEntity.KIND_LOCAL,
            label = "scan test", endpoint = if (remote) "http://localhost/" else null,
            treeUri = if (remote) null else TREE.toString(), addedAt = 1, lastScanAt = 2,
            scanState = SourceEntity.STATE_DONE,
        ).let { it.copy(id = db.sourceDao().insert(it)) }
        val id = book(source, oldPath, BookEntity.FORMAT_ZIP)
        val progress = ProgressEntity(id, 0, 3, 3, false, 4)
        db.progressDao().upsert(progress)
        db.bookmarkDao().insert(BookmarkEntity(bookId = id, chapterOrdinal = 0,
            pageInChapter = 3, createdAt = 5, note = "keep"))
        return Fixture(source, db.bookDao().bySourceNow(source.id), progress, db.bookmarkDao().forBookNow(id))
    }

    private suspend fun book(source: SourceEntity, path: String, format: Int) =
        db.bookDao().insert(BookEntity(sourceId = source.id, docRef = "$ROOT/$path", title = path, titleSort = path,
            format = format, addedAt = 1, modifiedAt = 1))

    private suspend fun assertUnchanged(fixture: Fixture) {
        assertEquals(fixture.source, db.sourceDao().get(fixture.source.id))
        assertUserData(fixture)
    }

    private suspend fun assertUserData(fixture: Fixture) {
        assertEquals(fixture.books, db.bookDao().bySourceNow(fixture.source.id))
        assertEquals(fixture.progress, db.progressDao().forBook(fixture.progress.bookId))
        assertEquals(fixture.bookmarks, db.bookmarkDao().forBookNow(fixture.progress.bookId))
    }

    private suspend fun expectCancelled(scan: suspend () -> LibraryScanner.Result) {
        val result = runCatching { scan() }
        assertTrue("expected cancellation: $result", result.exceptionOrNull() is LibraryScanner.Cancelled)
    }

    private fun remoteReplacement() = listOf(LibraryScanner.RemoteEntry("$ROOT/new.cbz", "new.cbz", false, 1))

    private fun dir(path: String) = ScanNode("$ROOT/$path", path.substringAfterLast('/'), true)
    private fun file(path: String) = ScanNode("$ROOT/$path", path.substringAfterLast('/'), false)

    private fun walker(children: Map<String, List<ScanNode>>, incomplete: Bundle? = null) =
        SafWalker(context, TREE) { uri, projection ->
            // ExternalStorageProvider returns a null cursor for a deleted document.
            if (uri.lastPathSegment != "children") return@SafWalker null
            val rows = children[DocumentsContract.getDocumentId(uri)] ?: return@SafWalker null
            MatrixCursor(projection).apply {
                for (row in rows) addRow(arrayOf<Any>(row.docId, row.name,
                    if (row.isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", 1, 1))
                if (uri.pathSegments[3].endsWith("/Ch 1") && incomplete != null) extras = incomplete
            }
        }

    private companion object {
        const val ROOT = "primary:AuditScan"
        val TREE = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", ROOT)
    }
}
