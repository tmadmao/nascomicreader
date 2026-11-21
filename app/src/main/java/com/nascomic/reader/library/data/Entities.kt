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
    /** 0=本地目录 1=WebDAV 2=SMB */
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
    /**
     * 这个书库的书型，只影响扫描时怎么切书：
     * 连载 = 扫描根的子项各自成书、子项内部当章；单行本 = 每个含图的目录各自成书。
     * 图集与连载经常同时存在，所以按来源区分，不给全局开关。
     */
    @ColumnInfo(name = "book_mode", defaultValue = "0") val bookMode: Int = MODE_SERIAL,
) {
    companion object {
        const val KIND_LOCAL = 0
        const val KIND_WEBDAV = 1
        const val KIND_SMB = 2

        /** 连载漫画：子目录 / 压缩包 / PDF 都当章 */
        const val MODE_SERIAL = 0

        /** 单行本 / 图集：每个含图的目录、每个压缩包或 PDF 各自成书 */
        const val MODE_SINGLE = 1

        const val STATE_IDLE = 0
        const val STATE_SCANNING = 1
        const val STATE_DONE = 2
        const val STATE_FAILED = 3

        const val ERROR_TREE_PERMISSION_LOST = 10
        const val ERROR_UNREACHABLE = 11
        const val ERROR_AUTH = 12

        /** 这次一本都没扫到，原有书库原样保留（授权或挂载多半出了问题） */
        const val ERROR_EMPTY_SCAN = 13

        /** 扫描完成，但有几个远端目录这次没读到：那一部分没更新、也没删 */
        const val ERROR_PARTIAL = 14

        /** 上次扫描没走完就退出了：状态停在「扫描中」，冷启动时改成这个，可重试 */
        const val ERROR_INTERRUPTED = 15
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
    /** 自然序排序键，由 NaturalSort.key 生成；用户改过名时是别名的键 */
    @ColumnInfo(name = "title_sort") val titleSort: String,
    /**
     * 用户改的书名。`title` 永远是来源上的名字，扫描拿它比对；
     * 别名只由用户写，重扫不会碰它——两者混在一列里，一次重扫就把改名冲掉了。
     */
    @ColumnInfo(name = "alias") val alias: String? = null,
    /** 从文件名解析出的作者；解析不出就空着，不猜 */
    @ColumnInfo(name = "author") val author: String? = null,
    /** 从文件名解析出的卷号；可以是数字，也可以是「上」「特别篇」这类字，解析不出就空着 */
    @ColumnInfo(name = "volume") val volume: String? = null,
    /** 0=zip/cbz 1=目录 2=pdf 3=epub 4=其他压缩包 */
    @ColumnInfo(name = "format") val format: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long = 0,
    @ColumnInfo(name = "page_total") val pageTotal: Int = 0,
    @ColumnInfo(name = "chapter_total") val chapterTotal: Int = 0,
    /** 封面所在全局页号（0 基），默认 0 */
    @ColumnInfo(name = "cover_page") val coverPage: Int = 0,
    /**
     * 目录书的内容版本。目录书字节数为 0，换图但页数不变时字节数也无从判断，
     * 重扫成功后把它 +1，页缓存键跟着变，下次打开重新解码，不拿旧图顶新图。
     */
    @ColumnInfo(name = "content_version") val contentVersion: Long = 0,
    @ColumnInfo(name = "cover_ref") val coverRef: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = 0,
    /** 1 = 没有子目录，整本一章 */
    @ColumnInfo(name = "is_single_volume") val isSingleVolume: Int = 1,
    /** 0=待解析 1=可读 2=损坏 */
    @ColumnInfo(name = "scan_state") val scanState: Int = STATE_PENDING,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
    /**
     * 包内元数据（`ComicInfo.xml`）读过没有：0 = 没读过，1 = 读过。
     *
     * 「读过」包含「包里没有这个文件」与「解析失败」——都算试过了，不必每次进详情再开一次书。
     * 老库默认 0，所以升级后第一次打开这本书会补读一次。
     */
    @ColumnInfo(name = "meta_version", defaultValue = "0") val metaVersion: Int = 0,
) {
    /** 界面上该显示的名字：用户改过就用他的，否则用来源标题 */
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() } ?: title

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
        const val KIND_PDF = 2
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
    /**
     * 是否读到过书的尾。双页并排时最后一屏的第一页不是最后一页，
     * 只看页号判断不出「看完了」，所以单独记一笔。
     */
    @ColumnInfo(name = "finished") val finished: Boolean = false,
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

/**
 * 作品系列：把散在不同目录 / 不同来源的同一部作品的各卷聚在一起。
 * 只建关联，不改来源文件，也不动 `books.doc_ref`。见《开发计划》§2.5。
 */
@Entity(
    tableName = "series",
    indices = [Index("name_sort")]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** 系列名，默认取作品名，允许改 */
    @ColumnInfo(name = "name") val name: String,
    /** 排序键，与 books.title_sort 同一套规则，列表可按它排 */
    @ColumnInfo(name = "name_sort") val nameSort: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 书与系列的归属。一本最多属于一个系列，靠 `book_id` 上的唯一索引兜住；
 * 删系列只删这里的行，书本身不动。
 */
@Entity(
    tableName = "series_books",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["series_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("series_id"),
        Index(value = ["book_id"], unique = true),
    ]
)
data class SeriesBookEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** 组内序，0 基；重排只改这一列 */
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

