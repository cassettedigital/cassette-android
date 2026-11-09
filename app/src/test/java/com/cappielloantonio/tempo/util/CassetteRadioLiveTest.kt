package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CassetteRadioLiveTest {
    private val clock: (Long) -> String = { "T${it / 60_000}" }

    @Test
    fun streamTitleSplitsIntoSongAndArtist() {
        assertEquals(CassetteRadioLive.Song("Waterloo Sunset", "The Kinks"), CassetteRadioLive.parseStreamTitle("The Kinks - Waterloo Sunset"))
        assertEquals(CassetteRadioLive.Song("Song - Live", "A"), CassetteRadioLive.parseStreamTitle("A - Song - Live"))
        assertEquals(CassetteRadioLive.Song("Morning Show", ""), CassetteRadioLive.parseStreamTitle(" Morning Show -"))
        assertNull(CassetteRadioLive.parseStreamTitle(" - "))
    }

    @Test
    fun aTitleThatIsOnlyTheStationIsNotASong() {
        assertTrue(CassetteRadioLive.isStationName("The Current-", "The Current"))
        assertTrue(CassetteRadioLive.isStationName("- KEXP |", "KEXP"))
        assertFalse(CassetteRadioLive.isStationName("KEXP Song of the Day", "KEXP"))
    }

    @Test
    fun whenLineReadsUntilThenNext() {
        val now = 0L
        val until = 60 * 60_000L
        assertEquals("Until T60, then MARTELO",
            CassetteRadioLive.whenLine(CassetteRadioLive.Live(until = until, next = "MARTELO", nextStarts = until), now, clock))
        assertEquals("Until T60 · T120: Drive Time",
            CassetteRadioLive.whenLine(CassetteRadioLive.Live(until = until, next = "Drive Time", nextStarts = 2 * until), now, clock))
        assertEquals("Next: Waterloo Sunset · The Kinks",
            CassetteRadioLive.whenLine(CassetteRadioLive.Live(next = "Waterloo Sunset · The Kinks"), now, clock))
        assertNull(CassetteRadioLive.whenLine(CassetteRadioLive.Live(until = -1), now, clock))
    }

    @Test
    fun theCloudsAnswerPrefersTheRecordWeCarry() {
        val live = CassetteRadioLive.parseNowPlaying("""
            {"station":"kexp","fetchedAt":"2026-11-09T18:00:00Z",
             "show":{"name":"The Morning Show","until":"2026-11-09T19:00:00Z","image":"https://x/show.jpg"},
             "track":{"artist":"Big Thief","title":"Vampire Empire","coverUrl":"https://x/kexp.jpg"},
             "match":{"albumSlug":"big-thief-vampire-empire","title":"Vampire Empire","artistName":"Big Thief","coverUrl":"https://x/small.webp","coverFullUrl":"https://x/full.webp","exact":true},
             "next":[{"name":"Midday","starts":"2026-11-09T19:00:00Z"}]}
        """.trimIndent())!!
        assertEquals(CassetteRadioLive.Song("Vampire Empire", "Big Thief"), live.song)
        assertEquals("https://x/full.webp", live.artwork)
        assertEquals("big-thief-vampire-empire", live.albumSlug)
        assertEquals("The Morning Show", live.show)
        assertEquals("Midday", live.next)
    }

    @Test
    fun theBareEnvelopeSaysNothing() {
        assertNull(CassetteRadioLive.parseNowPlaying("""{"station":"wfmu","fetchedAt":"2026-11-09T18:00:00Z"}"""))
    }
}
