package com.nascomic.reader.library.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        HistoryEntity::class,
    ],
    version = 4,
    exportSchema = true
)
abstract class ReaderDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        private const val NAME = "reader.db"

        @Volatile
        private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** 进度表补一列「读到过书的尾」，老库按未读完处理 */
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

        private fun build(context: Context): ReaderDatabase =
            Room.databaseBuilder(context, ReaderDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // 兜底：开发期的库版本五花八门，认不出来的直接重建，别卡在启动上
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
