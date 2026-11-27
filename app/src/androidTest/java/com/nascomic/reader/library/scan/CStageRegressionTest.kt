package com.nascomic.reader.library.scan

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nascomic.reader.format.BookOpener
import com.nascomic.reader.library.author.AuthorMerger
import com.nascomic.reader.library.cover.ThumbnailStore
import com.nascomic.reader.library.data.BookEntity
import com.nascomic.reader.library.data.BookmarkEntity
import com.nascomic.reader.library.data.ProgressEntity
import com.nascomic.reader.library.data.ReaderDatabase
import com.nascomic.reader.library.data.SourceEntity
import com.nascomic.reader.library.mode.BookModeBatch
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 第三批 C 的回归用例。
 *
 * 每一条对应审计里的一项：有的是发现的缺陷（远端只读到一半仍提交、预览后删页、
 * 书签备注丢失、单册压缩包被卷进目录组、撤销漏掉窗口内新书），有的是设计边界
 * （来源根目录永远是容器）。之前它们是「断言错误行为」的探针，现在改成断言期望行为。
 *
 * 全部走内存库 + 设备回环 WebDAV，不碰真实书库与网络。
 */
@RunWith(AndroidJUnit4::class)
class CStageRegressionTest {

    /** 目标目录 503：整组必须不动，一本书都不能少 */
    @Test
    fun `远端目标目录读不到时整组不动`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertEquals(2, group.targets.size)

            dav.failB = true
            val result = batch.execute(group)

