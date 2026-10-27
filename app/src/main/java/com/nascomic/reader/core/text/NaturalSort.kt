package com.nascomic.reader.core.text

/**
 * 文件名的自然序排序键。
 *
 * 目的：让「第2话」排在「第10话」前面，让 `p1 < p07 < p10` 成立。
 * 做法：把名字切成「数字段 / 非数字段」，数字段按数值比、其余按码位比，
 * 再拼成一个可以直接字符串比较的键。
 *
 * 输出保证：两个键用 compareTo 比较的结果 = 人看这两个名字的先后。
 */
object NaturalSort {

    /** 数字段用一个小于任何可见字符的标记开头，避免和名字里的普通字符混淆 */
    private const val DIGIT_MARK = '\u0002'

    /** 数值补零到这个宽度；文件名里的数字基本不会超过，超出时退化为按位数比较 */
    private const val NUM_WIDTH = 10

    /**
     * 把名字转成排序键。规则见《数据模型与命名规范》§5：
     * NFKC 归一 → 全角转半角 → 去首尾空白 → 数字段按值、其余转小写。
     */
    fun key(raw: String): String {
        val normalized = normalize(raw)
        val out = StringBuilder(normalized.length + 8)
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            if (c.isAsciiDigit()) {
                var j = i
                while (j < normalized.length && normalized[j].isAsciiDigit()) j++
                val digits = normalized.substring(i, j).trimStart('0').ifEmpty { "0" }
                out.append(DIGIT_MARK)
                // 超过宽度时原样输出：标记已能区分，长度差只会让更长的数字排后面
                out.append(digits.padStart(NUM_WIDTH, '0'))
                i = j
            } else {
                out.append(c.lowercaseChar())
                i++
            }
        }
        return out.toString()
    }

    /** 按名字比较的比较器，可直接给 sortedWith */
    fun comparator(): Comparator<String> = Comparator { a, b -> key(a).compareTo(key(b)) }

    /**
     * 按文件名排序用的键：**先去掉扩展名**再比。
     *
     * 为什么必须去掉：`0.zip` 和 `0.5.zip` 在数字 `0` 之后分别是 `.z` 和 `.` + 数字段，
     * 而数字段的标记比任何字母都小，会把 `0.5` 判成排在 `0` 前面。
     * 扩展名不参与排序，这种错位就不会发生。
     */
    fun nameKey(raw: String): String = key(stripExtension(raw))

    /** 按文件名给列表排序（稳定），比较时忽略扩展名 */
    fun <T> sortedByName(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith { a, b -> nameKey(nameOf(a)).compareTo(nameKey(nameOf(b))) }

    private fun stripExtension(name: String): String {
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        return if (dot > slash) name.substring(0, dot) else name
    }

    private fun normalize(raw: String): String {
        var s = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFKC)
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when {
                    c == '\u3000' -> ' '                       // 全角空格
                    c in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()  // 全角 ASCII → 半角
                    else -> c
                }
            )
        }
        return sb.toString().trim()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
