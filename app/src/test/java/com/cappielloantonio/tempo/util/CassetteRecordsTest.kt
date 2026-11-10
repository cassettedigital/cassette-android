package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.model.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-970 (the album-artist tag names a record, as on the web) and BUG-979 (a song the computer
 * filed under another album id is still its record, unless it is a second edition of it).
 */
class CassetteRecordsTest {
    private var n = 0

    private fun song(
        album: String,
        albumId: String,
        artist: String,
        artistId: String = "ar-$artist",
        track: Int? = null,
        disc: Int? = null,
        year: Int? = null,
        tag: String? = null,
    ) = Download("s${n++}").apply {
        this.album = album; this.albumId = albumId; this.artist = artist; this.artistId = artistId
        this.track = track; this.discNumber = disc; this.year = year; this.albumArtist = tag
    }

    /** [count] songs of one record under one album id, tracks 1..count (from [first]). */
    private fun record(album: String, albumId: String, artist: String, count: Int, first: Int = 1, year: Int? = null, tag: String? = null) =
        (first until first + count).map { song(album, albumId, artist, track = it, year = year, tag = tag) }

    // ---- BUG-970: the album-artist tag ----

    @Test
    fun aRecordTheComputerTagsVariousArtistsIsNotNamedForItsTopSinger() {
        // K-Pop Demon Hunters: most songs are by HUNTR/X, the computer's album-artist tag says Various Artists
        val songs = (1..5).map { song("KPop Demon Hunters", "kpdh", "HUNTR/X", track = it, tag = "Various Artists") } +
            song("KPop Demon Hunters", "kpdh", "Saja Boys", track = 6, tag = "Various Artists") +
            song("KPop Demon Hunters", "kpdh", "TWICE", track = 7, tag = "Various Artists")
        assertEquals("Various Artists", CassetteRecords.albumArtist(songs))

        val album = CassetteOfflineLibrary.groupAlbums(songs).single()
        assertEquals("Various Artists", album.artist)
        assertNull(album.artistId) // not someone to go to
        assertEquals(7, album.songCount)
    }

    @Test
    fun withoutTagsTheTopArtistStillNamesTheRecord() {
        // rows from before the tag was kept: the heuristic the phone always had
        val songs = (1..5).map { song("KPop Demon Hunters", "kpdh", "HUNTR/X", track = it) } +
            song("KPop Demon Hunters", "kpdh", "Saja Boys", track = 6)
        assertEquals("HUNTR/X", CassetteRecords.albumArtist(songs))
    }

    @Test
    fun songsThatDisagreeOnTheTagFallBackToCountingAndAnEmptyTagSaysNothing() {
        val disagree = listOf(
            song("Mix", "m", "A", tag = "A"), song("Mix", "m", "A", tag = "A"), song("Mix", "m", "B", tag = "B"),
        )
        assertEquals("A", CassetteRecords.albumArtist(disagree)) // A twice, B once
        val blank = listOf(song("Mix", "m", "A", tag = ""), song("Mix", "m", "A", tag = "  "), song("Mix", "m", "B"))
        assertEquals("A", CassetteRecords.albumArtist(blank))
        // one tag on some songs, none on the rest: the tag still names it (the web's rule)
        val some = listOf(song("Mix", "m", "A", tag = "The Band"), song("Mix", "m", "B"))
        assertEquals("The Band", CassetteRecords.albumArtist(some))
    }

    @Test
    fun aTagSpelledAlittleDifferentlyStillLeadsToTheArtist() {
        val songs = (1..3).map { song("Lemonade", "lem", "BEYONCE", artistId = "bey", track = it, tag = "Beyoncé") }
        val album = CassetteOfflineLibrary.groupAlbums(songs).single()
        assertEquals("Beyoncé", album.artist)
        assertEquals("bey", album.artistId)
    }

    // ---- BUG-979: one record under two album ids ----

    @Test
    fun aBonusSongTaggedApartIsFoldedIntoItsRecord() {
        // Laufey: an 18-song record and "Clockwork" alone, the same name, artist and year
        val songs = record("A Matter of Time: The Final Hour", "al-main", "Laufey", 18, year = 2025) +
            song("A Matter of Time: The Final Hour", "al-odd", "Laufey", track = 19, year = 2025)
        val fold = CassetteRecords.foldMap(songs)
        assertEquals(mapOf("al-odd" to "al-main"), fold)

        val albums = CassetteOfflineLibrary.groupAlbums(songs)
        assertEquals(1, albums.size)
        assertEquals("al-main", albums[0].id)
        assertEquals(19, albums[0].songCount)
        assertEquals(1, CassetteRecords.recordCount(songs))
    }

    @Test
    fun theBiggerHalfNamesTheRecordWhateverOrderTheSongsComeIn() {
        // Gorillaz: 11 + 6, the small half first
        val songs = record("Song Machine, Season One: Strange Timez", "al-b", "Gorillaz", 6, first = 12, year = 2020) +
            record("Song Machine, Season One: Strange Timez", "al-a", "Gorillaz", 11, year = 2020)
        assertEquals(mapOf("al-b" to "al-a"), CassetteRecords.foldMap(songs))
        val album = CassetteOfflineLibrary.groupAlbums(songs).single()
        assertEquals("al-a", album.id)
        assertEquals(17, album.songCount)
    }

