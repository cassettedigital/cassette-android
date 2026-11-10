package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-980: UP NEXT does not list the kept songs as next while a station plays. */
class CassetteUpNextTest {
    @Test
    fun aStationHasNothingNextWhateverIsKept() {
        val kept = listOf("Minnesota, WI", "The Place Where He Inserted the Blade")
        assertTrue(CassetteUpNext.rows(kept, stationLive = true).isEmpty())
    }

    @Test
    fun songsListTheKeptQueueAsItIs() {
        val kept = listOf("one", "two")
        assertEquals(kept, CassetteUpNext.rows(kept, stationLive = false))
        assertTrue(CassetteUpNext.rows<String>(null, stationLive = false).isEmpty())
    }

    @Test
    fun onlyTheRadioTypeMarksAStation() {
        assertTrue(CassetteUpNext.isStation(Constants.MEDIA_TYPE_RADIO))
        assertFalse(CassetteUpNext.isStation("music"))
        assertFalse(CassetteUpNext.isStation(null))
    }
}
