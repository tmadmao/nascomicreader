package com.nascomic.reader.format

/**
 * 已读百分比（0–100）。
 *
 * 读到过书的尾就按 100% 算：双页并排时最后一屏的第一页并不是最后一页，
 * 只按页号换算会停在 66% 这种数上，看着像没读完。
 */
fun readPercent(pageGlobal: Int, total: Int, finished: Boolean): Int {
    if (finished) return 100
    if (total <= 0) return 0
    return ((pageGlobal + 1) * 100 / total).coerceIn(0, 100)
}
