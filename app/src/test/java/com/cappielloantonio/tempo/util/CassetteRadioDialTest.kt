package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The Radio page's order and its On now cards, held to the Mac's and the website's (components/radio/RadioDial). */
class CassetteRadioDialTest {

    private fun station(slug: String, section: String, live: Boolean = false, isStation: Boolean = false, image: String? = "https://x/$slug.png") =
        CassetteRadio.Station(slug, slug.uppercase(), "", section, "https://stream/$slug", null, null, image, null,
            live = live, isStation = isStation)

    @Test
    fun dailyTurnIsTheWebsHash() {
        // apps/cassette/src/lib/radio/daily-turn.ts, run in node for day 20773 (2026-11-16)
        assertEquals(2100125714L, CassetteRadio.dailyTurn(20773, "kexp"))
        assertEquals(1930404695L, CassetteRadio.dailyTurn(20773, "nts-1"))
        assertEquals(1947182314L, CassetteRadio.dailyTurn(20773, "nts-2"))
        assertEquals(3002923717L, CassetteRadio.dailyTurn(20773, "wfmu"))
        assertEquals(330453217L, CassetteRadio.dailyTurn(20773, "the-current"))
    }

    @Test
    fun savedStationsLeadTheDialAndSectionsKeepTheirOrder() {
        val dial = listOf(
            station("kexp", "Live · United States"),
            station("wfmu", "Live · United States"),
            station("nts-1", "Live · London"),
            station("groove", "Chill"),
        )
        val order = CassetteRadio.dial(dial, listOf("groove", "nts-1")).map { it.slug }
        assertEquals(listOf("groove", "nts-1", "kexp", "wfmu"), order)
        assertEquals(listOf("kexp", "wfmu", "nts-1", "groove"), CassetteRadio.dial(dial, emptyList()).map { it.slug })
    }

    @Test
    fun onNowIsSavedThenLiveInTodaysTurnSixAtMost() {
        val live = listOf("kexp", "nts-1", "nts-2", "wfmu", "dublab", "kalx", "kxlu", "wfuv").map { station(it, "Live · United States", live = true, isStation = true) }
        val channel = station("groove", "Chill", live = true)
        val noMark = station("quiet", "Live · United States", live = true, isStation = true, image = null)
        val all = live + channel + noMark
        val board = CassetteRadio.onNowStations(all, listOf("groove", "wfmu"), 20773)
        assertEquals(6, board.size)
        // saved first, newest saved first, whatever they are
        assertEquals(listOf("groove", "wfmu"), board.take(2).map { it.slug })
        // then only live stations with a mark, by today's turn
        val rest = board.drop(2).map { it.slug }
        assertEquals(rest.sortedBy { CassetteRadio.dailyTurn(20773, it) }, rest)
        assertFalse("quiet" in rest || "groove" in rest)
    }

    @Test
    fun onAirReadsTheSongTheShowAndTheCover() {
        val song = CassetteRadioLive.parseOnAir("""{"station":"kexp","show":{"name":"Morning","until":"2026-11-16T18:00:00Z","image":"https://kexp/show.jpg"},"track":{"artist":"The High Llamas","title":"Stone Cold Slow","coverUrl":"https://caa/c.jpg","coverRef":{"src":"https://media.cassette.digital/i/{rung}/x/abc"}}}""")
        assertNotNull(song)
        assertEquals("The High Llamas · Stone Cold Slow", song!!.now)
        assertEquals("https://caa/c.jpg", song.cover)
        assertEquals("https://media.cassette.digital/i/{rung}/x/abc", song.coverSrc)
        assertEquals(java.time.Instant.parse("2026-11-16T18:00:00Z").toEpochMilli(), song.until)

        val talk = CassetteRadioLive.parseOnAir("""{"station":"nts-1","show":{"name":"WOODGRAIN","image":""}}""")
        assertEquals("WOODGRAIN", talk!!.now)
        assertNull(talk.cover)

        assertNull(CassetteRadioLive.parseOnAir("""{"station":"the-current","fetchedAt":"2026-11-16T18:00:00Z"}"""))
    }
}
