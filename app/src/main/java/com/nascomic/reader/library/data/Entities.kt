package com.nascomic.reader.library.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书库来源。本地目录（SAF 授权）或远程地址。
 * 列名与语义见《数据模型与命名规范》§2.1。
 */
@Entity(
    tableName = "sources",
    indices = [
        Index("kind"),
        Index(value = ["kind", "tree_uri"], unique = true),
    ]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** 0=本地目录 1=WebDAV */
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "label") val label: String,
    /** SAF 树 URI，仅本地来源 */
    @ColumnInfo(name = "tree_uri") val treeUri: String? = null,
    /** 远程根地址 */
    @ColumnInfo(name = "endpoint") val endpoint: String? = null,
    @ColumnInfo(name = "account") val account: String? = null,
    /** 指向加密存储的键名，密码本身不落库 */
    @ColumnInfo(name = "secret_ref") val secretRef: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long? = null,
    /** 0=未扫描 1=扫描中 2=完成 3=失败 */
    @ColumnInfo(name = "scan_state") val scanState: Int = STATE_IDLE,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
) {
    companion object {
        const val KIND_LOCAL = 0
        const val KIND_WEBDAV = 1

        const val STATE_IDLE = 0
        const val STATE_SCANNING = 1
        const val STATE_DONE = 2
        const val STATE_FAILED = 3

        const val ERROR_TREE_PERMISSION_LOST = 10
        const val ERROR_UNREACHABLE = 11
        const val ERROR_AUTH = 12
    }
}

/**
 * 一本书。一个压缩包、一个目录、一个 pdf、一个 epub 各算一本。
 * 列名见《数据模型与命名规范》§2.2。
 */
@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("source_id"),
        Index("added_at"),
        Index("title_sort"),
        Index(value = ["source_id", "doc_ref"], unique = true),
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    /** 来源内的相对引用：zip 路径 / 目录路径 / 文件名 */
    @ColumnInfo(name = "doc_ref") val docRef: String,
    @ColumnInfo(name = "title") val title: String,
    /** 自然序排序键，由 NaturalSort.key 生成 */
    @ColumnInfo(name = "title_sort") val titleSort: String,
    /** 0=zip/cbz 1=目录 2=pdf 3=epub 4=其他压缩包 */
    @ColumnInfo(name = "format") val format: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long = 0,
    @ColumnInfo(name = "page_total") val pageTotal: Int = 0,
    @ColumnInfo(name = "chapter_total") val chapterTotal: Int = 0,
    /** 封面所在全局页号（0 基），默认 0 */
    @ColumnInfo(name = "cover_page") val coverPage: Int = 0,
    @ColumnInfo(name = "cover_ref") val coverRef: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = 0,
    /** 1 = 没有子目录，整本一章 */
    @ColumnInfo(name = "is_single_volume") val isSingleVolume: Int = 1,
    /** 0=待解析 1=可读 2=损坏 */
    @ColumnInfo(name = "scan_state") val scanState: Int = STATE_PENDING,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
) {
    companion object {
        const val FORMAT_ZIP = 0
        const val FORMAT_FOLDER = 1
        const val FORMAT_PDF = 2
        const val FORMAT_EPUB = 3
        const val FORMAT_ARCHIVE = 4

        const val STATE_PENDING = 0
        const val STATE_READABLE = 1
        const val STATE_BROKEN = 2

        const val ERROR_TRUNCATED = 1
        const val ERROR_NO_IMAGES = 2
        const val ERROR_ENCRYPTED = 3
        const val ERROR_DECODE = 4
        const val ERROR_BAD_EPUB = 5
    }
}

/** 章。容器内的子目录即一章；没有子目录时整本只有一章。见 §2.3 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["book_id", "ordinal"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** 0 基 */
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "title") val title: String,
    /** 本章页在容器内的路径前缀，空串表示根 */
    /** 目录章填完整路径前缀；压缩包章留空 */
    @ColumnInfo(name = "entry_prefix") val entryPrefix: String = "",
    /** 章的来源：该章对应目录或压缩包的 docId */
    @ColumnInfo(name = "chapter_ref") val chapterRef: String = "",
    @ColumnInfo(name = "chapter_kind") val chapterKind: Int = KIND_FOLDER,
    @ColumnInfo(name = "page_total") val pageTotal: Int = 0,
) {
    companion object {
        const val KIND_FOLDER = 0
        const val KIND_ARCHIVE = 1
    }
}

/** 阅读进度，一本书一行。页码 0 基存库，界面显示时 +1。见 §2.4 */
@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("updated_at")]
)
data class ProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_ordinal") val chapterOrdinal: Int = 0,
    @ColumnInfo(name = "page_in_chapter") val pageInChapter: Int = 0,
    @ColumnInfo(name = "page_global") val pageGlobal: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 书签。同页再点 = 删除该行。见 §2.5 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["book_id", "chapter_ordinal", "page_in_chapter"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_ordinal") val chapterOrdinal: Int,
    @ColumnInfo(name = "page_in_chapter") val pageInChapter: Int,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
