package com.nascomic.reader.ui.reader

/**
 * 页缓存的内容版本。
 *
 * 缓存键里除了页号、尺寸、裁边参数，还要有一个「这份内容是什么版本」的值。只看字节数是
 * 不够的：远端目录书的字节数是 0，往第一章前面插一章之后这个值一点没变，
 * 同一个全局页号就会读到上一章的位图。
 */
object ContentStamp {

    /**
     * @param byteSize 单文件书的字节数；目录书通常是 0
     * @param chapters 每章一个（章名, 页数），按阅读顺序
     * @param totalPages 全书页数
     * @param contentVersion 目录书重扫时 +1 的版本号；单文件书恒为 0，不影响结果
     */
    fun of(
        byteSize: Long,
        chapters: List<Pair<String, Int>>,
        totalPages: Int,
        contentVersion: Long = 0,
    ): Long {
        var h = byteSize
        for ((title, pages) in chapters) {
            h = h * 31 + title.hashCode()
            h = h * 31 + pages
        }
        h = h * 31 + totalPages
        return h * 31 + contentVersion
    }
}
