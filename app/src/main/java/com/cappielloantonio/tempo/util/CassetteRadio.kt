package com.cappielloantonio.tempo.util

import android.content.Context
import android.util.Log
import com.cappielloantonio.tempo.subsonic.models.InternetRadioStation
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * cassette: the radio dial on Android (iPhone e0b0efab, 55190d42). The whole
 * registry on every phone, not only the stations a computer holds: a copy is
 * bundled with the app (assets/radio-catalog.json, the same file the iPhone
 * carries) and refreshed from the public `GET /api/radio/catalog` once a launch.
 * Each station plays straight from its own stream; no computer is involved.
 */
object CassetteRadio {
    private const val TAG = "CassetteRadio"
    private const val CACHE = "radio-catalog.json"

    class Station(
        val slug: String,
        val name: String,
        val label: String,
        val section: String,
        val streamUrl: String,
        val homepage: String?,
        val supportUrl: String?,
        val image: String?,
        val accent: String?,
        /** genres, moods, eras and cities, for Search */
        val keywords: List<String> = emptyList(),
    ) {
        fun toInternetRadioStation(): InternetRadioStation = InternetRadioStation().apply {
            id = "cassette-radio-$slug"
            name = this@Station.name
            streamUrl = this@Station.streamUrl
            homePageUrl = homepage
            label = this@Station.label
            image = this@Station.image
        }

        /** The station's own colour, for its word art when it has no picture; a warm neutral when it names none. */
        fun accentColor(): Int = runCatching { android.graphics.Color.parseColor(accent) }.getOrDefault(0xFF6B5A44.toInt())

        /** "kexp.org", for the menu's "Go to …". */
        fun site(): String? = homepage?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }

