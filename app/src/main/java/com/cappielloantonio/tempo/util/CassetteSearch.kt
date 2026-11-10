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

    /** A song with its words folded once, so a keystroke only compares. */
    private class Entry(val song: Download) {
        val title = fold(song.title)
        val artist = fold(song.artist)
        val album = fold(song.album)
    }

    /** One Search visit's copy of the phone: its songs, folded, and its records by id. */
    private class Index(val entries: List<Entry>, val records: Map<String, AlbumID3>)

    @Volatile
    private var index: Index? = null

    /**
     * Read the phone's songs once per Search visit (off the main thread): SearchFragment calls this
     * as Search opens, so songs synced since the last visit are found, and no keystroke pays for the
     * read or the folding (each keystroke used to fold every song's words again, ~4 s on a Pixel 8
     * with 2,000 songs).
     */
    @JvmStatic
    fun warm() {
        index = indexOf(AppDatabase.getInstance().downloadDao().getAllDownloadedSync())
    }

    private fun indexOf(all: List<Download>): Index = Index(
        all.map { Entry(it) },
        // The phone's records by id, each under its own artist (the Library's grouping, BUG-581).
        CassetteOfflineLibrary.groupAlbums(all).filter { it.id != null }.associateBy { it.id!! },
    )

    private val MARKS = Regex("\\p{M}+")

    @JvmStatic
    fun fold(s: String?): String = s?.let {
        MARKS.replace(Normalizer.normalize(it, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).trim()
    } ?: ""

    /** How well an already-folded name answers: starts with it, a word starts with it, holds it. */
    private fun rankFolded(n: String, q: String): Int = when {
        n.startsWith(q) -> 0
        n.split(' ').any { it.startsWith(q) } -> 1
        n.contains(q) -> 2
        else -> 3
    }

    private fun rank(name: String?, q: String): Int = rankFolded(fold(name), q)

    @JvmStatic
    fun search(query: String): Results {
        val q = fold(query)
        if (q.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        val idx = index ?: indexOf(AppDatabase.getInstance().downloadDao().getAllDownloadedSync()).also { index = it }

        val songs = idx.entries.filter { e ->
            e.title.contains(q) || e.artist.contains(q) || e.album.contains(q)
        }.sortedWith(compareBy<Entry>({ rankFolded(it.title, q) }, { rankFolded(it.artist, q) }, { it.title }))
            .map { it.song }

        val albums = LinkedHashMap<String, AlbumID3>()
        val artists = LinkedHashMap<String, ArtistID3>()
        for (e in idx.entries) {
            val d = e.song
            val albumId = d.albumId
            if (albumId != null && !albums.containsKey(albumId) && (e.album.contains(q) || e.artist.contains(q))) {
                // the record as the Library shows it: a soundtrack under its own artist, not a singer on it
                albums[albumId] = idx.records[albumId] ?: AlbumID3().apply {
                    id = albumId; name = d.album; artist = d.artist; artistId = d.artistId
                    coverArtId = d.coverArtId; year = d.year ?: 0
                }
            }
            val artistId = d.artistId
            if (artistId != null && !artists.containsKey(artistId) && e.artist.contains(q)) {
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
