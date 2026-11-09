package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CassetteLikenessTest {
    private fun song(moods: List<String>, sound: List<String> = emptyList(), energy: String? = null) =
        CassetteLikeness.tagVector(moods, energy, null, sound)

    @Test
    fun soundCountsAsMuchAsMood() {
        // BUG-498: the same mood words, nothing alike in sound, is not a match
        val a = CassetteLikeness.Prepared(song(listOf("wistful", "contemplative"), listOf("indie pop")))
        val b = CassetteLikeness.Prepared(song(listOf("wistful", "contemplative"), listOf("britpop", "pub rock")))
        val like = CassetteLikeness.likeness(a, b)
        assertEquals(1.0, like.mood, 1e-9)
        assertEquals(0.0, like.sound!!, 1e-9)
        assertFalse(like.passes(CassetteLikeness.recordBar(0.75)))
    }

    @Test
    fun withoutSoundTheMoodAloneDecides() {
        val a = CassetteLikeness.Prepared(song(listOf("wistful", "warm")))
        val b = CassetteLikeness.Prepared(song(listOf("wistful", "warm", "hazy")))
        val like = CassetteLikeness.likeness(a, b)
        assertNull(like.sound)
        assertEquals(2 / Math.sqrt(2.0 * 3.0), like.mood, 1e-6)
        assertTrue(like.passes(CassetteLikeness.recordBar(0.75)))
    }

    @Test
    fun scenariosNeverCount() {
        val a = CassetteLikeness.Prepared(CassetteLikeness.tagVector(listOf("warm"), null, null, emptyList(), listOf("road_trip")))
        val b = CassetteLikeness.Prepared(CassetteLikeness.tagVector(listOf("warm"), null, null, emptyList(), listOf("workout")))
        assertEquals(1.0, CassetteLikeness.likeness(a, b).mood, 1e-9)
    }

    @Test
    fun moreLikeKeepsOnePerArtistAndLeavesOutTheExcluded() {
        val warm = song(listOf("warm", "hazy"), listOf("dream pop"))
        val albums = listOf(
            CassetteLikeness.Candidate("a1", "beach house", listOf(warm, warm)),
            CassetteLikeness.Candidate("a2", "beach house", listOf(warm)),
            CassetteLikeness.Candidate("b1", "slowdive", listOf(warm)),
            CassetteLikeness.Candidate("c1", "green day", listOf(song(listOf("angry", "loud"), listOf("pop punk")))),
            CassetteLikeness.Candidate("page", "mazzy star", listOf(warm)),
        )
        val ids = CassetteLikeness.moreLike(warm, albums, excluding = setOf("page"), minMatch = 0.75)
        assertEquals(listOf("a1", "b1"), ids)
    }

    @Test
    fun moreLikeArtistShowsEachArtistByTheirWeightiestRecord() {
        val warm = song(listOf("warm", "hazy"), listOf("dream pop"))
        val albums = listOf(
            CassetteLikeness.Candidate("p1", "mazzy star", listOf(warm)),
            CassetteLikeness.Candidate("a1", "beach house", listOf(warm)),
            CassetteLikeness.Candidate("a2", "beach house", listOf(warm, warm, warm)),
            CassetteLikeness.Candidate("v1", "various artists", listOf(warm)),
            CassetteLikeness.Candidate("c1", "green day", listOf(song(listOf("angry"), listOf("pop punk")))),
        )
        assertEquals(listOf("a2"), CassetteLikeness.moreLikeArtist("Mazzy Star ", albums))
    }

    @Test
    fun interludesAreNotSongs() {
        assertTrue(CassetteLikeness.isInterlude("Anything", 45))
        assertTrue(CassetteLikeness.isInterlude("Skit (Live)", 200))
        assertTrue(CassetteLikeness.isInterlude("Intro", 90))
        assertFalse(CassetteLikeness.isInterlude("Intro", 240))
        assertTrue(CassetteLikeness.isInterlude("(Vegas // Dad)", 120))
        assertFalse(CassetteLikeness.isInterlude("Space Song", 320))
        assertFalse(CassetteLikeness.isInterlude("Untitled", 0))
    }

    @Test
    fun artistNamesFoldAccentsAndCase() {
        assertEquals("bjork", CassetteLikeness.normalizedArtistName(" Björk "))
    }
}
