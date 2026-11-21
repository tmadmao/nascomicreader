package com.nascomic.reader.library.series

/**
 * 从书名里拆出作者、作品名、卷号。
 *
 * 只认文件名里写着的东西：不联网、不留汉化组。包内的 `ComicInfo.xml` 不走这里——
 * 那条路在 [com.nascomic.reader.format.comic.ComicInfoReader]。
 * 拆不出来就留空，不猜——猜错会把不相干的书并到一起，比不并更糟。
 */
object VolumeNameParser {

    data class VolumeName(
        /** 方括号里的作者；没写就是 null */
        val author: String?,
        /** 去掉标签与卷号后的作品名 */
        val title: String,
        /** 卷号；没有就是 null（外传、单本这类） */
        val volume: Int?,
        /** 用于比较的归一化名 */
        val normalized: String,
    )

    private val TAG = Regex("""[\[【]([^\]】]{1,30})[\]】]""")
    private val VOLUME = Regex(
        """(?:第\s*([0-9]{1,4}|[〇零一二三四五六七八九十百]{1,5})\s*[卷話话集册])|(?:vol\.?\s*([0-9]{1,4}))""",
        RegexOption.IGNORE_CASE,
    )
    /** 方括号里是这些词的多半是汉化组 / 扫图组，不是作者 */
    private val GROUP_TAG = Regex("""汉化|漢化|字幕|翻譯|翻译|组|組|社|扫描|掃描|制作|製作|压制|壓制""")
    /** 只削真扩展名：先前用 `\.[A-Za-z0-9]{1,5}$` 会把 `vol.2` 里的 `.2` 当扩展名削掉 */
    private val EXTENSION = Regex(
        """\.(?:jpe?g|png|webp|gif|bmp|zip|cbz|rar|cbr|7z|tar|pdf|epub|mobi|azw3?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): VolumeName {
        val tags = TAG.findAll(raw).map { it.groupValues[1] }.toList()
        val author = tags.firstOrNull { !GROUP_TAG.containsMatchIn(it) }

        var rest = TAG.replace(raw, " ")
        rest = EXTENSION.replace(rest, "")
        val hit = VOLUME.find(rest)
        val volume = hit?.let { m ->
            val arabic = m.groupValues[1]
            val latin = m.groupValues[2]
            when {
                arabic.isNotEmpty() -> arabic.toIntOrNull() ?: chineseNumber(arabic)
                latin.isNotEmpty() -> latin.toIntOrNull()
                else -> null
            }
        }
        if (hit != null) rest = rest.removeRange(hit.range)

        val title = rest.replace(Regex("""[\s_\-~·]+"""), " ").trim().trim('-', '_', ' ')
        return VolumeName(author, title, volume, normalize(title))
    }

    /**
     * 归一化顺序：繁转简 → 小写 → 去空白与 . _ - ~ 与括号 → 去掉末尾 1–3 位数字。
     * 末尾四位数当年份保留（有些作品就叫《1984》）。
     */
    fun normalize(title: String): String {
        var s = toSimplified(title.lowercase())
        s = s.replace(Regex("""[\s._\-~·（）()\[\]【】《》]"""), "")
        s = s.replace(Regex("""(?<![0-9])[0-9]{1,3}$"""), "")
        return s.trim()
    }

    /** 只收录高频差异字，够覆盖常见繁简写法；不做全表替换 */
    private val TRAD_TO_SIMPLE = mapOf(
        '籃' to '篮', '龍' to '龙', '鬥' to '斗', '學' to '学', '園' to '园', '兒' to '儿',
        '畫' to '画', '電' to '电', '車' to '车', '島' to '岛', '夢' to '梦', '國' to '国',
        '貓' to '猫', '劍' to '剑', '戰' to '战', '記' to '记', '術' to '术', '時' to '时',
        '間' to '间', '會' to '会', '對' to '对', '無' to '无', '與' to '与', '邊' to '边',
        '這' to '这', '個' to '个', '們' to '们', '說' to '说', '話' to '话', '讀' to '读',
        '頭' to '头', '點' to '点', '聲' to '声', '樂' to '乐', '藥' to '药', '愛' to '爱',
        '戀' to '恋', '殺' to '杀', '屍' to '尸', '靈' to '灵', '異' to '异', '聞' to '闻',
        '錄' to '录', '傳' to '传', '傑' to '杰', '鳥' to '鸟', '捲' to '卷', '艦' to '舰',
        '選' to '选', '歸' to '归', '俠' to '侠', '獵' to '猎', '獸' to '兽', '術' to '术',
    )

    private fun toSimplified(text: String): String = buildString(text.length) {
        for (ch in text) append(TRAD_TO_SIMPLE[ch] ?: ch)
    }

    /** 中文数字转整数；转换不出来给 null，不硬凑 */
    private fun chineseNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        val digits = mapOf(
            '〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var total = 0
        var current = 0
        for (ch in raw) {
            when {
                ch in digits -> current = digits.getValue(ch)
                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                }
                ch == '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                }
                else -> return null
            }
        }
        return total + current
    }
}
