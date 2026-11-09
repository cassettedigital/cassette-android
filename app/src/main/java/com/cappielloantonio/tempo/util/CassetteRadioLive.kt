package com.cappielloantonio.tempo.util

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * cassette: what a station is playing, in the player, the mini player, the lock screen and
 * the car at once (iPhone 96ad8657, 8dcd3464, f3a3c506). The song comes from the cloud
 * (`GET /api/radio/now-playing?station=`, public, asked every 30 seconds while a station
 * plays), else from the stream's own title split into song and artist; a title that is only
 * the station's name is dropped (BUG-758). The picture is the record we carry, else the
 * station's cover for the song, else the show's picture, else the station's own.
 *
 * The playing item is updated in place (same stream, so Media3 keeps it playing), which is
 * what every surface already reads; nothing else has to learn about radio.
 */
object CassetteRadioLive {
    const val SLUG = "radio_slug"
    const val STATION = "radio_station"
    const val STATION_IMAGE = "radio_station_image"
    const val WHEN = "radio_when"
    const val ALBUM_SLUG = "radio_album_slug"
    const val ALBUM_TITLE = "radio_album_title"
    private const val PREFIX = "cassette-radio-"

    data class Song(val title: String, val artist: String)

    class Live(
        val song: Song? = null,
        val show: String? = null,
        /** Who is on, when the station names them; the line under the show when no song is named. */
        val host: String? = null,
        val until: Long? = null,
        val next: String? = null,
        val nextStarts: Long? = null,
        val artwork: String? = null,
        val albumSlug: String? = null,
        val albumTitle: String? = null,
    )

