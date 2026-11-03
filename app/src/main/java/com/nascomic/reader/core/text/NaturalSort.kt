package com.nascomic.reader.core.text

/**
 * 文件名的自然序排序键。
 *
 * 目的：让「第2话」排在「第10话」前面，让 `p1 < p07 < p10` 成立。
 * 做法：把名字切成「数字段 / 非数字段」，数字段按数值比、其余按码位比，
 * 再拼成一个可以直接字符串比较的键。
 *
 * 「数字段」不只有阿拉伯数字：汉化组与字幕组的命名里，`第一话`、`第三季`、
 * `其ノ二`、`Vol.IV` 都当序号用，所以中文数字与罗马数字也要换算出数值再比，
 * 否则「第十话」会排在「第三话」前面。
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
        val s = normalize(raw)
        val out = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isAsciiDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isAsciiDigit()) j++
                    out.append(numeric(s.substring(i, j)))
                    i = j
                }
                // 中文数字与罗马数字要先看语境，不是数字就按普通字符走
                c in CN_DIGIT || c in CN_UNIT -> {
                    val cn = chineseNumber(s, i)
                    if (cn == null) {
                        out.append(c.lowercaseChar())
                        i++
                    } else {
                        out.append(numeric(cn.value.toString()))
                        i = cn.end
                    }
                }
                c in ROMAN_CHARS -> {
                    val roman = romanNumber(s, i)
                    if (roman == null) {
                        out.append(c.lowercaseChar())
                        i++
                    } else {
                        out.append(numeric(roman.value.toString()))
                        i = roman.end
                    }
                }
                else -> {
                    out.append(c.lowercaseChar())
                    i++
                }
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

    /** 一段数字在键里的写法：标记 + 去掉前导零后补零到固定宽度 */
    private fun numeric(digits: String): String {
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        return DIGIT_MARK + trimmed.padStart(NUM_WIDTH, '0')
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

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    // ---- 数字段的另外两种写法：中文数字、罗马数字 ----

    private class Number(val value: Int, val end: Int)

    private val CN_DIGIT = mapOf(
        '〇' to 0, '零' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val CN_UNIT = mapOf('十' to 10, '百' to 100, '千' to 1000)

    /** 中文数字出现在这些字之后（`第十二话`、`其ノ二`、`卷一`）才算序号 */
    private val CN_LEAD = setOf('第', '之', 'ノ', '卷', '巻', '册', '冊')

    /** 出现在这些字之前也算序号：`十二话`、`三季`、`二卷` */
    private val CN_FOLLOW = setOf(
        '话', '話', '卷', '巻', '章', '集', '季', '期', '册', '冊',
        '篇', '回', '幕', '页', '頁', '部', '号', '號', '本', '帖', '曲',
    )

    /**
     * 认一段中文数字：`一`、`十二`、`一百零八`。
     *
     * 只认挨着量词的那些：「一人之下」「三体」这种书名里的中文数字不动它，
     * 否则书架按名称排序时，这类书名会全跑到数字开头那一堆去。
     */
    private fun chineseNumber(s: String, start: Int): Number? {
        var end = start
        while (end < s.length && (s[end] in CN_DIGIT || s[end] in CN_UNIT)) end++
        if (end == start) return null
        if (s.getOrNull(start - 1) !in CN_LEAD && s.getOrNull(end) !in CN_FOLLOW) return null

        var hasUnit = false
        var unitSum = 0
        var num = 0
        for (k in start until end) {
            val ch = s[k]
            val digit = CN_DIGIT[ch]
            if (digit != null) {
                num = digit
                continue
            }
            hasUnit = true
            unitSum += (if (num == 0) 1 else num) * CN_UNIT.getValue(ch)
            num = 0
        }
        if (!hasUnit) {
            // 没有十百千：逐位拼，`二〇二四` 是 2024 而不是 4
            var value = 0
            for (k in start until end) value = value * 10 + CN_DIGIT.getValue(s[k])
            return Number(value, end)
        }
        return Number(unitSum + num, end)
    }

    private const val ROMAN_CHARS = "IVXLCDM"

    private val ROMAN_VALUE = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000,
    )

    private val ROMAN_TABLE = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )

    /** 这些词后面跟罗马数字就是卷号 */
    private val VOLUME_WORDS = setOf("vol", "volume", "book", "chap", "chapter", "no", "number")

    /**
     * 认一段罗马数字：`Vol.IV`、`第IV卷`。
     *
     * 要求是一整个大写单词（`CLAMP`、`DIVE` 这种整词不全是罗马字符，不会被误认），
     * 并且只认规范写法：`IIII` 不算，`MIX` 这种虽然能拼出数、但没有量词语境也不动。
     */
    private fun romanNumber(s: String, start: Int): Number? {
        if (start > 0 && s[start - 1].isAsciiLetter()) return null
        var end = start
        while (end < s.length && s[end] in ROMAN_CHARS) end++
        if (end < s.length && s[end].isAsciiLetter()) return null

        val lead = s.getOrNull(start - 1) in CN_LEAD || wordBefore(s, start) in VOLUME_WORDS
        val text = s.substring(start, end)
        // 单字母 I / V / X 太容易是标题里的字母，只有紧跟在 vol / 卷 后面才算
        if (text.length < 2 && !lead) return null
        if (!lead && s.getOrNull(end) !in CN_FOLLOW) return null

        val value = romanValueOf(text) ?: return null
        if (value <= 0 || value > 3999) return null
        if (toRoman(value) != text) return null
        return Number(value, end)
    }

    private fun romanValueOf(text: String): Int? {
        var sum = 0
        var previous = 0
        for (k in text.indices.reversed()) {
            val value = ROMAN_VALUE[text[k]] ?: return null
            if (value < previous) {
                sum -= value
            } else {
                sum += value
                previous = value
            }
        }
        return sum
    }

    private fun toRoman(value: Int): String {
        var left = value
        val sb = StringBuilder()
        for ((unit, symbol) in ROMAN_TABLE) {
            while (left >= unit) {
                sb.append(symbol)
                left -= unit
            }
        }
        return sb.toString()
    }

    /** 往回找紧挨着的那个字母词（`Vol.IV` 里 IV 前面的 `vol`） */
    private fun wordBefore(s: String, index: Int): String {
        var end = index
        while (end > 0 && !s[end - 1].isAsciiLetterOrDigit()) end--
        var start = end
        while (start > 0 && s[start - 1].isAsciiLetter()) start--
        return s.substring(start, end).lowercase()
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || isAsciiDigit()
}
