package com.cappielloantonio.tempo.util

import android.content.Context
import android.util.Log
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.google.gson.JsonParser
import java.io.File

/**
 * cassette: genres for the music on this phone (iPhone LibraryGenres, 9f605fd1). A song's
 * own file genre wins; an album whose files carry none takes the genre the cloud gives it
 * (`GET /api/sync/album-genres`: your own genre, else the catalog's), keyed by the album's
 * group key. Asked twice a day; the answer is kept on the phone, so Genres works offline.
 */
object CassetteGenres {
    private const val TAG = "CassetteGenres"
    private const val FILE = "album-genres.json"
    private const val PREF = "cassette_album_genres_at"
    private const val EVERY_MS = 12 * 3600 * 1000L

    class Genre(val name: String, val albums: List<AlbumID3>)

    /** Ask the cloud when due (off the main thread); [onFresh] runs on the main thread when a new answer lands. */
    @JvmStatic
    @JvmOverloads
    fun refreshIfDue(context: Context, onFresh: Runnable? = null) {
        val app = context.applicationContext
        val prefs = com.cappielloantonio.tempo.App.getInstance().preferences
        if (System.currentTimeMillis() - prefs.getLong(PREF, 0) < EVERY_MS) return
        val bearer = Preferences.getCassetteBearerToken() ?: return
        Thread({
            try {
                val json = CassetteSyncApi.getAlbumGenres(bearer) ?: return@Thread
                File(app.filesDir, FILE).writeText(json)
                prefs.edit().putLong(PREF, System.currentTimeMillis()).apply()
                if (onFresh != null) android.os.Handler(android.os.Looper.getMainLooper()).post(onFresh)
            } catch (e: Exception) {
                Log.w(TAG, "album genres failed", e)
            }
        }, "cassette-album-genres").start()
    }

    private fun cloud(context: Context): Map<String, String> = try {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) emptyMap() else JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("albums")?.mapNotNull { e ->
                val o = e.asJsonObject
                // stored keys carry the unit separator where the phone's key has a NUL
                // (Postgres text refuses NUL; library-group-keys.ts persistableGroupKey)
                val key = o.get("group_key")?.asString?.replace('\u001f', '\u0000') ?: return@mapNotNull null
                val genre = o.get("genre")?.asString ?: return@mapNotNull null
                key to genre
            }?.toMap().orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    /** Every genre with music on this phone, the biggest first. Off the main thread. */
    @JvmStatic
    fun genres(context: Context): List<Genre> {
        val fromCloud = cloud(context)
        val songs = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        // one genre however its files spell it ("Indie Rock" / "indie rock"): the first spelling met names it
        val byGenre = LinkedHashMap<String, LinkedHashMap<String, AlbumID3>>()
        val names = HashMap<String, String>()
        songs.groupBy { it.albumId }.forEach { (albumId, tracks) ->
            if (albumId == null) return@forEach
            val first = tracks.first()
            val own = tracks.firstNotNullOfOrNull { it.genre?.trim()?.takeIf { g -> g.isNotEmpty() } }
            val genre = own ?: fromCloud[CassetteAlbumGroupKey.keyFromTracks(tracks.mapNotNull { it.artist }, first.album ?: "")]
                ?: return@forEach
            val album = AlbumID3().apply {
                id = albumId; name = first.album; artist = first.artist; artistId = first.artistId
                coverArtId = first.coverArtId; year = first.year ?: 0; this.genre = genre
            }
            val key = genre.lowercase()
            names.putIfAbsent(key, genre)
            byGenre.getOrPut(key) { LinkedHashMap() }[albumId] = album
        }
        return byGenre.map { (key, albums) -> Genre(names[key] ?: key, albums.values.sortedBy { CassetteLetters.sortKey(it.name) }) }
            .sortedByDescending { it.albums.size }
    }
}