    /** The station slug of a playing item, or null when it isn't one of the dial's stations. */
    @JvmStatic
    fun slugOf(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    private const val DANGLING = " \t\n\"'-–—|·:"

    /** Whether a stream title is only the station's own name ("The Current-"), separators aside. */
    @JvmStatic
    fun isStationName(streamTitle: String, stationName: String): Boolean {
        fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val left = squash(streamTitle.trim { it in DANGLING })
        return left.isNotEmpty() && left == squash(stationName)
    }

    /** "Artist - Title" into the song; a title with no separator is all title. */
    @JvmStatic
    fun parseStreamTitle(streamTitle: String): Song? {
        val cleaned = streamTitle.trim { it in DANGLING }
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(" - ")
        return if (parts.size >= 2) Song(parts.drop(1).joinToString(" - ").trim(), parts.first().trim())
        else Song(cleaned, "")
    }

    /**
     * "Until 1:00 PM, then MARTELO" / "Until 1:00 PM · 3:00 PM: Drive Time" / "Next: Waterloo
     * Sunset · The Kinks". A time already gone drops out (a feed can lag past a show's end).
     */
    @JvmStatic
    fun whenLine(live: Live, now: Long, clock: (Long) -> String): String? {
        val until = live.until?.takeIf { it > now }
        val nextStarts = live.nextStarts?.takeIf { it > now }
        val untilText = until?.let { "Until ${clock(it)}" }
        val next = live.next?.takeIf { it.isNotBlank() } ?: return untilText
        val backToBack = until != null && nextStarts != null && Math.abs(nextStarts - until) < 120_000
        val nextText = if (nextStarts != null && !backToBack) "${clock(nextStarts)}: $next"
        else if (untilText == null) "Next: $next" else "then $next"
        if (untilText == null) return nextText
        return if (backToBack || nextStarts == null) "$untilText, $nextText" else "$untilText · $nextText"
    }

    /** The cloud's answer (lib/radio/types.ts RadioNowPlaying), or null when it says nothing. */
    @JvmStatic
    fun parseNowPlaying(json: String): Live? {
        val o = JsonParser.parseString(json).asJsonObject
        val track = o.obj("track")
        val show = o.obj("show")
        val match = o.obj("match")
        val song = track?.let { t -> t.str("title")?.let { Song(it, t.str("artist") ?: "") } }
        val nextShow = o.getAsJsonArray("next")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val nextTrack = o.getAsJsonArray("nextTracks")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val next = nextShow?.str("name") ?: nextTrack?.let { t -> listOfNotNull(t.str("title"), t.str("artist")).joinToString(" · ").ifEmpty { null } }
        val nextStarts = (nextShow?.str("starts") ?: nextTrack?.str("starts"))?.let(::millis)
        if (song == null && show == null) return null
        return Live(
            song = song,
            show = show?.str("name"),
            host = show?.str("host"),
            until = show?.str("until")?.let(::millis),
            next = next,
            nextStarts = nextStarts,
            artwork = match?.str("coverFullUrl") ?: match?.str("coverUrl") ?: track?.str("coverUrl") ?: show?.str("image"),
            albumSlug = match?.str("albumSlug"),
            albumTitle = match?.str("title"),
        )
    }

    private fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private val client by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

    /** One read of a station's live info; null when the cloud can't say. Off the main thread. */
    @JvmStatic
    fun fetch(slug: String): Live? = try {
        client.newCall(Request.Builder().url(Cassette.BASE_URL + "/api/radio/now-playing?station=" + Uri.encode(slug)).build())
            .execute().use { r -> r.body?.string()?.takeIf { r.isSuccessful }?.let(::parseNowPlaying) }
    } catch (e: Exception) {
        Log.w("CassetteRadioLive", "now-playing failed", e)
        null
    }

    /** Keeps the playing station's item up to date. Attach to the ExoPlayer. */
    @OptIn(UnstableApi::class)
    class Follower(private val player: Player, private val clock: (Long) -> String) : Player.Listener {
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor()
        private var slug: String? = null
        private var stream: Song? = null
        private var cloud: Live? = null
        private var applied: String? = null
        private var generation = 0
        private val tick = Runnable { poll() }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val next = slugOf(mediaItem)
            // our own update in place comes back as a playlist change on the same station
            if (next != null && next == slug && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            follow(next)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) follow(slugOf(player.currentMediaItem), keep = true) else main.removeCallbacks(tick)
        }

        override fun onMetadata(metadata: Metadata) {
            if (slug == null) return
            for (i in 0 until metadata.length()) {
                val entry = metadata[i] as? IcyInfo ?: continue
                val title = entry.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                stream = if (isStationName(title, stationName())) null else parseStreamTitle(title)
                apply()
            }
        }

        private fun follow(next: String?, keep: Boolean = false) {
            main.removeCallbacks(tick)
            if (!(keep && next == slug)) {
                slug = next
                stream = null
                cloud = null
                applied = null
                generation++
            }
            if (slug != null) main.post(tick)
        }

        private fun poll() {
            val s = slug ?: return
            val gen = generation
            io.execute {
                val live = fetch(s)
                main.post {
                    if (gen != generation || slug != s) return@post
                    if (live != null) cloud = live
                    apply()
                    if (player.isPlaying) {
                        main.removeCallbacks(tick)
                        main.postDelayed(tick, 30_000)
                    }
                }
            }
        }

        private fun stationName(): String {
            val extras = player.currentMediaItem?.mediaMetadata?.extras
            return extras?.getString(STATION) ?: extras?.getString("title") ?: ""
        }

        private fun apply() {
            val item = player.currentMediaItem ?: return
            if (slugOf(item) != slug) return
            val old = item.mediaMetadata.extras ?: Bundle()
            val station = old.getString(STATION) ?: old.getString("title") ?: ""
            val stationImage = if (old.containsKey(STATION)) old.getString(STATION_IMAGE) else old.getString("coverArtId")
            val live = cloud
            val song = live?.song ?: stream
            val title = song?.title ?: live?.show ?: station
            // the song's artist; with no song, who is on (never the station twice where a host is named)
            val artist = song?.artist?.takeIf { it.isNotBlank() }
                ?: live?.host?.takeIf { song == null && it.isNotBlank() }
                ?: station
            val picture = live?.artwork ?: stationImage
            val then = live?.let { whenLine(it, System.currentTimeMillis(), clock) }
            val line = listOfNotNull(live?.show?.takeIf { song != null }, then).joinToString(" · ").ifEmpty { null }
            val signature = listOf(title, artist, picture, line, live?.albumSlug).joinToString("\u0001")
            if (signature == applied) return
            applied = signature

            val extras = Bundle(old).apply {
                putString(SLUG, slug)
                putString(STATION, station)
                putString(STATION_IMAGE, stationImage)
                putString("title", title)
                putString("artist", artist)
                putString("coverArtId", picture)
                putString(WHEN, line)
                putString(ALBUM_SLUG, live?.albumSlug)
                putString(ALBUM_TITLE, live?.albumTitle)
            }
            val meta = item.mediaMetadata.buildUpon()
                .setTitle(title)
                .setArtist(artist)
                .setStation(station)
                .setAlbumTitle(live?.albumTitle)
                .setSubtitle(listOfNotNull(station, line).joinToString(" · "))
                .setArtworkUri(picture?.let(Uri::parse))
                .setExtras(extras)
                .build()
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build())
        }
    }
}
