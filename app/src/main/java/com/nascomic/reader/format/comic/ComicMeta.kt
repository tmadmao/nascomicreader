package com.nascomic.reader.format.comic

/**
 * 压缩包里附带的 `ComicInfo.xml`。
 *
 * 只收这本书能直接用的两项：作者与卷号。系列名、册号、阅读方向、页面类型这些字段
 * 暂时不读——库里没有对应的位置，读出来也只能丢掉。
 */
data class ComicMeta(
    /** `Writer`，没有就退回 `Penciller`；两者都没有就是 null */
    val writer: String?,
    /** 卷号：优先 `Number`（册号），它缺了才看 `Volume`；两者都没有就是 null */
    val volume: String?,
) {
    /** 一项都没读出来时，调用方可以整条忽略 */
    val isEmpty: Boolean get() = writer == null && volume == null
}
