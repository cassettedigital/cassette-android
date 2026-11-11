package com.cappielloantonio.tempo.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/**
 * cassette: your computer, as the phone can truthfully say it (iPhone
 * CassetteSyncAPI.macState / heartbeatState, ported). One word: in sync,
 * syncing or out of reach, read from the hub's heartbeat the cloud keeps
 * (`GET /api/player/devices/mac`), asked when Settings or the account sheet
 * opens, never polled in the background. Never "in sync" without a heartbeat.
 * Never "Mac" as the general word (founder, 2026-10-26): "your computer".
 */
object CassetteComputer {
    private const val TAG = "CassetteComputer"
    private const val HEARTBEAT_MS = 15 * 60 * 1000L
    private const val TRUST_MS = 30 * 60 * 1000L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** A record newly in the library on the computer (the summary's recentItems), newest first. */
    class Recent(val groupKey: String, val albumName: String, val artistName: String)

    class Summary(val hostname: String?, val lastSeenAt: Long?, val lastSyncAt: Long?, val albums: Int, val tracks: Int, val registered: Boolean,
                  val recent: List<Recent> = emptyList())

    /** The last summary read, for a page that opens before a fresh one lands. */
    @JvmStatic
    fun last(): Summary? = summary

    @Volatile private var summary: Summary? = null
    @Volatile private var fetchedAt: Long = 0

    fun interface Callback {
        fun onSummary(summary: Summary?)
    }

    /** Ask the cloud for the computer's heartbeat now (one call), then [callback] on the main thread. */
    @JvmStatic
    fun refresh(callback: Callback) {
        io.execute {
            val bearer = Preferences.getCassetteBearerToken()
            if (!bearer.isNullOrEmpty()) {
                try {
                    val json = CassetteSyncApi.getComputerSummary(bearer)
                    if (json != null) {
                        val o = JsonParser.parseString(json).asJsonObject
                        val mac = o.get("mac")?.takeIf { it.isJsonObject }?.asJsonObject
                        fun time(key: String): Long? = mac?.get(key)?.takeIf { !it.isJsonNull }?.asString
                            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        summary = Summary(
                            mac?.get("lanHostname")?.takeIf { !it.isJsonNull }?.asString,
                            time("lastSeenAt"), time("lastSyncAt"),
                            o.get("albums")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                            o.get("tracks")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                            mac != null,
                            o.getAsJsonArray("recentItems")?.mapNotNull { e ->
                                val r = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                                fun s(k: String) = r.get(k)?.takeIf { !it.isJsonNull }?.asString
                                Recent(s("groupKey") ?: return@mapNotNull null, s("albumName") ?: "", s("artistName") ?: "")
                            }.orEmpty(),
                        )
                        fetchedAt = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "computer summary failed", e)
                }
            }
            val s = summary
            main.post { callback.onSummary(s) }
        }
    }

    /** The computer's name from its address ("Studio-iMac.local" reads "Studio iMac"). */
    @JvmStatic
    fun name(): String {
        val host = summary?.hostname ?: hostOf(Preferences.getServer())
        if (host.isNullOrEmpty()) return "Your computer"
        val isIp = host.split('.').size == 4 && host.all { it.isDigit() || it == '.' }
        if (isIp || host.contains(':')) return "Your computer"
        var n = host
        if (n.lowercase(Locale.ROOT).endsWith(".local")) n = n.dropLast(6)
        n = n.replace('-', ' ').trim()
        return n.ifEmpty { "Your computer" }
    }

    private fun hostOf(server: String?): String? = server?.let {
        runCatching { java.net.URI(it).host }.getOrNull()
    }

    /** One word for now, or null when nothing true can be said yet. */
    @JvmStatic
    fun state(): String? {
        if (Preferences.getCassetteBearerToken().isNullOrEmpty() || Cassette.isNoComputer(Preferences.getServer())) return "not paired"
        val sync = CassetteSyncStatus.live().value
        if (sync != null && sync.active) return if (sync.waitingForWifi) "out of reach" else "syncing"
        val s = summary ?: return null
        val now = System.currentTimeMillis()
        val recent = now - fetchedAt < TRUST_MS
        val seen = s.lastSeenAt ?: return if (recent) "out of reach" else null
        if (now - seen < HEARTBEAT_MS) return "in sync"
        if (recent && fetchedAt - seen >= HEARTBEAT_MS) return "out of reach"
        return null
    }

    /** "Synced 5 min ago", from the computer's last full library sync. */
    @JvmStatic
    fun syncedLine(): String? = syncedAgo()?.let { "Synced $it" }

    /** "5 min ago", for the computer page's Last synced. */
    @JvmStatic
    fun syncedAgo(): String? {
        val at = summary?.lastSyncAt ?: return null
        val mins = ((System.currentTimeMillis() - at) / 60000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} hr ago"
            else -> "${mins / (60 * 24)} days ago"
        }
    }

    /** A laptop by its name ("MacBook", "Laptop"), else a desktop: the glyph beside "Your computer". */
    @JvmStatic
    fun isLaptop(): Boolean = name().lowercase(Locale.ROOT).let { it.contains("book") || it.contains("laptop") }

    /**
     * The records newly in the library that are on this phone, newest first (iPhone
     * DevicePagesModel.rebuildNewOnMac): matched by the cloud's album key, else by name.
     * Off the main thread.
     */
    @JvmStatic
    fun newOnPhone(limit: Int): List<com.cappielloantonio.tempo.subsonic.models.AlbumID3> {
        val recent = summary?.recent.orEmpty()
        if (recent.isEmpty()) return emptyList()
        val held = com.cappielloantonio.tempo.database.AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        val fold = CassetteRecords.foldMap(held)
        val byAlbum = held.filter { it.albumId != null }.groupBy { CassetteRecords.recordId(fold, it.albumId)!! }
        val byKey = HashMap<String, com.cappielloantonio.tempo.subsonic.models.AlbumID3>()
        val byName = HashMap<String, com.cappielloantonio.tempo.subsonic.models.AlbumID3>()
        byAlbum.forEach { (albumId, songs) ->
            val first = CassetteRecords.lead(songs, albumId)
            val artist = CassetteRecords.albumArtist(songs)
            val album = com.cappielloantonio.tempo.subsonic.models.AlbumID3().apply {
                id = albumId; name = first.album; this.artist = artist.ifEmpty { first.artist }
                artistId = first.artistId; coverArtId = first.coverArtId; year = first.year ?: 0
            }
            byKey[CassetteAlbumGroupKey.keyFromTracks(songs.mapNotNull { it.artist }, first.album ?: "")] = album
            byName[CassetteAlbumGroupKey.normalizeTitle(first.album ?: "") + "\u0000" + CassetteAlbumGroupKey.normalizeArtist(album.artist ?: "")] = album
        }
        val out = LinkedHashMap<String, com.cappielloantonio.tempo.subsonic.models.AlbumID3>()
        for (r in recent) {
            // stored keys carry U+001F where the phone's key has a NUL (see CassetteGenres)
            val album = byKey[r.groupKey.replace('\u001f', '\u0000')]
                ?: byName[CassetteAlbumGroupKey.normalizeTitle(r.albumName) + "\u0000" + CassetteAlbumGroupKey.normalizeArtist(r.artistName)]
                ?: continue
            out.putIfAbsent(album.id!!, album)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }
}
