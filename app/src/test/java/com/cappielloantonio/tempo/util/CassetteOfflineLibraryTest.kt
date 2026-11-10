package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.model.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-581: the Library's records wear their own artist, and Artists lists only artists with records. */
class CassetteOfflineLibraryTest {
    private var n = 0

    private fun song(album: String, albumId: String, artist: String, artistId: String) =
        Download("s${n++}").apply {
            this.album = album; this.albumId = albumId; this.artist = artist; this.artistId = artistId
        }

    @Test
    fun aSoundtrackIsVariousArtistsAndAddsNoOne() {
        val songs = listOf(
            song("Drive", "al-drive", "Kavinsky", "kav"),
            song("Drive", "al-drive", "College", "col"),
            song("Drive", "al-drive", "Desire", "des"),
            song("OK Computer", "al-okc", "Radiohead", "rh"),
            song("OK Computer", "al-okc", "Radiohead", "rh"),
        )
        val albums = CassetteOfflineLibrary.groupAlbums(songs).associateBy { it.id }
        assertEquals("Various Artists", albums["al-drive"]!!.artist)
        assertNull(albums["al-drive"]!!.artistId)
        assertEquals("Radiohead", albums["al-okc"]!!.artist)
        assertEquals("rh", albums["al-okc"]!!.artistId)

        val artists = CassetteOfflineLibrary.groupArtists(albums.values.toList())
        assertEquals(listOf("Radiohead"), artists.map { it.name })
        assertEquals(1, artists[0].albumCount)
    }

    @Test
    fun anAlbumWearsTheArtistMostOfItsSongsAreBy() {
        // the first downloaded song is a guest's; the album is still the band's
        val songs = listOf(
            song("Plastic Beach", "al-pb", "Snoop Dogg", "snoop"),
            song("Plastic Beach", "al-pb", "Gorillaz", "gz"),
            song("Plastic Beach", "al-pb", "Gorillaz feat. Lou Reed", "gz"),
            song("Plastic Beach", "al-pb", "Gorillaz", "gz"),
        )
        val album = CassetteOfflineLibrary.groupAlbums(songs).single()
        assertEquals("Gorillaz", album.artist)
        assertEquals("gz", album.artistId)
        assertEquals(4, album.songCount)
        assertEquals(listOf("Gorillaz"), CassetteOfflineLibrary.groupArtists(listOf(album)).map { it.name })
    }

    @Test
    fun anArtistCountsEachOfTheirRecordsOnce() {
        val songs = listOf(
            song("Kid A", "al-kida", "Radiohead", "rh"),
            song("Kid A", "al-kida", "Radiohead", "rh"),
            song("Amnesiac", "al-amn", "Radiohead", "rh"),
        )
        val artists = CassetteOfflineLibrary.groupArtists(CassetteOfflineLibrary.groupAlbums(songs))
        assertEquals(1, artists.size)
        assertEquals(2, artists[0].albumCount)
        assertEquals("ar-rh", artists[0].coverArtId)
    }
}
