package com.nascomic.reader.ui.reader

import com.nascomic.reader.library.data.PageEditEntity

/** 有效手动修正。没有内容证据的页仍走自动布局；一组手动配对必须核过两页。 */
internal class PageEdits private constructor(private val edits: Map<Int, PageEditEntity>) {
    private val partners = buildMap {
        for (edit in edits.values) {
            val other = partner(edit.page, edit.pairing) ?: continue
            if (edit.page !in this && other !in this && edits[other]?.pairing != SOLO) {
                put(edit.page, other)
                put(other, edit.page)
            }
        }
    }

    operator fun get(page: Int): PageEditEntity? = edits[page]
    fun pairedWith(page: Int): Int? = partners[page]
    fun pairingAt(page: Int): Int = when {
        partners[page] == page - 1 -> PREVIOUS
        partners[page] == page + 1 -> NEXT
        edits[page]?.pairing == SOLO -> SOLO
        else -> AUTO
    }

    companion object {
        const val AUTO = 0
        const val NORMAL = 1
        const val SPREAD = 2
        const val SOLO = 1
        const val PREVIOUS = 2
        const val NEXT = 3

        val Empty = PageEdits(emptyMap())

        fun partner(page: Int, pairing: Int): Int? = when (pairing) {
            PREVIOUS -> page - 1
            NEXT -> page + 1
            else -> null
        }

        fun verify(rows: List<PageEditEntity>, total: Int, chapter: (Int) -> Int, proof: (Int) -> String?): PageEdits =
            PageEdits(rows.mapNotNull { row ->
                if (row.page !in 0 until total || proof(row.page) != row.proof) return@mapNotNull null
                val other = partner(row.page, row.pairing)
                val validPair = other == null || (other in 0 until total && chapter(row.page) == chapter(other) &&
                    row.partnerProof != null && proof(other) == row.partnerProof)
                val edit = if (validPair) row else row.copy(pairing = AUTO, partnerProof = null)
                if (edit.isEmpty()) null else edit.page to edit
            }.toMap())

        fun PageEditEntity.isEmpty(): Boolean = kind == AUTO && rotation == 0 && pairing == AUTO

        /** 编辑一页或指定其新邻页时，解绑冲突的旧配对，保留邻页自己的类型和旋转。 */
        fun unlink(rows: List<PageEditEntity>, page: Int, partner: Int?): List<PageEditEntity> = rows.mapNotNull { row ->
            if (row.page == page) return@mapNotNull null
            val oldPartner = partner(row.page, row.pairing)
            if (row.page == partner || oldPartner == page || (partner != null && oldPartner == partner)) {
                row.copy(pairing = AUTO, partnerProof = null)
            } else null
        }
    }
}