            assertTrue(result.message, !result.ok)
            // 原书还在，两章都还在，覆盖没写进去
            val now = db.bookDao().bySourceNow(source.id)
            assertEquals(1, now.size)
            assertEquals(2, db.chapterDao().forBook(now.single().id).size)
            assertNull(db.modeOverrideDao().get(source.id, group.rootRef))
        }
    }

    /** 来源根目录永远是容器：在根上并出来的书会被下一次全扫拆回去，干脆不让并 */
    @Test
    fun `来源根目录不能并成一本书`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.useRoot = true
            scanner.scan(source)
            val first = db.bookDao().bySourceNow(source.id).first { it.title == "A" }
            val preview = batch.preview(listOf(first.id), BookModeBatch.Target.SERIAL)

            assertTrue(preview is BookModeBatch.Preview.Failed)
            assertTrue((preview as BookModeBatch.Preview.Failed).message.contains("根目录"))
        }
    }

    /** 子目录里的合并要经得起全库重扫：书目、进度、覆盖都保住 */
    @Test
    fun `子目录合并后全量重扫书目与进度都在`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { _, db, scanner, source, batch ->
            scanner.scan(source)
            val first = db.bookDao().bySourceNow(source.id).first { it.title == "A" }
            db.progressDao().upsert(ProgressEntity(first.id, 0, 0, 0, false, 1000))

            val group = (batch.preview(listOf(first.id), BookModeBatch.Target.SERIAL) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertEquals(2, group.oldIds.size)
            assertTrue(batch.execute(group).ok)

            val merged = db.bookDao().bySourceNow(source.id).single()
            assertNotNull(db.progressDao().forBook(merged.id))
            assertNotNull(db.modeOverrideDao().get(source.id, group.rootRef))

            scanner.scan(source)

            val after = db.bookDao().bySourceNow(source.id)
            assertEquals(1, after.size)
            assertEquals(merged.docRef, after.single().docRef)
            // 进度按章内页迁过来，重扫之后还在
            assertNotNull(db.progressDao().forBook(after.single().id))
            assertNotNull(db.modeOverrideDao().get(source.id, group.rootRef))
        }
    }

    /** 拆分按书引用直达：不去从来源根遍历无关目录 */
    @Test
    fun `拆分不从头遍历来源`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            dav.hits.clear()

            val preview = batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE)

            assertTrue(preview is BookModeBatch.Preview.Ready)
            // 根目录只该在「合并」时被找到；拆分只需要读这本书自己的章
            assertTrue("预览不该去列来源根：${dav.hits}", "/dav/" !in dav.hits)
        }
    }

    /** 拆分 → 全库重扫 → 恢复来源 → 再重扫：每一步进度与书签都要跟着走 */
    @Test
    fun `拆分开再恢复_两次全扫之后进度书签都在`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { _, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            db.progressDao().upsert(ProgressEntity(book.id, 0, 0, 0, false, 1000))
            db.bookmarkDao().insert(
                BookmarkEntity(bookId = book.id, chapterOrdinal = 1, pageInChapter = 0, note = "第二章开头", createdAt = 1000)
            )

            // 1) 拆成两本
            val split = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertTrue(batch.execute(split).ok)
            assertEquals(setOf("A", "B"), db.bookDao().bySourceNow(source.id).map { it.title }.toSet())

            // 2) 全库重扫：拆出来的两本不能被并回去，进度书签也不能丢
            scanner.scan(source)
            val afterSplit = db.bookDao().bySourceNow(source.id)
            assertEquals(setOf("A", "B"), afterSplit.map { it.title }.toSet())
            val aBook = afterSplit.single { it.title == "A" }
            val bBook = afterSplit.single { it.title == "B" }
            assertEquals(0, db.progressDao().forBook(aBook.id)?.pageInChapter)
            assertEquals("第二章开头", db.bookmarkDao().forBookNow(bBook.id).single().note)

            // 3) 恢复跟随来源：并回那一本
            val restore = (batch.preview(listOf(aBook.id), BookModeBatch.Target.RESTORE) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertTrue(batch.execute(restore).ok)
            val merged = db.bookDao().bySourceNow(source.id).single()
            assertEquals(2, db.chapterDao().forBook(merged.id).size)
            assertNull(db.modeOverrideDao().get(source.id, restore.rootRef))

            // 4) 再全扫：还是这一本，进度在第 1 章第 1 页，书签在第 2 章且备注还在
            scanner.scan(source)
            val final = db.bookDao().bySourceNow(source.id).single()
            assertEquals(merged.docRef, final.docRef)
            val progress = db.progressDao().forBook(final.id)
            assertNotNull(progress)
            assertEquals(0, progress!!.chapterOrdinal)
            assertEquals(0, progress.pageInChapter)
            val mark = db.bookmarkDao().forBookNow(final.id).single()
            assertEquals(1, mark.chapterOrdinal)
            assertEquals("第二章开头", mark.note)
        }
    }

    /**
     * 混合目录：同层的独立压缩包不参与合并，全扫也不能把它吞成章。
     *
     * 吞掉的代价是它的书行、进度、书签、自选封面一起没了。
     */
    @Test
    fun `独立压缩包在合并与全扫之后仍是独立书`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.looseArchive = true
            scanner.scan(source)
            val before = db.bookDao().bySourceNow(source.id)
            val files = before.filter { it.format != BookEntity.FORMAT_FOLDER }
            assertEquals(setOf("Loose", "Document", "Novel"), files.map { it.title }.toSet())
            val archive = files.single { it.title == "Loose" }
            val first = before.single { it.title == "A" }
            db.progressDao().upsert(ProgressEntity(archive.id, 0, 1, 1, false, 1000))
            db.bookmarkDao().insert(
                BookmarkEntity(bookId = archive.id, chapterOrdinal = 0, pageInChapter = 1, note = "独立那一本", createdAt = 1000)
            )
            db.bookDao().updateCoverPage(archive.id, 1)

            // 1) 只选 A 合并：ZIP 不在组里，也不该被并成章
            val group = (batch.preview(listOf(first.id), BookModeBatch.Target.SERIAL) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertEquals(listOf("B"), group.incoming.map { it.title })
            assertTrue(batch.execute(group).ok)
            assertNotNull(db.bookDao().get(archive.id))

            // 2) 全扫：目录书还是那一本连载，ZIP 还是独立的一本
            scanner.scan(source)
            assertAllKept(db, source.id, archive.docRef)
            files.forEach { assertEquals(it.id, db.bookDao().findByRef(source.id, it.docRef)?.id) }

            // 3) 恢复来源再全扫一遍，ZIP 依旧不受影响
            val merged = db.bookDao().bySourceNow(source.id).single { it.format == BookEntity.FORMAT_FOLDER }
            assertEquals(listOf("A", "B"), db.chapterDao().forBook(merged.id).map { it.title })
            val restore = (batch.preview(listOf(merged.id), BookModeBatch.Target.RESTORE) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertTrue(batch.execute(restore).ok)
            scanner.scan(source)
            assertAllKept(db, source.id, archive.docRef)
            files.forEach { assertEquals(it.id, db.bookDao().findByRef(source.id, it.docRef)?.id) }
            // A、B 又各自成书了
            assertEquals(
                setOf("A", "B", "Loose", "Document", "Novel"),
                db.bookDao().bySourceNow(source.id).map { it.title }.toSet(),
            )
        }
    }

    /** 页数没变不等于还是那几页：p1、p2 换成 p2、p3 时必须拒绝执行 */
    @Test
    fun `换了页但页数不变时拒绝执行`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            db.progressDao().upsert(ProgressEntity(book.id, 0, 0, 0, false, 1000))

            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready)
                .groups.single()

            // 页数还是 2，但第一页已经是 p2：进度会落到另一张图上
            dav.aFirstPage = 2
            val result = batch.execute(group)

            assertTrue(result.message, !result.ok)
            assertEquals(1, db.bookDao().bySourceNow(source.id).size)
            assertNull(db.modeOverrideDao().get(source.id, group.rootRef))
        }
    }

    @Test
    fun twoIndependentFolderGroupsCanCommitFromOnePreview() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            dav.secondBundle = true
            scanner.scan(source)
            val ids = db.bookDao().bySourceNow(source.id).map { it.id }
            assertEquals(2, ids.size)
            val groups = (batch.preview(ids, BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready).groups
            assertEquals(2, groups.size)
            groups.forEach { group ->
                val result = batch.execute(group)
                assertTrue(result.message, result.ok)
            }
            assertEquals(4, db.bookDao().bySourceNow(source.id).size)
        }
    }

    @Test
    fun sameFileNamesWithNewEtagsRejectThePlan() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            val progress = ProgressEntity(book.id, 0, 0, 0, false, 1000)
            db.progressDao().upsert(progress)
            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready).groups.single()
            dav.aRevision++
            assertTrue(!batch.execute(group).ok)
            assertEquals(progress, db.progressDao().forBook(book.id))
        }
    }

    @Test
    fun mergeThenRestoreMovesLatestProgressMarksCoverAndSeries() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.bPages = 2
            scanner.scan(source)
            val old = db.bookDao().bySourceNow(source.id).associateBy { it.title }
            val a = old.getValue("A")
            val b = old.getValue("B")
            db.progressDao().upsert(ProgressEntity(a.id, 0, 1, 1, false, 1000))
            db.progressDao().upsert(ProgressEntity(b.id, 0, 1, 1, false, 2000))
            db.bookmarkDao().insert(BookmarkEntity(bookId = a.id, chapterOrdinal = 0, pageInChapter = 0, note = "A note", createdAt = 1))
            db.bookmarkDao().insert(BookmarkEntity(bookId = b.id, chapterOrdinal = 0, pageInChapter = 1, note = "B note", createdAt = 2))
            db.bookDao().updateCoverPage(b.id, 1)
            val series = db.seriesDao().insert(com.nascomic.reader.library.data.SeriesEntity(name = "Series", nameSort = "series", createdAt = 1))
            db.seriesDao().insertAt(series, b.id, 4, 1)
            val merge = (batch.preview(listOf(a.id), BookModeBatch.Target.SERIAL) as BookModeBatch.Preview.Ready).groups.single()
            val mergedResult = batch.execute(merge)
            assertTrue(mergedResult.message, mergedResult.ok)
            scanner.scan(source)
            val merged = db.bookDao().bySourceNow(source.id).single()
            assertEquals(ProgressEntity(merged.id, 1, 1, 3, false, 2000), db.progressDao().forBook(merged.id))
            assertEquals(setOf("A note", "B note"), db.bookmarkDao().forBookNow(merged.id).map { it.note }.toSet())
            assertEquals(3, merged.coverPage)
            assertEquals(4, db.seriesDao().membershipOf(merged.id)?.ordinal)
            val restore = (batch.preview(listOf(merged.id), BookModeBatch.Target.RESTORE) as BookModeBatch.Preview.Ready).groups.single()
            val restoredResult = batch.execute(restore)
            assertTrue(restoredResult.message, restoredResult.ok)
            scanner.scan(source)
            val restored = db.bookDao().bySourceNow(source.id).associateBy { it.title }
            val newA = restored.getValue("A")
            val newB = restored.getValue("B")
            assertNull(db.progressDao().forBook(newA.id))
            assertEquals(ProgressEntity(newB.id, 0, 1, 1, false, 2000), db.progressDao().forBook(newB.id))
            assertEquals("A note", db.bookmarkDao().forBookNow(newA.id).single().note)
            assertEquals("B note", db.bookmarkDao().forBookNow(newB.id).single().note)
            assertEquals(1, newB.coverPage)
            assertEquals(4, db.seriesDao().membershipOf(newB.id)?.ordinal)
        }
    }

    @Test
    fun mergeRejectsChangedPrefixBeforeSavedProgressAndCover() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.bPages = 2
            scanner.scan(source)
            val books = db.bookDao().bySourceNow(source.id)
            val b = books.single { it.title == "B" }
            val progress = ProgressEntity(b.id, 0, 1, 1, false, 1000)
            db.progressDao().upsert(progress)
            db.bookDao().updateCoverPage(b.id, 1)
            val group = (batch.preview(listOf(b.id), BookModeBatch.Target.SERIAL) as BookModeBatch.Preview.Ready).groups.single()
            assertEquals(3, group.plan.progress.single().pageGlobal)
            assertEquals(3, group.plan.covers.single().page)
            dav.aPages++
            assertTrue(!batch.execute(group).ok)
            assertEquals(progress, db.progressDao().forBook(b.id))
            assertEquals(1, db.bookDao().get(b.id)?.coverPage)
        }
    }

    @Test
    fun progressWrittenDuringPreflightSurvives() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            val progress = ProgressEntity(book.id, 0, 0, 0, false, 1000)
            db.progressDao().upsert(progress)
            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready).groups.single()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            dav.pauseNext = started to release
            val running = async(Dispatchers.IO) { batch.execute(group) }
            val newer = progress.copy(pageGlobal = 1, pageInChapter = 1, updatedAt = 2000)
            try {
                assertTrue(started.await(4, TimeUnit.SECONDS))
                db.progressDao().upsert(newer)
            } finally {
                release.countDown()
            }
            assertTrue(!running.await().ok)
            assertEquals(newer, db.progressDao().forBook(book.id))
            assertNull(db.modeOverrideDao().get(source.id, group.rootRef))
        }
    }

    @Test
    fun scanWithStaleModeSnapshotDoesNotWrite() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { _, db, scanner, source, _ ->
            scanner.scan(source)
            val written = java.util.concurrent.atomic.AtomicBoolean()
            val original = db.bookDao().bySourceNow(source.id).map { it.id }.toSet()
            val changed = runCatching {
                scanner.scanRemoteForTest(source, "root") { _ ->
                    if (written.compareAndSet(false, true)) runBlocking {
                        db.modeOverrideDao().put(com.nascomic.reader.library.data.BookModeOverrideEntity(
                            sourceId = source.id, docRef = "root/new", mode = SourceEntity.MODE_SERIAL, createdAt = 1000,
                        ))
                    }
                    listOf(LibraryScanner.RemoteEntry("root/book.zip", "book.zip", false, 42))
                }
            }
            assertTrue(changed.exceptionOrNull() is LibraryScanner.SourceChanged)
            assertEquals(original, db.bookDao().bySourceNow(source.id).map { it.id }.toSet())
            assertEquals(SourceEntity.STATE_IDLE, db.sourceDao().get(source.id)?.scanState)
        }
    }

    @Test
    fun smbParentUsesShareRelativePathAndOverrides() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { _, db, scanner, _, _ ->
            val draft = SourceEntity(kind = SourceEntity.KIND_SMB, label = "test", endpoint = "smb://example.local/share",
                addedAt = 2, bookMode = SourceEntity.MODE_SERIAL)
            val smb = draft.copy(id = db.sourceDao().insert(draft))
            db.modeOverrideDao().put(com.nascomic.reader.library.data.BookModeOverrideEntity(
                sourceId = smb.id, docRef = "Shelf/Bundle", mode = SourceEntity.MODE_SINGLE, createdAt = 1000,
            ))
            val child = scanner.locate(smb, "Shelf/Bundle/A")
            assertEquals("Shelf/Bundle", child?.parentRef)
            assertEquals(SourceEntity.MODE_SINGLE, child?.parentMode)
            assertEquals("", scanner.locate(smb, "Folder")?.parentRef)
            assertNull(scanner.locate(smb.copy(endpoint = "smb://example.local/share/Shelf"), "Shelf2/Bundle/A"))
        }
    }

    /** 合并同一父目录下的两本，不该把无关分支再走一遍 */
    @Test
    fun `合并两兄弟不访问无关目录`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.includeNoise = true
            scanner.scan(source)
            val ids = db.bookDao().bySourceNow(source.id).filter { it.title == "A" || it.title == "B" }.map { it.id }
            assertEquals(2, ids.size)
            dav.hits.clear()

            assertTrue(batch.preview(ids, BookModeBatch.Target.SERIAL) is BookModeBatch.Preview.Ready)

            assertTrue("不该遍历无关目录：${dav.hits}", dav.hits.none { it == "/dav/Noise/" })
        }
    }

    /** 拆分搬运书签时，备注要一起搬 */
    @Test
    fun `拆分后书签备注还在`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { _, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            db.bookmarkDao().insert(
                BookmarkEntity(
                    bookId = book.id,
                    chapterOrdinal = 0,
                    pageInChapter = 0,
                    note = "这一页的伏笔",
                    createdAt = 1000,
                )
            )

            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready)
                .groups.single()
            assertTrue(batch.execute(group).ok)

            val first = db.bookDao().bySourceNow(source.id).first { it.title == "A" }
            assertEquals("这一页的伏笔", db.bookmarkDao().forBookNow(first.id).single().note)
        }
    }

    /** 一册 zip 就是一本，不能拿来「改书型」，更不能把同级的目录书卷进去 */
    @Test
    fun `单册压缩包不参与改书型`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { dav, db, scanner, source, batch ->
            dav.looseArchive = true
            scanner.scan(source)
            val archive = db.bookDao().bySourceNow(source.id).single { it.title == "Loose" }

            val preview = batch.preview(listOf(archive.id), BookModeBatch.Target.SERIAL)

            assertTrue(preview is BookModeBatch.Preview.Failed)
            // 那两本目录书一本都没被并走
            assertEquals(setOf("A", "B", "Loose", "Document", "Novel"), db.bookDao().bySourceNow(source.id).map { it.title }.toSet())
        }
    }

    /** 5 秒窗口里扫进来的书不动；到点提交时按那时的库一起改名 */
    @Test
    fun `窗口内扫进来的书跟着提交一起改名`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { _, db, scanner, source, _ ->
            val root = source.endpoint!!
            val first = LibraryScanner.RemoteEntry(root + "/first.zip", "[Old] First.zip", false, 90)
            val second = LibraryScanner.RemoteEntry(root + "/second.zip", "[Old] Second.zip", false, 90)
            scanner.scanRemoteForTest(source, root) { listOf(first) }
            assertEquals("Old", db.bookDao().bySourceNow(source.id).single().author)

            val merger = AuthorMerger(db)
            val pending = merger.prepare("Old", "Canonical")!!

            // 窗口内扫进来的书仍按旧名落库——别名还没写，谁都不用猜
            scanner.scanRemoteForTest(source, root) { listOf(first, second) }
            assertEquals(
                "Old",
                db.bookDao().bySourceNow(source.id).single { it.docRef == second.ref }.author,
            )

            merger.commit(pending)

            val after = db.bookDao().bySourceNow(source.id)
            assertEquals(2, after.count { it.author == "Canonical" })
            assertEquals("Canonical", db.authorAliasDao().targetOf("Old"))
        }
    }

    /** 窗口里点撤销：库里一个字都不改，原本就归在正名下的书也不受影响 */
    @Test
    fun `撤销只取消这次合并_不动别的书`() = runBlocking {
        withLibrary(SourceEntity.MODE_SINGLE) { _, db, scanner, source, _ ->
            val root = source.endpoint!!
            val active = LibraryScanner.RemoteEntry(root + "/active.zip", "[Old] Active.zip", false, 90)
            val manual = LibraryScanner.RemoteEntry(root + "/manual.zip", "[Old] Manual.zip", false, 90)
            scanner.scanRemoteForTest(source, root) { listOf(active, manual) }
            // 这本是用户早先手动归到正名下的，撤销不该把它也带走
            val manualId = db.bookDao().bySourceNow(source.id).single { it.docRef == manual.ref }.id
            db.bookDao().setAuthor(manualId, "Canonical")

            val merger = AuthorMerger(db)
            merger.prepare("Old", "Canonical")
            assertTrue(merger.pending()?.let(merger::cancelWindow) == true)

            assertEquals("Old", db.bookDao().bySourceNow(source.id).single { it.docRef == active.ref }.author)
            assertEquals("Canonical", db.bookDao().get(manualId)?.author)
            assertNull(db.authorAliasDao().all().firstOrNull { it.fromName == "Old" })
        }
    }

    /** 预览之后少了一页：进度没有落点，整组拒绝执行，库里一个字都不动 */
    @Test
    fun `预览后章页数变少时拒绝执行`() = runBlocking {
        withLibrary(SourceEntity.MODE_SERIAL) { dav, db, scanner, source, batch ->
            scanner.scan(source)
            val book = db.bookDao().bySourceNow(source.id).single()
            // 第 2 页（0 起算的第 1 页）读完就走
            db.progressDao().upsert(ProgressEntity(book.id, 0, 1, 1, false, 1000))

            val group = (batch.preview(listOf(book.id), BookModeBatch.Target.SINGLE) as BookModeBatch.Preview.Ready)
                .groups.single()

            dav.aPages = 1
            val result = batch.execute(group)

            assertTrue(result.message, !result.ok)
            assertEquals(1, db.bookDao().bySourceNow(source.id).size)
            assertEquals(1, db.progressDao().forBook(book.id)?.pageInChapter)
            assertNull(db.modeOverrideDao().get(source.id, group.rootRef))
        }
    }

    /** 独立压缩包的记录、进度、书签、封面都还在 */
    private suspend fun assertAllKept(db: ReaderDatabase, sourceId: Long, docRef: String) {
        val book = db.bookDao().bySourceNow(sourceId).singleOrNull { it.docRef == docRef }
        assertNotNull("独立压缩包的书行被吞掉了", book)
        assertNotNull(db.progressDao().forBook(book!!.id))
        assertEquals("独立那一本", db.bookmarkDao().forBookNow(book.id).single().note)
        assertEquals(1, book.coverPage)
    }

    // ── 夹具 ───────────────────────────────────────────────

    private suspend fun withLibrary(
        mode: Int,
        block: suspend (Dav, ReaderDatabase, LibraryScanner, SourceEntity, BookModeBatch) -> Unit,
    ) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "c-audit-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getCacheDir() = isolated
            override fun getFilesDir() = isolated
        }
        val db = Room.inMemoryDatabaseBuilder(target, ReaderDatabase::class.java).build()
        try {
            Dav().use { dav ->
                val draft = SourceEntity(
                    kind = SourceEntity.KIND_WEBDAV,
                    label = "audit",
                    endpoint = dav.endpoint,
                    bookMode = mode,
                    addedAt = 1,
                )
                val source = draft.copy(id = db.sourceDao().insert(draft))
                val scanner = LibraryScanner(context, db)
                val batch = BookModeBatch(context, db, scanner, BookOpener(context), ThumbnailStore(context))
                block(dav, db, scanner, source, batch)
            }
        } finally {
            db.close()
            isolated.deleteRecursively()
        }
    }

    /**
     * 回环 WebDAV：只回 PROPFIND 的多状态响应，足够走完「列目录 → 认书 → 取页」。
     *
     * [useRoot] 让 A、B 直接挂在来源根下；[failB] 让 B 目录返回 503；
     * [looseArchive] 在根下多放一个 Loose.zip；[aPages] 控制 A 里有几页。
     */
    private class Dav : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val endpoint = "http://127.0.0.1:${socket.localPort}/dav"
        @Volatile var failB = false
        @Volatile var useRoot = false
        @Volatile var looseArchive = false
        @Volatile var aPages = 2
        @Volatile var bPages = 1
        @Volatile var aRevision = 1
        @Volatile var pauseNext: Pair<CountDownLatch, CountDownLatch>? = null

        /** A 章第一页的页号：改它就能在页数不变的情况下换掉页 */
        @Volatile var aFirstPage = 1

        /** 根下多一个无关目录，用来看合并会不会把整棵树都走一遍 */
        @Volatile var includeNoise = false
        @Volatile var secondBundle = false

        /** 这一轮代理收到过哪些路径，用来确认「拆分没有从头遍历整个来源」 */
        val hits: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { conn ->
                        conn.soTimeout = 5000
                        val reader = conn.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        hits += path
                        while (!reader.readLine().isNullOrEmpty()) { }
                        pauseNext?.let { (started, release) ->
                            pauseNext = null
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        val base = when {
                            useRoot -> "/dav/"
                            path.startsWith("/dav/Second/") -> "/dav/Second/"
                            else -> "/dav/Bundle/"
                        }
                        val status = if (failB && path == base + "B/") "503 Unavailable" else "207 Multi-Status"
                        val children = when (path) {
                            base -> listOf(base + "A/", base + "B/") +
                                if (looseArchive) listOf(base + "Loose.zip", base + "Document.pdf", base + "Novel.epub") else emptyList()
                            "/dav/" -> listOf(base) +
                                (if (includeNoise) listOf("/dav/Noise/") else emptyList()) +
                                (if (secondBundle) listOf("/dav/Second/") else emptyList())
                            base + "A/" -> (aFirstPage until aFirstPage + aPages).map { path + "p$it.jpg" }
                            base + "B/" -> (1..bPages).map { path + "p$it.jpg" }
                            else -> emptyList()
                        }
                        val body = (listOf(path) + children)
                            .joinToString("", "<d:multistatus xmlns:d=\"DAV:\">", "</d:multistatus>") { ref ->
                                val type = if (ref.endsWith('/')) "<d:collection/>" else ""
                                "<d:response><d:href>$ref</d:href><d:propstat><d:prop><d:resourcetype>$type</d:resourcetype>" +
                                    "<d:getcontentlength>10</d:getcontentlength>" +
                                    (if (ref.startsWith(base + "A/")) "<d:getetag>${aRevision}</d:getetag>" else "") + "</d:prop>" +
                                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                            }.toByteArray()
                        conn.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n".toByteArray())
                            write("Content-Type: application/xml\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            worker.join(1000)
        }
    }
}
