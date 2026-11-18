package com.nascomic.reader.library.author

/**
 * 作者名 → 索引字母。
 *
 * 面向中文读者：**汉字一律按中文拼音**（日文汉字也不例外，手塚治虫 → S、龍幸伸 → L）——
 * 这是中文用户的念法，不是日语读音；假名没法转拼音，退一步取罗马音首字母；
 * 拉丁字母原样；其余（々、ヶ、数字、符号）归入「#」。
 *
 * 罗马音取平文式（Hepburn）：し→shi、ち→chi、つ→tsu、ふ→fu、じ/ぢ→j、づ→zu，
 * 与日本图书馆、书目索引的常见写法一致。
 */
object AuthorIndex {

    /** 非 A–Z 的都堆在这里，排在字母之后 */
    const val OTHER = "#"

    /** 只用来取首字母，所以按单字建表；拗音（きゃ）的首字母与 き 相同，不必单列 */
    private val KANA: Map<Char, String> = buildMap {
        fun row(prefix: String, vararg kana: Char) {
            kana.forEachIndexed { i, c ->
                put(c, prefix + "aiueo"[i])
            }
        }
        row("", 'あ', 'い', 'う', 'え', 'お')
        row("", 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')
        row("k", 'か', 'き', 'く', 'け', 'こ')
        row("g", 'が', 'ぎ', 'ぐ', 'げ', 'ご')
        row("s", 'さ', 'し', 'す', 'せ', 'そ')
        row("z", 'ざ', 'じ', 'ず', 'ぜ', 'ぞ')
        row("t", 'た', 'ち', 'つ', 'て', 'と')
        row("d", 'だ', 'ぢ', 'づ', 'で', 'ど')
        row("n", 'な', 'に', 'ぬ', 'ね', 'の')
        row("h", 'は', 'ひ', 'ふ', 'へ', 'ほ')
        row("b", 'ば', 'び', 'ぶ', 'べ', 'ぼ')
        row("p", 'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ')
        row("m", 'ま', 'み', 'む', 'め', 'も')
        row("y", 'や', 'ゆ', 'よ')
        row("r", 'ら', 'り', 'る', 'れ', 'ろ')
        row("w", 'わ', 'ゐ', 'ゑ', 'を')
        put('ん', "n")
        put('ゔ', "vu")
        // 平文式的几处特殊读法，直接决定首字母
        put('ち', "chi")
        put('ふ', "fu")
        put('じ', "ji")
        put('ぢ', "ji")
        put('づ', "zu")
        // 片假名：与平假名同音
        val kata = mapOf(
            'ア' to 'あ', 'イ' to 'い', 'ウ' to 'う', 'エ' to 'え', 'オ' to 'お',
            'ァ' to 'ぁ', 'ィ' to 'ぃ', 'ゥ' to 'ぅ', 'ェ' to 'ぇ', 'ォ' to 'ぉ',
            'カ' to 'か', 'キ' to 'き', 'ク' to 'く', 'ケ' to 'け', 'コ' to 'こ',
            'ガ' to 'が', 'ギ' to 'ぎ', 'グ' to 'ぐ', 'ゲ' to 'げ', 'ゴ' to 'ご',
            'サ' to 'さ', 'シ' to 'し', 'ス' to 'す', 'セ' to 'せ', 'ソ' to 'そ',
            'ザ' to 'ざ', 'ジ' to 'じ', 'ズ' to 'ず', 'ゼ' to 'ぜ', 'ゾ' to 'ぞ',
            'タ' to 'た', 'チ' to 'ち', 'ツ' to 'つ', 'テ' to 'て', 'ト' to 'と',
            'ダ' to 'だ', 'ヂ' to 'ぢ', 'ヅ' to 'づ', 'デ' to 'で', 'ド' to 'ど',
            'ナ' to 'な', 'ニ' to 'に', 'ヌ' to 'ぬ', 'ネ' to 'ね', 'ノ' to 'の',
            'ハ' to 'は', 'ヒ' to 'ひ', 'フ' to 'ふ', 'ヘ' to 'へ', 'ホ' to 'ほ',
            'バ' to 'ば', 'ビ' to 'び', 'ブ' to 'ぶ', 'ベ' to 'べ', 'ボ' to 'ぼ',
            'パ' to 'ぱ', 'ピ' to 'ぴ', 'プ' to 'ぷ', 'ペ' to 'ぺ', 'ポ' to 'ぽ',
            'マ' to 'ま', 'ミ' to 'み', 'ム' to 'む', 'メ' to 'め', 'モ' to 'も',
            'ヤ' to 'や', 'ユ' to 'ゆ', 'ヨ' to 'よ',
            'ラ' to 'ら', 'リ' to 'り', 'ル' to 'る', 'レ' to 'れ', 'ロ' to 'ろ',
            'ワ' to 'わ', 'ヰ' to 'ゐ', 'ヱ' to 'ゑ', 'ヲ' to 'を',
            'ン' to 'ん', 'ヴ' to 'ゔ',
        )
        kata.forEach { (k, h) -> put(k, getValue(h)) }
    }

    /** 这些字符不进排序，遇到就跳过（长音符、空格、间隔号、括号） */
    private val SKIP = setOf(
        'ー', '～', '〜', '·', '・', ' ', '\u3000', '　',
        '(', ')', '（', '）', '[', ']', '【', '】', '「', '」', '『', '』',
        '-', '_', '.', ',', '，', '。',
    )

    /** 作者名的索引字母；识别不出来给 [OTHER] */
    fun letter(author: String): String {
        for (raw in author.trim()) {
            if (raw in SKIP) continue
            val c = raw
            // 汉字（含日文汉字）：按中文拼音
            if (com.github.promeg.pinyinhelper.Pinyin.isChinese(c)) {
                val pinyin = com.github.promeg.pinyinhelper.Pinyin.toPinyin(c)
                val head = pinyin.firstOrNull()?.uppercaseChar()
                if (head != null && head in 'A'..'Z') return head.toString()
                continue
            }
            KANA[c]?.let { return it.first().uppercaseChar().toString() }
            if (c.isLetter() && c.uppercaseChar() in 'A'..'Z') return c.uppercaseChar().toString()
            // 数字、符号、々、ヶ 之类：认不出就归「#」，别硬猜
            return OTHER
        }
        return OTHER
    }
}
