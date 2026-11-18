package com.nascomic.reader.library.author

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作者索引字母的判据。
 *
 * 口径（2026-11-19 定）：面向中文读者——汉字一律按中文拼音，**日文汉字也按中文读**
 * （手塚治虫 → S，不是日语的 T）；假名取罗马音首字母；其余归「#」。
 */
class AuthorIndexTest {

    @Test
    fun `汉字按中文拼音取首字母`() {
        assertEquals("G", AuthorIndex.letter("高桥留美子"))
        assertEquals("H", AuthorIndex.letter("荒木飞吕彦"))
        assertEquals("J", AuthorIndex.letter("井上雄彦"))
        assertEquals("L", AuthorIndex.letter("龍幸伸"))
    }

    @Test
    fun `日文汉字也按中文读`() {
        assertEquals("S", AuthorIndex.letter("手塚治虫"))
        assertEquals("Z", AuthorIndex.letter("塚"))
        // 异体字照样能转：髙 与 高 同音
        assertEquals("G", AuthorIndex.letter("髙橋"))
    }

    @Test
    fun `假名取罗马音首字母`() {
        assertEquals("A", AuthorIndex.letter("あずまきよひこ"))
        assertEquals("A", AuthorIndex.letter("アズマキヨヒコ"))
        assertEquals("K", AuthorIndex.letter("かわぐち"))
        assertEquals("S", AuthorIndex.letter("しんどう"))
        assertEquals("C", AuthorIndex.letter("ちば"))
        assertEquals("T", AuthorIndex.letter("つるた"))
        assertEquals("F", AuthorIndex.letter("ふじこ"))
        assertEquals("J", AuthorIndex.letter("じゃき"))
        assertEquals("P", AuthorIndex.letter("ぱんた"))
    }

    @Test
    fun `拉丁字母原样`() {
        assertEquals("C", AuthorIndex.letter("CLAMP"))
        assertEquals("O", AuthorIndex.letter("OKAMA"))
        assertEquals("A", AuthorIndex.letter("azuma"))
    }

    @Test
    fun `认不出的归其他`() {
        assertEquals(AuthorIndex.OTHER, AuthorIndex.letter("々"))
        assertEquals("K", AuthorIndex.letter("ケ"))
        assertEquals(AuthorIndex.OTHER, AuthorIndex.letter("ヶ"))
        assertEquals(AuthorIndex.OTHER, AuthorIndex.letter(""))
        assertEquals(AuthorIndex.OTHER, AuthorIndex.letter("　"))
    }

    @Test
    fun `开头是符号或括号时跳过再取`() {
        assertEquals("G", AuthorIndex.letter("（高桥）留美子"))
        assertEquals("A", AuthorIndex.letter("・あずま"))
    }
}
