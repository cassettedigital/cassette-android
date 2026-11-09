package com.cappielloantonio.tempo.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.subsonic.models.Playlist
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * cassette: One Home on Android (founder call 2026-11-09: Android is in One Home).
 *
 * The cloud makes Home once, for every device (`GET /api/sync/home`, shape in
 * apps/cassette/src/lib/home/types.ts): rows, order, names and contents, from
 * every device's plays. This phone draws it, showing only what it holds: each
 * record found by any of its songs on the phone (Cassette song ids, which every
 * download carries), each artist through their songs, each made-for-you playlist
 * by its songs here, each playlist by its cloud id. A row the phone can't fill
 * is hidden. The last answer is kept on disk so Home opens at once and offline.
 * The Android twin of iOS's HomeStore + CloudHome.
 *
 * The cloud's recipes run in the cloud; nothing here re-ranks anything.
 */
object CassetteHome {
    private const val TAG = "CassetteHome"
    private const val FILE = "cassette_home.json"
    private const val VERSION = 1
    private const val MIN_GAP_MS = 60_000L
    /** Every made-for-you playlist but Lately needs this many songs here (iOS madeMixMinSongs). */
    private const val MIX_MIN_SONGS = 15

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastAsk = 0L

    // ---- the answer, as the cloud sends it (camelCase JSON) ----

    data class Record(val key: String?, val title: String?, val artist: String?, val year: Int?, val songs: List<String>?)
    data class Artist(val key: String?, val name: String?, val songs: List<String>?)
    data class Cover(val key: String?, val variant: String?, val url: String?)
    data class Mix(val id: String?, val kind: String?, val title: String?, val subtitle: String?, val color: String?, val cover: Cover?, val songs: List<String>?, val artists: Int?)
    data class HomePlaylist(val id: String?, val name: String?, val songs: Int?, val cover: String?, val lastPlayedAt: String?)
    data class Shelf(val id: String?, val kind: String?, val shout: String?, val answer: String?, val records: List<Record>?, val mix: Mix?, val onHome: Boolean?)
    data class Row(
        val id: String?, val kind: String?, val shout: String?, val answer: String?,
        val record: Record?, val song: String?, val playlist: String?,
        val records: List<Record>?, val mixes: List<Mix>?, val playlists: List<HomePlaylist>?,
        val stations: List<String>?, val shelf: Shelf?, val artists: List<Artist>?, val count: Int?,
    )
    data class Answer(val version: Int?, val madeAt: String?, val day: String?, val slot: String?, val madeOn: String?, val rows: List<Row>?, val shelves: List<Shelf>?)

    // ---- what the phone draws ----

    class DrawnMix(val id: String, val kind: String, val title: String, val subtitle: String?, val coverUrl: String?, val songs: List<Child>)

    class DrawnRow(
        val id: String,
        val kind: String,
        val shout: String,
        val answer: String?,
        val albums: List<AlbumID3> = emptyList(),
        val artists: List<ArtistID3> = emptyList(),
        val mixes: List<DrawnMix> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val stations: List<CassetteRadio.Station> = emptyList(),
        /** Resume: the record's song to pick up from. */
        val song: Child? = null,
        val resumePlaylistId: String? = null,
    ) {
        val isFilled: Boolean
            get() = when (kind) {
                "mixes" -> mixes.isNotEmpty()
                "artists" -> artists.isNotEmpty()
                "playlists" -> playlists.isNotEmpty()
                "stations" -> stations.isNotEmpty()
                "resume" -> song != null || resumePlaylistId != null
                "reach" -> albums.size >= 2 || (id.startsWith("deep-dive") && albums.isNotEmpty())
                else -> albums.isNotEmpty()
            }
    }

    fun interface Callback {
        fun onRows(rows: List<DrawnRow>)
    }

    /** The made-for-you playlists last drawn, by id, for their page. */
    private val mixes = HashMap<String, DrawnMix>()