    @Test
    fun twoEditionsOfARecordStayTwoRecords() {
        // Hozier 2014 and Hozier 2025: the same name and artist, but the same slots twice and eleven years apart
        val songs = record("Hozier", "al-2014", "Hozier", 16, year = 2014) + record("Hozier", "al-2025", "Hozier", 10, year = 2025)
        assertTrue(CassetteRecords.foldMap(songs).isEmpty())
        assertEquals(2, CassetteRecords.recordCount(songs))
        assertEquals(2, CassetteOfflineLibrary.groupAlbums(songs).size)
    }

    @Test
    fun twoCopiesOfOneSongListAreNotMergedAndNeitherAreYearsApart() {
        // a slot taken twice, though the years agree
        val twice = record("Kid A", "x", "Radiohead", 10, year = 2000) + record("Kid A", "y", "Radiohead", 3, year = 2000)
        assertTrue(CassetteRecords.foldMap(twice).isEmpty())
        // no slot twice, but five years apart: a reissue, not one record
        val apart = record("Kid A", "x", "Radiohead", 10, year = 2000) + record("Kid A", "y", "Radiohead", 3, first = 11, year = 2005)
        assertTrue(CassetteRecords.foldMap(apart).isEmpty())
        // a year apart is the same record (a bonus song carrying its release year)
        val near = record("Kid A", "x", "Radiohead", 10, year = 2000) + record("Kid A", "y", "Radiohead", 1, first = 11, year = 2001)
        assertEquals(mapOf("y" to "x"), CassetteRecords.foldMap(near))
    }

    @Test
    fun differentArtistsOrNamesOrDiscsAreNeverFoldedByAccident() {
        val otherArtist = record("Greatest Hits", "x", "Queen", 8) + record("Greatest Hits", "y", "ABBA", 4, first = 9)
        assertTrue(CassetteRecords.foldMap(otherArtist).isEmpty())
        val otherName = record("Kid A", "x", "Radiohead", 8) + record("Amnesiac", "y", "Radiohead", 4, first = 9)
        assertTrue(CassetteRecords.foldMap(otherName).isEmpty())
        // disc 2 track 1 is not disc 1 track 1
        val discs = record("The Wall", "x", "Pink Floyd", 3).map { it.apply { discNumber = 1 } } +
            song("The Wall", "y", "Pink Floyd", track = 1, disc = 2)
        assertEquals(mapOf("y" to "x"), CassetteRecords.foldMap(discs))
    }

    @Test
    fun songsWithNoTrackNumbersFoldOnlyWhenTheYearsAgree() {
        val a = (1..4).map { song("Singles", "x", "Band", year = 2020) }
        val same = a + song("Singles", "y", "Band", year = 2020)
        assertEquals(mapOf("y" to "x"), CassetteRecords.foldMap(same))
        val far = a + song("Singles", "y", "Band", year = 2012)
        assertTrue(CassetteRecords.foldMap(far).isEmpty())
    }

    @Test
    fun recordIdAnswersAnIdWithItselfWhenItStandsAlone() {
        val fold = mapOf("odd" to "main")
        assertEquals("main", CassetteRecords.recordId(fold, "odd"))
        assertEquals("main", CassetteRecords.recordId(fold, "main"))
        assertEquals("other", CassetteRecords.recordId(fold, "other"))
        assertNull(CassetteRecords.recordId(fold, null))
    }

    @Test
    fun aRecordsSongsComeBackWholeFromEitherId() {
        val songs = record("A Matter of Time", "main", "Laufey", 3, year = 2025) +
            song("A Matter of Time", "odd", "Laufey", track = 4, year = 2025) +
            record("Bewitched", "other", "Laufey", 2, year = 2023)
        for (id in listOf("main", "odd")) {
            val titles = CassetteRecords.songsOf(songs, id)
            assertEquals(4, titles.size)
            assertEquals(listOf(1, 2, 3, 4), titles.map { it.track })
        }
        assertEquals(2, CassetteRecords.songsOf(songs, "other").size)
        assertTrue(CassetteRecords.songsOf(songs, "nothing").isEmpty())
    }

    @Test
    fun theFoldedRecordLooksLikeItsOwnSongNotTheOddOne() {
        val odd = song("Record", "odd", "Band", track = 9).apply { coverArtId = "cover-odd" }
        val main = record("Record", "main", "Band", 8).onEach { it.coverArtId = "cover-main" }
        // the odd song is listed first, as the store may well give it
        val album = CassetteOfflineLibrary.groupAlbums(listOf(odd) + main).single()
        assertEquals("main", album.id)
        assertEquals("cover-main", album.coverArtId)
        assertFalse(album.songCount == 8)
        assertEquals(9, album.songCount)
    }
}
