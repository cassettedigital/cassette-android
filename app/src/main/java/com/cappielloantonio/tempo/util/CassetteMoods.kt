package com.cappielloantonio.tempo.util

import android.content.Context
import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.google.gson.JsonParser
import java.io.File

/**
 * cassette: the MORE LIKE shelves on album, artist and playlist pages (iPhone
 * AlbumDetailVC / ArtistDetailVC / PlaylistDetailVC, founder 2026-10-21): other
 * records on this phone whose songs carry the same moods and sound.
 *
 * Each song's tags come from the cloud (`GET /api/sync/library-moods`, keyed by
 * cassette_local_id), asked at most twice a day and kept on the phone so the
 * shelves work offline. The matching is CassetteLikeness, the iPhone's own.
 */
object CassetteMoods {
    private const val TAG = "CassetteMoods"
    private const val FILE = "library-moods.json"
    private const val PREF = "cassette_library_moods_at"
    private const val EVERY_MS = 12 * 3600 * 1000L

    @Volatile private var parsed: Map<String, Map<String, Double>> = emptyMap()
    @Volatile private var parsedVersion = -1L

    /** Ask the cloud when due (off the main thread); [onFresh] runs on the main thread when new tags land. */
    @JvmStatic
    @JvmOverloads
    fun refreshIfDue(context: Context, onFresh: Runnable? = null) {
        val app = context.applicationContext
        val prefs = App.getInstance().preferences
        if (System.currentTimeMillis() - prefs.getLong(PREF, 0) < EVERY_MS) return
        val bearer = Preferences.getCassetteBearerToken() ?: return
        Thread({
            try {
                val json = CassetteSyncApi.getLibraryMoods(bearer) ?: return@Thread
                File(app.filesDir, FILE).writeText(json)
                prefs.edit().putLong(PREF, System.currentTimeMillis()).apply()
                if (onFresh != null) android.os.Handler(android.os.Looper.getMainLooper()).post(onFresh)
            } catch (e: Exception) {
                Log.w(TAG, "library moods failed", e)
            }
        }, "cassette-library-moods").start()
    }

    /** Song tags by cassette_local_id, read from the kept answer once per version of it. */
    private fun tags(context: Context): Map<String, Map<String, Double>> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyMap()
        val version = f.lastModified()
        if (version == parsedVersion) return parsed
        val out = HashMap<String, Map<String, Double>>()
        try {
            JsonParser.parseString(f.readText()).asJsonObject.getAsJsonArray("tracks")?.forEach { e ->
                val o = e.asJsonObject
                val id = o.get("cassette_local_id")?.asString ?: return@forEach
                fun list(name: String) = o.getAsJsonArray(name)?.mapNotNull { if (it.isJsonNull) null else it.asString }.orEmpty()
                fun str(name: String) = o.get(name)?.takeIf { !it.isJsonNull }?.asString
                out[id] = CassetteLikeness.tagVector(list("moods"), str("energy"), str("valence"), list("subgenres"), list("scenarios"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "library moods unreadable", e)
            return emptyMap()
        }
        parsed = out
        parsedVersion = version
        return out
    }

    private class Index(
        val candidates: List<CassetteLikeness.Candidate>,
        val albums: Map<String, AlbumID3>,
        val songs: Map<String, Download>,
        val songTags: Map<String, Map<String, Double>>,
    )

    /** Every tagged song on this phone, interludes left out, summed per record. Off the main thread. */
    private fun index(context: Context): Index? {
        val tags = tags(context)
        if (tags.isEmpty()) return null
        val rows = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
            .filter { it.albumId != null && it.cassetteLocalId != null && tags.containsKey(it.cassetteLocalId) }
            .filter { !CassetteLikeness.isInterlude(it.title, it.duration ?: 0) }
            .sortedBy { it.id } // summed tags add up the same way every time (BUG-494)
        if (rows.isEmpty()) return null
        val albums = HashMap<String, AlbumID3>()
        val candidates = rows.groupBy { it.albumId!! }.map { (albumId, songs) ->
            val first = songs.first()
            val artist = CassetteAlbumGroupKey.effectiveAlbumArtist(songs.mapNotNull { it.artist?.let(CassetteAlbumGroupKey::stripFeatured) })
            albums[albumId] = AlbumID3().apply {
                id = albumId; name = first.album; this.artist = artist.ifEmpty { first.artist }; artistId = first.artistId
                coverArtId = first.coverArtId; year = first.year ?: 0
            }
            CassetteLikeness.Candidate(albumId, CassetteLikeness.normalizedArtistName(artist), songs.map { tags.getValue(it.cassetteLocalId!!) })
        }.sortedBy { it.albumId }
        return Index(candidates, albums, rows.associateBy { it.id }, tags)
    }

    /** MORE LIKE *record*: other artists' records, one each, never its own artist's nor Various Artists. */
    @JvmStatic
    fun forAlbum(context: Context, albumId: String, pageArtist: String?): List<AlbumID3> {
        val index = index(context) ?: return emptyList()
        val own = index.candidates.firstOrNull { it.albumId == albumId } ?: return emptyList()
        val page = CassetteLikeness.normalizedArtistName(pageArtist ?: index.albums[albumId]?.artist)
        val excluding = HashSet<String>().apply {
            add(albumId)
            index.candidates.forEach { c ->
                if (c.artistKey.isEmpty() || c.artistKey == page || c.artistKey == own.artistKey || c.artistKey == "various artists") add(c.albumId)
            }
        }
        return CassetteLikeness.moreLike(own.summed, index.candidates, excluding, minMatch = 0.75, perArtist = 1, limit = 8)
            .mapNotNull { index.albums[it] }
    }

    /** MORE LIKE *artist*: other artists whose music carries the same moods and sound, one record each. */
    @JvmStatic
    fun forArtist(context: Context, artistName: String?): List<AlbumID3> {
        val index = index(context) ?: return emptyList()
        return CassetteLikeness.moreLikeArtist(artistName ?: "", index.candidates).mapNotNull { index.albums[it] }
    }

    /** MORE LIKE *playlist*: records like its songs that are on this phone, none it already draws from. */
    @JvmStatic
    fun forSongs(context: Context, songIds: Collection<String>, albumIds: Collection<String>): List<AlbumID3> {
        val index = index(context) ?: return emptyList()
        val profile = HashMap<String, Double>()
        for (id in songIds.sorted()) {
            val song = index.songs[id] ?: continue
            CassetteLikeness.add(index.songTags.getValue(song.cassetteLocalId!!), profile)
        }
        return CassetteLikeness.moreLike(profile, index.candidates, albumIds.toSet(), minMatch = 0.7, perArtist = 1, limit = 8)
            .mapNotNull { index.albums[it] }
    }
}