    @JvmStatic
    fun mix(id: String?): DrawnMix? = id?.let { mixes[it] }

    /**
     * Home's rows: the held answer at once (when there is one), then the cloud's
     * fresh one when it lands. Asks the cloud at most once a minute.
     */
    @JvmStatic
    fun load(context: Context, callback: Callback) {
        val app = context.applicationContext
        io.execute {
            readHeld(app)?.let { held ->
                val rows = resolve(held, playlistsOnPhone(app), dial(app))
                main.post { callback.onRows(rows) }
            }
            val now = System.currentTimeMillis()
            if (now - lastAsk < MIN_GAP_MS) return@execute
            lastAsk = now
            val fresh = ask(app) ?: return@execute
            val rows = resolve(fresh, refreshPlaylistsOnPhone(app), dial(app))
            main.post { callback.onRows(rows) }
        }
    }

    private fun ask(context: Context): Answer? {
        val bearer = Preferences.getCassetteBearerToken()
        if (bearer.isNullOrEmpty()) return null
        return try {
            val json = CassetteSyncApi.getHome(bearer, TimeZone.getDefault().id) ?: return null
            val answer = Gson().fromJson(json, Answer::class.java)
            if ((answer.version ?: 0) > VERSION) return null // a shape this build can't read
            File(context.filesDir, FILE).writeText(json)
            answer
        } catch (e: Exception) {
            Log.w(TAG, "home fetch failed", e)
            null
        }
    }

