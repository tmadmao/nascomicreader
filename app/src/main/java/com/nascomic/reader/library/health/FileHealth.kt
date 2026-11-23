package com.nascomic.reader.library.health

/**
 * 一次检查里的一本问题书。
 *
 * 只带事实（哪本、哪一类、库里几页、现在几页），中文说法留给界面拼——
 * 报告本身不进库、不上传，检查完就丢。
 */
data class HealthIssue(
    val bookId: Long,
    val title: String,
    val kind: Kind,
    val storedPages: Int = 0,
    val actualPages: Int = 0,
    val cause: Cause = Cause.OTHER,
) {
    /** 三类问题：打不开、没有图片页、页数与库里对不上 */
    enum class Kind { UNREADABLE, NO_IMAGES, PAGE_MISMATCH }

    /** 打不开的成因，界面按它换成一句人话 */
    enum class Cause { CORRUPT, MISSING, PERMISSION, FORMAT, OTHER }
}

data class HealthReport(
    /** 这个来源一共有几本 */
    val total: Int,
    /** 实际试过几本（含中途读不到、判不了的那几本） */
    val checked: Int,
    /** 判不了几本：书源那边暂时读不到，不能算坏包 */
    val skipped: Int,
    val issues: List<HealthIssue>,
    /** 书源中途连不上，没查完就停了 */
    val stoppedEarly: Boolean = false,
)

/**
 * 判定只做三件事：空页表、页数不符、其余算能开。
 *
 * 库里没量过页数（0）不报差异——那是没量过，不是对不上；
 * 读出 0 页优先说「没有图片页」，比「页数对不上」更接近用户看到的。
 */
object FileHealthRules {

    fun judge(storedPages: Int, actualPages: Int): HealthIssue.Kind? = when {
        actualPages <= 0 -> HealthIssue.Kind.NO_IMAGES
        storedPages > 0 && storedPages != actualPages -> HealthIssue.Kind.PAGE_MISMATCH
        else -> null
    }

    /**
     * 成因按异常类型归，不读异常文案：库里的原文是英文（native / okhttp / smbj），
     * 直接摆到界面上没人看得懂；归不了类的就说「读取失败」。
     *
     * 异常归不出类时再看书的格式：压缩包几种解析器都读不动，就是包坏了；
     * EPUB 读不动多半是不规范，不当成「损坏」吓人。
     */
    fun causeOf(e: Throwable?, format: Int = -1): HealthIssue.Cause {
        when (e) {
            is com.nascomic.reader.format.zip.ZipFormatException -> return HealthIssue.Cause.CORRUPT
            is java.util.zip.ZipException -> return HealthIssue.Cause.CORRUPT
            is SecurityException -> return HealthIssue.Cause.PERMISSION
            is java.io.FileNotFoundException -> return HealthIssue.Cause.MISSING
            is UnsupportedOperationException -> return HealthIssue.Cause.FORMAT
        }
        return when (format) {
            com.nascomic.reader.library.data.BookEntity.FORMAT_ZIP,
            com.nascomic.reader.library.data.BookEntity.FORMAT_ARCHIVE ->
                HealthIssue.Cause.CORRUPT
            com.nascomic.reader.library.data.BookEntity.FORMAT_EPUB ->
                HealthIssue.Cause.FORMAT
            else -> HealthIssue.Cause.OTHER
        }
    }
}
