package com.nascomic.reader.library.mode

import com.nascomic.reader.library.mode.BookModePlanner.BookmarkMove
import com.nascomic.reader.library.mode.BookModePlanner.CoverMove
import com.nascomic.reader.library.mode.BookModePlanner.OldBook
import com.nascomic.reader.library.mode.BookModePlanner.OldBookmark
import com.nascomic.reader.library.mode.BookModePlanner.OldChapter
import com.nascomic.reader.library.mode.BookModePlanner.OldProgress
import com.nascomic.reader.library.mode.BookModePlanner.SeriesMove
import com.nascomic.reader.library.mode.BookModePlanner.TargetBook
import com.nascomic.reader.library.mode.BookModePlanner.TargetChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModePlannerTest {

    private fun chapter(ref: String, pages: Int, title: String = ref.substringAfterLast('/')) =
        OldChapter(ref, title, pages)

    private fun targetChapter(ref: String) = TargetChapter(ref, ref.substringAfterLast('/'), 0)

    @Test
    fun `拆分：一章一本，进度书签系列封面各归其位`() {
        val old = OldBook(
            id = 1,
            docRef = "A",
            title = "作品A",
            alias = "改过的书名",
            author = "某作者",
            volume = null,
            addedAt = 1000,
            pageTotal = 35,
            coverPage = 15, // c1 的 10 页之后，第 5 页 → c2 的第 5 页
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 20), chapter("A/c3", 5)),
            seriesId = 7,
            progress = OldProgress(chapterOrdinal = 1, pageInChapter = 3, pageGlobal = 13, finished = false, updatedAt = 500),
            bookmarks = listOf(
                OldBookmark(0, 1, 100),
                OldBookmark(1, 3, 200),
                OldBookmark(2, 0, 300),
            ),
        )
        val targets = listOf("A/c1", "A/c2", "A/c3").map { TargetBook(it, it.substringAfterLast('/'), 1, emptyList()) }

        val plan = BookModePlanner.plan(listOf(old), targets)

        assertTrue(plan.canExecute)
        assertEquals(listOf(1L), plan.removed)
        assertEquals(emptyList<Long>(), plan.kept)
        assertEquals(3, plan.created.size)

        // 进度只落在读到的那一章
        assertEquals(
            listOf(BookModePlanner.ProgressMove("A/c2", 0, 3, 3, false, 500)),
            plan.progress,
        )
        // 书签按页分到三本
        assertEquals(
            setOf(
                BookmarkMove("A/c1", 0, 1, 100),
                BookmarkMove("A/c2", 0, 3, 200),
                BookmarkMove("A/c3", 0, 0, 300),
            ),
            plan.bookmarks.toSet(),
        )
        // 系列跟主本（读到的那一章）
        assertEquals(listOf(SeriesMove("A/c2", 7)), plan.series)
        // 自选封面跟原页面走
        assertEquals(listOf(CoverMove("A/c2", 5)), plan.covers)
        // 书名与卷号只跟主本，作者三本都带
        assertEquals("改过的书名", plan.carry.getValue("A/c2").alias)
        assertEquals(null, plan.carry.getValue("A/c1").alias)
        assertEquals("某作者", plan.carry.getValue("A/c1").author)
    }

    @Test
    fun `合并：取最近阅读，书签去重，系列与封面冲突给默认`() {
        val v1 = OldBook(
            id = 2, docRef = "A/c1", title = "c1", alias = null, author = "作者A", volume = "1",
            addedAt = 1000, pageTotal = 10, coverPage = 0, chapters = emptyList(),
            seriesId = 8, progress = null, bookmarks = listOf(OldBookmark(0, 2, 100)),
        )
        val v2 = OldBook(
            id = 3, docRef = "A/c2", title = "c2", alias = null, author = "作者A", volume = "2",
            addedAt = 1100, pageTotal = 20, coverPage = 0, chapters = emptyList(),
            seriesId = 8,
            progress = OldProgress(0, 4, 4, false, 900),
            bookmarks = listOf(OldBookmark(0, 2, 200)),
        )
        val v3 = OldBook(
            id = 4, docRef = "A/c3", title = "c3", alias = "改过的第三话", author = "作者A", volume = "3",
            addedAt = 1200, pageTotal = 5, coverPage = 2, chapters = emptyList(),
            seriesId = 9,
            progress = OldProgress(0, 1, 1, false, 300),
            bookmarks = emptyList(),
        )
        val merged = TargetBook(
            "A", "作品A", 1,
            listOf(targetChapter("A/c1"), targetChapter("A/c2"), targetChapter("A/c3")),
        )

        val plan = BookModePlanner.plan(listOf(v1, v2, v3), listOf(merged))

        assertTrue(plan.canExecute)
        assertEquals(setOf(2L, 3L, 4L), plan.removed.toSet())
        assertEquals(1, plan.created.size)
        // 取 updated_at 最近的 v2：章 1，页 4，全局 10 + 4
        assertEquals(listOf(BookModePlanner.ProgressMove("A", 1, 4, 14, false, 900)), plan.progress)
        // 各话第 2 页并成同一本书的不同章，两条都留着
        assertEquals(
            setOf(BookmarkMove("A", 0, 2, 100), BookmarkMove("A", 1, 2, 200)),
            plan.bookmarks.toSet(),
        )
        // 两个系列冲突：默认跟读到的那本（系列 8）
        assertEquals(listOf(SeriesMove("A", 8)), plan.series)
        assertEquals(1, plan.conflicts.count { it.kind == BookModePlanner.ConflictKind.SERIES })
        // 唯一一张自选封面：c3 第 2 页 → 10 + 20 + 2
        assertEquals(listOf(CoverMove("A", 32)), plan.covers)
        assertEquals(0, plan.conflicts.count { it.kind == BookModePlanner.ConflictKind.COVER })
        // 改过的章名留在目录里
        assertEquals("改过的第三话", plan.chapterTitles["A/c3"])
        // 主本是 v2（读得最近），别名与卷号跟它走
        assertEquals("2", plan.carry.getValue("A").volume)
    }

    @Test
    fun `前面有章没量过页数：先报缺页，补齐后再算全局页号`() {
        val v1 = OldBook(
            id = 2, docRef = "A/c1", title = "c1", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 0, coverPage = 0, chapters = emptyList(),
            seriesId = null, progress = null, bookmarks = emptyList(),
        )
        val v2 = OldBook(
            id = 3, docRef = "A/c2", title = "c2", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 20, coverPage = 0, chapters = emptyList(),
            seriesId = null,
            progress = OldProgress(0, 4, 4, false, 900),
            bookmarks = emptyList(),
        )
        val merged = TargetBook(
            "A", "作品A", 1,
            listOf(targetChapter("A/c1"), targetChapter("A/c2")),
        )

        val first = BookModePlanner.plan(listOf(v1, v2), listOf(merged))
        assertFalse(first.canExecute)
        assertEquals(listOf("A/c1"), first.missingPages)
        assertTrue(first.progress.isEmpty())

        val second = BookModePlanner.plan(
            listOf(v1, v2),
            listOf(merged),
            pageTotals = mapOf("A/c1" to 12),
        )
        assertTrue(second.canExecute)
        assertEquals(listOf(BookModePlanner.ProgressMove("A", 1, 4, 16, false, 900)), second.progress)
    }

    @Test
    fun `章号越界：整组报错，不猜落点`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 30, coverPage = 0,
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 20)),
            seriesId = null,
            progress = OldProgress(chapterOrdinal = 5, pageInChapter = 1, pageGlobal = 1, finished = false, updatedAt = 1),
            bookmarks = emptyList(),
        )
        val plan = BookModePlanner.plan(
            listOf(old),
            listOf(TargetBook("A/c1", "c1", 1, emptyList()), TargetBook("A/c2", "c2", 1, emptyList())),
        )

        assertFalse(plan.canExecute)
        assertTrue(plan.problems.any { it.contains("章节记录对不上") })
    }

    @Test
    fun `自选封面超出整本页数：报定位失败`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 30, coverPage = 99,
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 20)),
            seriesId = null, progress = null, bookmarks = emptyList(),
        )
        val plan = BookModePlanner.plan(
            listOf(old),
            listOf(TargetBook("A/c1", "c1", 1, emptyList()), TargetBook("A/c2", "c2", 1, emptyList())),
        )

        assertFalse(plan.canExecute)
        // 走完整本还没落地 = 记的页号比实际页数大，跟「章页数没量过」分开报
        assertTrue(plan.problems.any { it.contains("自选封面超出了这本书的页数") })
    }

    @Test
    fun `同一格上有两条书签：留最早那条，但备注不能丢`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 20, coverPage = 0,
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 10)),
            seriesId = null, progress = null,
            bookmarks = listOf(
                OldBookmark(0, 2, 100, note = null),
                OldBookmark(0, 2, 300, note = "这里有个伏笔"),
            ),
        )
        val plan = BookModePlanner.plan(
            listOf(old),
            listOf(TargetBook("A", "A", 1, listOf(targetChapter("A/c1"), targetChapter("A/c2")))),
        )

        assertTrue(plan.canExecute)
        assertEquals(listOf(BookmarkMove("A", 0, 2, 100, "这里有个伏笔")), plan.bookmarks)
    }

    @Test
    fun `章页数比库里记的少：进度越界，整组不动`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 30, coverPage = 0,
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 20)),
            seriesId = null,
            // 库里记着 c1 有 10 页，读到第 10 页
            progress = OldProgress(0, 9, 9, false, 1),
            bookmarks = emptyList(),
        )
        val targets = listOf(TargetBook("A/c1", "c1", 1, emptyList()), TargetBook("A/c2", "c2", 1, emptyList()))

        // 量出来 c1 只剩 9 页：第 10 页不存在了
        val plan = BookModePlanner.plan(listOf(old), targets, pageTotals = mapOf("A/c1" to 9))

        assertFalse(plan.canExecute)
        assertTrue(plan.problems.any { it.contains("超出了这一章的页数") })
        assertTrue(plan.progress.isEmpty())
    }

    @Test
    fun `进度落在页数还没量的章上：先报要量的引用`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 0, coverPage = 0, chapters = emptyList(),
            seriesId = null,
            progress = OldProgress(0, 3, 3, false, 1),
            bookmarks = emptyList(),
        )
        val plan = BookModePlanner.plan(listOf(old), listOf(TargetBook("A", "A", 1, emptyList())))

        // 整本一章时也要核对这本书的页数，不能只信库里的 page_total
        assertEquals(listOf("A"), plan.pageChecks)
    }

    @Test
    fun `系列位置跟着原成员走，不一律排到队尾`() {
        val first = OldBook(
            id = 1, docRef = "A/c1", title = "c1", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 10, coverPage = 0, chapters = emptyList(),
            seriesId = 8, seriesOrdinal = 4, progress = null, bookmarks = emptyList(),
        )
        val second = OldBook(
            id = 2, docRef = "A/c2", title = "c2", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 10, coverPage = 0, chapters = emptyList(),
            seriesId = null, progress = null, bookmarks = emptyList(),
        )
        val merged = TargetBook("A", "A", 1, listOf(targetChapter("A/c1"), targetChapter("A/c2")))

        val plan = BookModePlanner.plan(listOf(first, second), listOf(merged))

        assertEquals(listOf(SeriesMove("A", 8, 4)), plan.series)
    }

    @Test
    fun `同一本里章序变了：标为更新，进度跟到新章号`() {
        val old = OldBook(
            id = 1, docRef = "A", title = "A", alias = null, author = null, volume = null,
            addedAt = 0, pageTotal = 30, coverPage = 0,
            chapters = listOf(chapter("A/c1", 10), chapter("A/c2", 20)),
            seriesId = null,
            progress = OldProgress(chapterOrdinal = 1, pageInChapter = 2, pageGlobal = 12, finished = false, updatedAt = 5),
            bookmarks = listOf(OldBookmark(1, 2, 50)),
        )
        val target = TargetBook(
            "A", "A", 1,
            listOf(targetChapter("A/c2"), targetChapter("A/c1")),
        )

        val plan = BookModePlanner.plan(listOf(old), listOf(target))

        assertTrue(plan.canExecute)
        assertEquals(listOf(1L), plan.updated)
        assertEquals(listOf(BookModePlanner.ProgressMove("A", 0, 2, 2, false, 5)), plan.progress)
        assertEquals(listOf(BookmarkMove("A", 0, 2, 50)), plan.bookmarks)
    }
    @Test
    fun refusesToDropBookWithoutAMatchingTarget() {
        val old = OldBook(
            id = 8, docRef = "Bundle/Deep/A", title = "A", alias = null, author = null, volume = null,
            addedAt = 1, pageTotal = 2, coverPage = 0, chapters = emptyList(),
            seriesId = 2, progress = null, bookmarks = emptyList(),
        )
        val target = TargetBook("Bundle", "Bundle", 1, listOf(TargetChapter("Bundle/Deep", "Deep", 0)))
        assertFalse(BookModePlanner.plan(listOf(old), listOf(target)).canExecute)
    }

}
