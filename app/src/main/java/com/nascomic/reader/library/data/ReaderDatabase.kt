package com.nascomic.reader.library.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 库版本。实体声明、导出的 schema、迁移目标都取这一处，
 * 改表只动这里一个数。
 */
internal const val DB_VERSION = 8

/**
 * 应用数据库。
 *
 * 迁移策略：每次改表结构都升 version 并写 Migration（schema 导出在 app/schemas/）。
 * 数据库已在测试者手上，跨版本要保住书架与进度，不能靠重建。
 */
@Database(
    entities = [
        SourceEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        SeriesEntity::class,
        SeriesBookEntity::class,
    ],
    version = DB_VERSION,
    exportSchema = true
)
abstract class ReaderDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun seriesDao(): SeriesDao

    companion object {
        private const val NAME = "reader.db"

        @Volatile
        private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** 进度表补一列「读到过书的尾」，该列缺省为未读完 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 加阅读历史表。删书时记录跟着删，索引与实体声明一致 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        book_id INTEGER NOT NULL,
                        opened_at INTEGER NOT NULL,
                        FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_book_id ON history(book_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_opened_at ON history(opened_at)")
            }
        }

        /** 阅读历史页已并进书架的「最近阅读」，这张表不再有人读，直接删掉 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS history")
            }
        }

        /**
         * 作品系列：书补两列从文件名解析出的作者与卷号，新增系列、归属、人工决定三张表。
         * 老库升级后原有书 / 进度 / 书签原样保留，新列留空。
         */
        /** 6→7：来源加「书型」一列。老库一律按连载处理，与升级前的行为一致 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sources ADD COLUMN book_mode INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 7→8：书加「用户别名」一列。
         *
         * 改名以前直接写 `title`，重扫一比对就当成来源变了，又拿文件名覆盖回去。
         * 别名单独一列，扫描只认 `title`，改名就再也冲不掉了。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN alias TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN volume TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `name_sort` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_name_sort` ON `series` (`name_sort`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series_books` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `series_id` INTEGER NOT NULL,
                        `book_id` INTEGER NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        FOREIGN KEY(`series_id`) REFERENCES `series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_books_series_id` ON `series_books` (`series_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_series_books_book_id` ON `series_books` (`book_id`)")

            }
        }

        /**
         * 迁移链，按版本顺序排列。建库与设备测试共用这一份：
         * 测试按它逐级套用，避免测试里另抄一份而两处走散。
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_6_7,
            MIGRATION_5_6,
            MIGRATION_7_8,
        )

        private fun build(context: Context): ReaderDatabase =
            Room.databaseBuilder(context, ReaderDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                // 兜底：开发期的库版本五花八门，认不出来的直接重建，别卡在启动上
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
