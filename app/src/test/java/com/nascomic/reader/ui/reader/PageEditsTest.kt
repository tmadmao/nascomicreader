package com.nascomic.reader.ui.reader

import com.nascomic.reader.library.data.PageEditEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageEditsTest {
    private val edit = PageEditEntity(1, 1, "original", PageEdits.NORMAL, 90, PageEdits.NEXT, "mate")

    @Test
    fun bothPagesMustMatchBeforeTheyCanPair() {
        val originals = listOf("cover", "original", "mate")
        val intact = PageEdits.verify(listOf(edit), 3, { 0 }, originals::get)
        assertEquals(2, intact.pairedWith(1))
        assertEquals(1, intact.pairedWith(2))

        val changedPartner = PageEdits.verify(listOf(edit), 3, { 0 }, listOf("cover", "original", "new")::get)
        assertEquals(90, changedPartner[1]?.rotation)
        assertNull(changedPartner.pairedWith(1))

        val reordered = PageEdits.verify(listOf(edit), 3, { 0 }, listOf("cover", "mate", "original")::get)
        assertNull(reordered[1])
        assertNull(reordered[2])

        val newChapterBoundary = PageEdits.verify(listOf(edit), 3, { if (it < 2) 0 else 1 }, originals::get)
        assertNull(newChapterBoundary.pairedWith(1))
    }

    @Test
    fun unlinkingFromTheOtherPageKeepsRotationAndKind() {
        val updates = PageEdits.unlink(listOf(edit), 2, null)
        assertEquals(listOf(edit.copy(pairing = PageEdits.AUTO, partnerProof = null)), updates)
        val moved = PageEdits.unlink(listOf(edit), 3, 2)
        assertEquals(updates, moved)
    }
}
