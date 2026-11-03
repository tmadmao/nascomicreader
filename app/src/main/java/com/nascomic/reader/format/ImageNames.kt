package com.nascomic.reader.format

/** 按扩展名判断是不是能显示的图片。 */
object ImageNames {

    private val EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp",
        // 近年压的包里会混这些；系统解不了的会在解码那步落到「这一页读不出来」
        "avif", "heif", "heic", "jxl",
    )

    fun isImage(name: String): Boolean = extensionOf(name) in EXTENSIONS

    fun extensionOf(name: String): String {
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        if (dot <= slash || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 去掉扩展名后的显示名 */
    fun baseName(name: String): String {
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        val from = slash + 1
        return if (dot > from) name.substring(from, dot) else name.substring(from)
    }
}
