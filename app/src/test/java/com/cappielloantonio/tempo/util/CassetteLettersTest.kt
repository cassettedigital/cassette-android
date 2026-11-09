package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The iPhone's filing examples (Utilities.swift, the phone-taste mock), on Android. */
class CassetteLettersTest {
    @Test
    fun filesLikeTheIPhone() {
        assertEquals("B", CassetteLetters.letter("The Bends"))
        assertEquals("W", CassetteLetters.letter("(What's the Story) Morning Glory?"))
        assertEquals("A", CassetteLetters.letter("“Awaken, My Love!”"))
        assertEquals("A", CassetteLetters.letter("Átta"))
        assertEquals("#", CassetteLetters.letter("22, a Million"))
        assertEquals("#", CassetteLetters.letter("The (What)"))
        assertEquals("T", CassetteLetters.letter("Theatre Is Evil"))
        assertEquals("Bends", CassetteLetters.sortKey("The Bends"))
    }
}
