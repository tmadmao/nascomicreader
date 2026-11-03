package com.nascomic.reader.library.scan

/**
 * 章的先后变了以后，把「读到第几章的第几页」搬到新序上。
 *
 * 进度与书签记的都是章号。重扫按名称重新定章序，旧章号就失效了——不搬的话，
 * 读到第 16 话的书重新扫一遍会变成从第 5 话接着看。
 *
 * 章的内容没变，所以按章的来源引用（目录或压缩包）能一一对上；页数又是按章记的，
 * 与章的先后无关，于是同一个位置在新序里的全局页号也能算准。
 */
object ChapterReorder {

    /** 旧章表里的一行：来源引用 + 量过的页数 */
    data class ChapterSlot(val ref: String, val pages: Int)

    /** 旧章号在新序里的位置；对不上（这一章已经不在了）返回 null */
    fun newOrdinal(old: List<ChapterSlot>, newRefs: List<String>, oldOrdinal: Int): Int? {
        val ref = old.getOrNull(oldOrdinal)?.ref ?: return null
        val at = newRefs.indexOf(ref)
        return if (at >= 0) at else null
    }

    /**
     * 新序下同一个位置对应的全局页号。
     *
     * 只要有一章没量过页数（0），前面几章合起来多少页就无从得知，这时返回 null，
     * 由调用方保留原来的全局页号——宁可页号偏一点，也不要凭空算出个更错的位置。
     */
    fun newPageGlobal(
        old: List<ChapterSlot>,
        newRefs: List<String>,
        newOrdinal: Int,
        pageInChapter: Int,
    ): Int? {
        val pages = newRefs.map { ref -> old.firstOrNull { it.ref == ref }?.pages ?: 0 }
        if (pages.any { it <= 0 }) return null
        return pages.take(newOrdinal).sum() + pageInChapter
    }
}
