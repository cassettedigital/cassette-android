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
        /**
         * The line under the name on the dial, the Mac's kicker (apps/cassette/src/lib/radio/dial.ts):
         * the network unless it is the station's own name, its city, and "Live" for a station with a
         * schedule and hosts ("KUSC 91.5 · Los Angeles · Live").
         */
        val kicker: String = label,
        /** The network On now's meta line names ("KUSC 91.5"); empty when it is the station's own name. */
        val network: String = "",
        /** What it sounds like, in its own words: the dial's row says it under the kicker. */
        val description: String = "",
        /** It publishes what's on (a show or a song), so On now can say. */
        val live: Boolean = false,
        /** A station with a schedule and hosts, not a channel. */
        val isStation: Boolean = false,
        /** Its mark on the image ladder, `{rung}` to fill (lib/radio/picture-refs.ts); the bundled copy has none. */
        val imageSrc: String? = null,
        /** The stations most like it, by slug: its page's More like, drawn at once while the page's own read is out. */
        val similar: List<String> = emptyList(),
        /** It publishes the songs it played, so its page holds their rows open while they load. */
        val hasRecentPlays: Boolean = false,
    ) {
        /** Its mark for a frame `px` pixels across: the ladder's rung when the cloud sent one, else the plain address. */
        fun imageUrl(px: Int): String? =
            CassetteImageLadder.rungUrl(imageSrc, CassetteImageLadder.ladderSize(px)) ?: image

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
            val isStation = o.str("kind") == "station"
            val kicker = listOfNotNull(network, cities.firstOrNull(), "Live".takeIf { isStation }).joinToString(" · ")
            val live = o.get("hasNowPlaying")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
            val imageSrc = o.get("imageRef")?.takeIf { it.isJsonObject }?.asJsonObject?.str("src")
            val similar = o.getAsJsonArray("similar")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
            val recentPlays = o.get("hasRecentPlays")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
            Station(slug, name, label, o.str("section") ?: "", stream, o.str("homepage"), o.str("supportUrl"), o.str("image"), o.str("accent"), words,
                kicker = kicker, network = network ?: "", description = o.str("description") ?: "", live = live,
                isStation = isStation, imageSrc = imageSrc, similar = similar, hasRecentPlays = recentPlays)
        }.orEmpty()
        return out.sortedBy { order.indexOf(it.section).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /**
     * The dial as the Mac's Radio page lays it out (apps/cassette/src/lib/radio/dial.ts): the saved stations
     * first, newest saved first, then the rest in the dial's own order (by section, the registry's order
     * inside each). A section comes in where its first station does, so the sections holding saved
     * stations lead.
     */
    @JvmStatic
    fun dial(stations: List<Station>, saved: List<String>): List<Station> {
        val savedAt = HashMap<String, Int>()
        saved.forEachIndexed { i, slug -> savedAt.putIfAbsent(slug, i) }
        return stations.withIndex()
            .sortedWith(compareBy<IndexedValue<Station>>({ savedAt[it.value.slug] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** The day the web turns On now by: whole days since 1970, in UTC. */
    @JvmStatic
    fun today(): Long = Math.floorDiv(System.currentTimeMillis(), 86_400_000L)

    /**
     * A station's place in today's order: the same all day, different the next. The web's own small steady
     * hash (apps/cassette/src/lib/radio/daily-turn.ts) on the same day number, so On now leads with the same
     * live stations on the phone as on the Mac and the website.
     */
    @JvmStatic
    fun dailyTurn(day: Long, slug: String): Long {
        val s = "$day|$slug"
        var h = 2166136261L.toInt()
        var i = 0
        while (i < s.length) {
            // the web reads each character's first UTF-16 unit (`charCodeAt(0)`)
            h = h xor s[i].code
            h *= 16777619
            i += Character.charCount(s.codePointAt(i))
        }
        return h.toLong() and 0xFFFFFFFFL
    }

    /**
     * On now's stations (components/radio/RadioDial): the saved ones, newest saved first, then the live
     * stations that publish what's on and have a mark of their own, in today's turn so the same few don't
     * lead for weeks (the founder, 2026-11-07); six at most.
     */
    @JvmStatic
    @JvmOverloads
    fun onNowStations(stations: List<Station>, saved: List<String>, day: Long = today()): List<Station> {
        val bySlug = stations.associateBy { it.slug }
        val mine = saved.distinct().mapNotNull { bySlug[it] }
        val live = stations
            .filter { it.slug !in saved && it.live && it.isStation && !it.image.isNullOrEmpty() }
            .sortedBy { dailyTurn(day, it.slug) }
        return (mine + live).take(6)
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

    /** One song a station played (lib/radio/types.ts RadioPlay), with the record on Cassette it matched. */
    class Play @JvmOverloads constructor(
        val title: String, val artist: String, val playedAt: Long?, val cover: String?,
        val albumSlug: String?, val albumTitle: String?, val albumArtist: String?,
        /** The cover on the image ladder, `{rung}` to fill (lib/radio/picture-refs.ts), when the cloud sent one. */
        val coverSrc: String? = null,
    ) {
        /** Its cover for a frame `px` pixels across: the ladder's rung when the cloud sent one, else the plain address. */
        fun coverFor(px: Int): String? = CassetteImageLadder.rungUrl(coverSrc, CassetteImageLadder.ladderSize(px)) ?: cover
    }

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
        // the cover and its ladder reference come from the same place: the record we carry, else the station's
        val coverFrom = if (match?.str("coverUrl") != null) match else t
        return Play(title, t.str("artist") ?: "",
            t.str("playedAt")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            coverFrom?.str("coverUrl"),
            match?.str("albumSlug"), match?.str("title"), match?.str("artistName"),
            coverFrom?.get("coverRef")?.takeIf { it.isJsonObject }?.asJsonObject?.str("src"))
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
