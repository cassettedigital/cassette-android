package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import java.text.Normalizer
import java.util.Locale

/**
 * cassette: Search on the phone (BUG-940; iPhone SearchMatcher.searchOnPhone).
 * What is on this phone, matched as you type, with no network: songs on their
 * title, artist or album; albums on their name or artist; artists on their name.
 * Names that start with what was typed lead, then the rest.
 */
object CassetteSearch {
    class Results(
        val songs: List<Child>,
        val albums: List<AlbumID3>,
        val artists: List<ArtistID3>,
    ) {
        val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
    }

    @Volatile
    private var library: List<Download>? = null

    /** The phone's records by id, each under its own artist (the Library's grouping, BUG-581). */
    @Volatile
    private var records: Map<String, AlbumID3>? = null

    /** Read the phone's songs once per Search visit (off the main thread). */
    @JvmStatic
    fun warm() {
        val all = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        library = all
        records = recordsOf(all)
    }

    private fun recordsOf(all: List<Download>): Map<String, AlbumID3> =
        CassetteOfflineLibrary.groupAlbums(all).filter { it.id != null }.associateBy { it.id!! }

    @JvmStatic
    fun fold(s: String?): String = s?.let {
        Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
    } ?: ""

    private fun rank(name: String?, q: String): Int {
        val n = fold(name)
        return when {
            n.startsWith(q) -> 0
            n.split(' ').any { it.startsWith(q) } -> 1
            n.contains(q) -> 2
            else -> 3
        }
    }

    @JvmStatic
    fun search(query: String): Results {
        val q = fold(query)
        val all = library ?: AppDatabase.getInstance().downloadDao().getAllDownloadedSync().also { library = it }
        if (q.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        val byId = records ?: recordsOf(all).also { records = it }

        val songs = all.filter { d ->
            fold(d.title).contains(q) || fold(d.artist).contains(q) || fold(d.album).contains(q)
        }.sortedWith(compareBy<Download>({ rank(it.title, q) }, { rank(it.artist, q) }, { fold(it.title) }))

        val albums = LinkedHashMap<String, AlbumID3>()
        val artists = LinkedHashMap<String, ArtistID3>()
        for (d in all) {
            val albumId = d.albumId
            if (albumId != null && !albums.containsKey(albumId) && (fold(d.album).contains(q) || fold(d.artist).contains(q))) {
                // the record as the Library shows it: a soundtrack under its own artist, not a singer on it
                albums[albumId] = byId[albumId] ?: AlbumID3().apply {
                    id = albumId; name = d.album; artist = d.artist; artistId = d.artistId
                    coverArtId = d.coverArtId; year = d.year ?: 0
                }
            }
            val artistId = d.artistId
            if (artistId != null && !artists.containsKey(artistId) && fold(d.artist).contains(q)) {
                artists[artistId] = ArtistID3().apply { id = artistId; name = d.artist; coverArtId = "ar-$artistId" }
            }
        }
        return Results(
            songs.take(5),
            albums.values.sortedWith(compareBy({ rank(it.name, q) }, { rank(it.artist, q) })).take(8),
            artists.values.sortedBy { rank(it.name, q) }.take(6),
        )
    }
}