    private fun readHeld(context: Context): Answer? = try {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) null else Gson().fromJson(file.readText(), Answer::class.java)
            ?.takeIf { (it.version ?: 0) <= VERSION }
    } catch (e: Exception) {
        null
    }

    // ---- playlists with at least one song on this phone ----

    private var playlistsHere: Set<String>? = null

    private fun playlistsOnPhone(context: Context): Set<String>? = playlistsHere

    private fun refreshPlaylistsOnPhone(context: Context): Set<String>? {
        val bearer = Preferences.getCassetteBearerToken() ?: return playlistsHere
        return try {
            val dao = AppDatabase.getInstance().downloadDao()
            val held = HashSet<String>()
            for (p in CassetteSyncApi.listPlaylists(bearer, true)) {
                val ids = p.items.mapNotNull { it.cassetteLocalId }
                if (ids.isNotEmpty() && byLocalId(dao, ids).isNotEmpty()) held.add(p.id)
            }
            playlistsHere = held
            held
        } catch (e: Exception) {
            playlistsHere
        }
    }

    // ---- finding the answer on the phone ----

    private fun byLocalId(dao: com.cappielloantonio.tempo.database.dao.DownloadDao, ids: Collection<String>): Map<String, Download> {
        val out = HashMap<String, Download>()
        ids.distinct().chunked(500).forEach { chunk ->
            dao.getByCassetteLocalIds(chunk).forEach { d -> d.cassetteLocalId?.let { out[it] = d } }
        }
        return out
    }

    private fun dial(context: Context): Map<String, CassetteRadio.Station> =
        runCatching { CassetteRadio.stations(context).associateBy { it.slug } }.getOrDefault(emptyMap())

    private fun resolve(answer: Answer, playlistsHere: Set<String>?, dial: Map<String, CassetteRadio.Station>): List<DrawnRow> {
        val dao = AppDatabase.getInstance().downloadDao()
        val wanted = HashSet<String>()
        fun want(r: Record?) { r?.songs?.let { wanted.addAll(it) } }
        fun wantMix(m: Mix?) { m?.songs?.let { wanted.addAll(it) } }
        answer.rows.orEmpty().forEach { row ->
            want(row.record)
            row.records?.forEach { want(it) }
            row.mixes?.forEach { wantMix(it) }
            row.shelf?.records?.forEach { want(it) }
            wantMix(row.shelf?.mix)
            row.artists?.forEach { a -> a.songs?.let { wanted.addAll(it) } }
        }
        val held = byLocalId(dao, wanted)

        fun album(r: Record): AlbumID3? {
            for (local in r.songs.orEmpty()) {
                val d = held[local] ?: continue
                val id = d.albumId ?: continue
                return AlbumID3().apply {
                    this.id = id
                    name = d.album ?: r.title
                    artist = d.artist ?: r.artist
                    artistId = d.artistId
                    coverArtId = d.coverArtId
                    year = d.year ?: r.year ?: 0
                }
            }
            return null
        }
        fun albums(rs: List<Record>?): List<AlbumID3> {
            val seen = HashSet<String>()
            return rs.orEmpty().mapNotNull { album(it) }.filter { seen.add(it.id ?: "") }
        }
        fun mix(m: Mix): DrawnMix? {
            val songs = m.songs.orEmpty().mapNotNull { held[it] }.filter { CassetteOnPhone.playable(it) }
            val kind = m.kind ?: return null
            if (songs.isEmpty() || (kind != "lately" && songs.size < MIX_MIN_SONGS)) return null
            return DrawnMix(m.id ?: kind, kind, m.title ?: "", m.subtitle, m.cover?.url, songs)
                .also { synchronized(mixes) { mixes[it.id] = it } }
        }
        fun artists(list: List<Artist>?): List<ArtistID3> {
            val seen = HashSet<String>()
            return list.orEmpty().mapNotNull { a ->
                val d = a.songs.orEmpty().firstNotNullOfOrNull { held[it] } ?: return@mapNotNull null
                val id = d.artistId ?: return@mapNotNull null
                ArtistID3().apply {
                    this.id = id
                    name = d.artist ?: a.name
                    coverArtId = "ar-$id"
                }
            }.filter { seen.add(it.id ?: "") }
        }
        fun playlists(list: List<HomePlaylist>?): List<Playlist> = list.orEmpty().mapNotNull { p ->
            val id = p.id ?: return@mapNotNull null
            if (playlistsHere != null && !playlistsHere.contains(id)) return@mapNotNull null
            Playlist(id).apply {
                name = p.name
                songCount = p.songs ?: 0
                coverArtId = p.cover?.let { if (it.startsWith("/")) "https://cassette.digital$it" else it }
            }
        }

        val out = ArrayList<DrawnRow>()
        for (row in answer.rows.orEmpty()) {
            val id = row.id ?: continue
            val shout = row.shout ?: continue
            val drawn = when (row.kind) {
                "resume" -> {
                    val record = row.record?.let { album(it) }
                    val song = row.song?.let { held[it] } ?: row.record?.songs?.firstNotNullOfOrNull { held[it] }
                    DrawnRow(id, "resume", shout, row.answer,
                        albums = listOfNotNull(record), song = if (record != null) song else null,
                        resumePlaylistId = row.playlist?.takeIf { playlistsHere?.contains(it) == true })
                }
                "records" -> DrawnRow(id, "records", shout, row.answer, albums = albums(row.records))
                "mixes" -> DrawnRow(id, "mixes", shout, row.answer, mixes = row.mixes.orEmpty().mapNotNull { mix(it) })
                "playlists" -> DrawnRow(id, "playlists", shout, row.answer, playlists = playlists(row.playlists))
                "artists" -> DrawnRow(id, "artists", shout, row.answer, artists = artists(row.artists))
                "reach" -> {
                    val shelf = row.shelf ?: continue
                    DrawnRow(shelf.id ?: id, "reach", shelf.shout ?: shout, shelf.answer ?: row.answer,
                        albums = albums(shelf.records),
                        mixes = listOfNotNull(shelf.mix?.let { mix(it) }))
                }
                // the Radio row: the cloud's stations, found on the dial by slug
                "stations" -> DrawnRow(id, "stations", shout, row.answer,
                    stations = row.stations.orEmpty().mapNotNull { dial[it] }.distinctBy { it.slug })
                // all-shelves waits for its page
                else -> continue
            }
            out.add(drawn)
        }
        return out
    }
}
