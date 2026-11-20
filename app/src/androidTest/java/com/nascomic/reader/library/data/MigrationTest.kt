package com.nascomic.reader.library.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移测试：按旧版本建库、写进一本书和它的进度书签，再套上迁移，
 * 断言库能正常打开、数据一条不少。跑在设备上，用的是系统自带的 SQLite。
 *
 * 迁移方向只有升级：旧库遇上新代码要保住书架与进度，靠的就是这里逐级验过。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReaderDatabase::class.java,
    )

    /** 当前库版本，与实体声明同一个常量，改表不用回来改这里 */
    private val latestVersion: Int = DB_VERSION

    @Test
    fun `2到3补finished列_旧进度按未读完算`() {
        val name = "mig_2_3.db"
        createOld(name, version = 2).close()

        val db = helper.runMigrationsAndValidate(name, 3, true, *chainUpTo(3))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM books"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM bookmarks"))
        assertEquals(7L, db.count("SELECT page_global FROM progress WHERE book_id = 1"))
        assertEquals(0L, db.count("SELECT finished FROM progress WHERE book_id = 1"))
        db.close()
    }

    @Test
    fun `3到4建历史表_原有数据不动`() {
        val name = "mig_3_4.db"
        createOld(name, version = 3).close()

        val db = helper.runMigrationsAndValidate(name, 4, true, *chainUpTo(4))
        assertTrue(db.hasTable("history"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM books"))
        assertEquals(1L, db.count("SELECT finished FROM progress WHERE book_id = 1"))
        db.close()
    }

    @Test
    fun `4到5删历史表_书架与进度照旧`() {
        val name = "mig_4_5.db"
        createOld(name, version = 4).close()

        val db = helper.runMigrationsAndValidate(name, 5, true, *chainUpTo(5))
        assertFalse(db.hasTable("history"))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM bookmarks"))
        assertEquals(7L, db.count("SELECT page_global FROM progress WHERE book_id = 1"))
        db.close()
    }

    @Test
    fun `5到6加作者卷号与系列三表_原有数据不动`() {
        val name = "mig_5_6.db"
        createOld(name, version = 5).close()

        val db = helper.runMigrationsAndValidate(name, 6, true, *chainUpTo(6))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM bookmarks"))
        // 新列留空：作者卷号要靠解析文件名填，升级时不去猜
        assertNull(db.text("SELECT author FROM books WHERE id = 1"))
        assertNull(db.text("SELECT volume FROM books WHERE id = 1"))
        assertTrue(db.hasTable("series"))
        assertTrue(db.hasTable("series_books"))
        assertFalse(db.hasTable("group_decisions"))
        db.close()
    }

    @Test
    fun `6到7加书型列_老库按连载处理`() {
        val name = "mig_6_7.db"
        createOld(name, version = 6).close()

        val db = helper.runMigrationsAndValidate(name, 7, true, *chainUpTo(7))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM bookmarks"))
        // 升级前没有书型这个概念，老来源一律按连载：行为与升级前一致
        assertEquals(0L, db.count("SELECT COUNT(*) FROM sources WHERE book_mode <> 0"))
        db.close()
    }

    @Test
    fun `7到8加别名列_老库没有别名`() {
        val name = "mig_7_8.db"
        createOld(name, version = 7).close()

        val db = helper.runMigrationsAndValidate(name, 8, true, *chainUpTo(8))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM bookmarks"))
        assertEquals(7L, db.count("SELECT page_global FROM progress WHERE book_id = 1"))
        // 升级前没有别名：显示仍按来源标题，改名之后才写这一列
        assertNull(db.text("SELECT alias FROM books WHERE id = 1"))
        db.close()
    }

    @Test
    fun `8到9加内容版本列_老书默认为零`() {
        val name = "mig_8_9.db"
        createOld(name, version = 8).close()

        val db = helper.runMigrationsAndValidate(name, 9, true, *chainUpTo(9))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(0L, db.count("SELECT content_version FROM books WHERE id = 1"))
        db.close()
    }

    /** 跨过多个版本的库要能一路升上来，中间每一步都不能断 */
    @Test
    fun `从2一路升到当前版本_书与进度书签都在`() {
        val name = "mig_2_latest.db"
        createOld(name, version = 2).close()

        val db = helper.runMigrationsAndValidate(name, latestVersion, true, *ReaderDatabase.MIGRATIONS)
        assertEquals(1L, db.count("SELECT COUNT(*) FROM sources"))
        assertEquals("第一卷", db.text("SELECT title FROM books WHERE id = 1"))
        assertEquals(7L, db.count("SELECT page_global FROM progress WHERE book_id = 1"))
        assertEquals("看到这里", db.text("SELECT note FROM bookmarks WHERE id = 1"))
        assertEquals(0L, db.count("SELECT finished FROM progress WHERE book_id = 1"))
        assertNull(db.text("SELECT alias FROM books WHERE id = 1"))
        db.close()
    }

    /**
     * 按目标版本建库并写进一组数据。
     * 各版本的列有增有减，所以按版本分别给值，不共用一条 INSERT。
     */
    private fun createOld(name: String, version: Int): SupportSQLiteDatabase =
        helper.createDatabase(name, version).apply {
            execSQL(
                "INSERT INTO sources (id, kind, label, added_at, scan_state) " +
                    "VALUES (1, 0, '本地目录', 1000, 2)"
            )
            execSQL(
                "INSERT INTO books (id, source_id, doc_ref, title, title_sort, format, byte_size, " +
                    "page_total, chapter_total, cover_page, added_at, modified_at, is_single_volume, " +
                    "scan_state) VALUES (1, 1, '漫画/第一卷.zip', '第一卷', 'diyijuan', 0, 1024, " +
                    "20, 1, 0, 1000, 1000, 1, 1)"
            )
            execSQL(
                "INSERT INTO chapters (id, book_id, ordinal, title, entry_prefix, chapter_ref, " +
                    "chapter_kind, page_total) VALUES (1, 1, 0, '第 1 章', '', '', 0, 20)"
            )
            if (version >= 3) {
                execSQL(
                    "INSERT INTO progress (book_id, chapter_ordinal, page_in_chapter, page_global, " +
                        "finished, updated_at) VALUES (1, 0, 3, 7, 1, 2000)"
                )
            } else {
                execSQL(
                    "INSERT INTO progress (book_id, chapter_ordinal, page_in_chapter, page_global, " +
                        "updated_at) VALUES (1, 0, 3, 7, 2000)"
                )
            }
            execSQL(
                "INSERT INTO bookmarks (id, book_id, chapter_ordinal, page_in_chapter, note, created_at) " +
                    "VALUES (1, 1, 0, 3, '看到这里', 2000)"
            )
            if (version == 4) {
                execSQL("INSERT INTO history (book_id, opened_at) VALUES (1, 3000)")
            }
        }

    private fun chainUpTo(version: Int): Array<Migration> =
        ReaderDatabase.MIGRATIONS.filter { it.endVersion <= version }.toTypedArray()

    private fun SupportSQLiteDatabase.count(sql: String): Long =
        query(sql).use { if (it.moveToFirst()) it.getLong(0) else -1L }

    private fun SupportSQLiteDatabase.text(sql: String): String? =
        query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'") > 0
}
