package com.nascomic.reader.library.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库。
 *
 * 迁移策略：正式发布后每次改表结构都要升 version 并写 Migration（schema 导出在 app/schemas/）；
 * 当前尚未发布，改动直接改 version=1 的定义即可。
 */
@Database(
    entities = [
        SourceEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class ReaderDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        private const val NAME = "reader.db"

        @Volatile
        private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): ReaderDatabase =
            Room.databaseBuilder(context, ReaderDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // 尚未发布：结构还在改，直接重建库。
                // 发布前必须删掉这一行并写正式迁移。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