        /** Search's folded words, worked out once per station rather than on every keystroke. */
        internal val foldedName: String by lazy { CassetteSearch.fold(name) }
        internal val foldedKeywords: List<String> by lazy { keywords.map { CassetteSearch.fold(it) } }
    }

    /** The parsed dial and which copy it came from (the cloud's file's time, or 0 for the bundled one). */
    private class Parsed(val source: Long, val stations: List<Station>)

    @Volatile private var parsed: Parsed? = null

    @Volatile private var refreshed = false

    /**
     * The dial, in the registry's section order. Reads the cloud's copy when held, else the bundled one.
     * Parsed once and kept until the cloud's copy changes: Search asks on every keystroke and the car
     * on every station, and the file is 165 KB.
     */
    @JvmStatic
    fun stations(context: Context): List<Station> {
        val file = File(context.filesDir, CACHE)
        val source = runCatching { if (file.exists()) file.lastModified() else 0L }.getOrDefault(0L)
        parsed?.takeIf { it.source == source }?.let { return it.stations }
        val json = (if (source != 0L) runCatching { file.readText() }.getOrNull() else null)
            ?: context.assets.open("radio-catalog.json").bufferedReader().use { it.readText() }
        return parse(json).also { parsed = Parsed(source, it) }
    }

    /** Once a launch: the cloud's registry (public, no sign-in), kept for next time. */
    @JvmStatic
    fun refresh(context: Context) {
        if (refreshed) return
        refreshed = true
        Thread({
            try {
                val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(Cassette.BASE_URL + "/api/radio/catalog").build()).execute().use { r ->
                    val body = r.body?.string()
                    if (r.isSuccessful && body != null && parse(body).isNotEmpty()) {
                        File(context.filesDir, CACHE).writeText(body)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "catalog refresh failed", e)
            }
        }, "cassette-radio-catalog").start()
    }

    private fun parse(json: String): List<Station> {
        val root = JsonParser.parseString(json).asJsonObject
        val order = root.getAsJsonArray("sectionOrder")?.map { it.asString } ?: emptyList()
        val out = root.getAsJsonArray("stations")?.mapNotNull { el ->
            val o = el.asJsonObject
            val slug = o.str("slug") ?: return@mapNotNull null
            val stream = o.str("streamUrl") ?: return@mapNotNull null
            val name = o.str("name") ?: slug
            val cities = o.getAsJsonArray("cityLabels")?.mapNotNull { it.asString }.orEmpty()
            val network = o.str("sourceLabel")?.takeIf { it != name }
            val label = listOfNotNull(network, cities.firstOrNull()).joinToString(" · ")
            val words = listOf("genres", "moods", "eras", "cityLabels").flatMap { k ->
                o.getAsJsonArray(k)?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
            } + listOfNotNull(o.str("sourceLabel"), o.str("section"))
            Station(slug, name, label, o.str("section") ?: "", stream, o.str("homepage"), o.str("supportUrl"), o.str("image"), o.str("accent"), words)
        }.orEmpty()
        return out.sortedBy { order.indexOf(it.section).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** Stations matching what was typed, by name, network, city, mood, era or genre (iPhone e0b0efab). */
    @JvmStatic
    fun search(context: Context, query: String): List<Station> {
        val q = CassetteSearch.fold(query)
        if (q.isEmpty()) return emptyList()
        return stations(context).map { s ->
            val name = s.foldedName
            val rank = when {
                name.startsWith(q) -> 0
                name.contains(q) -> 1
                s.foldedKeywords.any { it.contains(q) } -> 2
                else -> 3
            }
            rank to s
        }.filter { it.first < 3 }.sortedBy { it.first }.map { it.second }.take(5)
    }

    fun interface OnNow {
        fun onNow(lines: Map<String, String>)
    }

    /**
     * What's on at every station whose feed the cloud reads (`GET /api/radio/on-now`,
     * public), as one line per slug: "Title · Artist", or the show. Read only while a
     * screen with stations is up.
     */
    @JvmStatic
    fun onNow(callback: OnNow) {
        Thread({
            val lines = HashMap<String, String>()
            try {
                val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(Cassette.BASE_URL + "/api/radio/on-now").build()).execute().use { r ->
                    val body = r.body?.string()
                    if (r.isSuccessful && body != null) {
                        val stations = JsonParser.parseString(body).asJsonObject.getAsJsonObject("stations")
                        stations?.entrySet()?.forEach { (slug, v) ->
                            if (!v.isJsonObject) return@forEach
                            val o = v.asJsonObject
                            val title = o.str("title")
                            val artist = o.str("artist")
                            val show = o.str("show")
                            val line = when {
                                title != null && artist != null -> "$title · $artist"
                                title != null -> title
                                show != null -> show
                                else -> null
                            }
                            if (line != null) lines[slug] = line
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "on-now failed", e)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback.onNow(lines) }
        }, "cassette-radio-on-now").start()
    }

    /** One song a station played (lib/radio/types.ts RadioPlay), with the record on Cassette it matched. */
    class Play(val title: String, val artist: String, val playedAt: Long?, val cover: String?,
               val albumSlug: String?, val albumTitle: String?, val albumArtist: String?)

    /** A station's page (lib/radio/station-page.ts): what's on, what came before, the stations like it. */
    class Page(val live: CassetteRadioLive.Live?, val now: Play?, val recent: List<Play>, val similar: List<String>)

    /** `GET /api/radio/station/<slug>` (public). Null when the cloud can't say. Off the main thread. */
    @JvmStatic
    fun page(slug: String): Page? = try {
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(Cassette.BASE_URL + "/api/radio/station/" + android.net.Uri.encode(slug)).build()).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful || body == null) null else parsePage(body)
        }
    } catch (e: Exception) {
        Log.w(TAG, "station page failed", e)
        null
    }

    @JvmStatic
    fun parsePage(json: String): Page {
        val o = JsonParser.parseString(json).asJsonObject
        val liveJson = o.get("live")?.takeIf { it.isJsonObject }?.asJsonObject
        val live = liveJson?.let { CassetteRadioLive.parseNowPlaying(it.toString()) }
        val now = liveJson?.get("track")?.takeIf { it.isJsonObject }?.asJsonObject?.let { t ->
            play(t, liveJson.get("match")?.takeIf { it.isJsonObject }?.asJsonObject)
        }
        val recent = o.getAsJsonArray("recent")?.mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.let { p -> play(p, p.get("match")?.takeIf { it.isJsonObject }?.asJsonObject) }
        }.orEmpty()
        val similar = o.getAsJsonArray("similar")?.mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.str("slug")
        }.orEmpty()
        return Page(live, now, recent, similar)
    }

    private fun play(t: JsonObject, match: JsonObject?): Play? {
        val title = t.str("title") ?: return null
        return Play(title, t.str("artist") ?: "",
            t.str("playedAt")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            match?.str("coverUrl") ?: t.str("coverUrl"),
            match?.str("albumSlug"), match?.str("title"), match?.str("artistName"))
    }

    /** The record on this phone a station's song came from, by its record's name and artist. Off the main thread. */
    @JvmStatic
    fun onPhone(albumTitle: String?, artist: String?): com.cappielloantonio.tempo.subsonic.models.AlbumID3? {
        if (albumTitle.isNullOrBlank()) return null
        val wantTitle = CassetteAlbumGroupKey.normalizeTitle(albumTitle)
        val wantArtist = artist?.let { CassetteAlbumGroupKey.normalizeArtist(it) }
        val songs = com.cappielloantonio.tempo.database.AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        val d = songs.firstOrNull { s ->
            s.album != null && CassetteAlbumGroupKey.normalizeTitle(s.album!!) == wantTitle &&
                (wantArtist == null || CassetteAlbumGroupKey.normalizeArtist(s.artist ?: "") == wantArtist)
        } ?: return null
        return com.cappielloantonio.tempo.subsonic.models.AlbumID3().apply {
            id = d.albumId; name = d.album; this.artist = d.artist; artistId = d.artistId; coverArtId = d.coverArtId; year = d.year ?: 0
        }
    }

    @JvmStatic
    fun bySlug(context: Context, slug: String?): Station? = slug?.let { s -> stations(context).firstOrNull { it.slug == s } }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
}
