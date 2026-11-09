package com.cappielloantonio.tempo.util

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * cassette: ON CASSETTE in Search (iPhone SearchVC CatalogHit, founder 2026-10-31 Q17):
 * artists, albums and songs from Cassette's catalog by name (`GET /api/search`, the
 * website's own type-ahead ranking), shown under the phone's results and plainly not from
 * the library. Each opens its page on Cassette. Pictures come only from the image ladder (each hit's
 * `image.src`, a `{rung}` template); a hit with none shows the empty square (BUG-964: the old
 * `coverArtUrl` sent the loader straight to archive.org for a cover with no grid size).
 */
object CassetteCatalog {
    enum class Kind { ARTIST, ALBUM, SONG }

    class Hit(val kind: Kind, val title: String, val artistName: String, val imageSrc: String?,
              val artistSlug: String?, val albumSlug: String?) {
        /** The picture at the rung that holds [px] pixels, or null with no ladder picture. */
        fun imageUrl(px: Int): String? = CassetteImageLadder.rungUrl(imageSrc, CassetteImageLadder.ladderSize(px))

        /** Its page on Cassette: the artist's, or the album's (a song opens its album). */
        fun pageUrl(): String? = when (kind) {
            Kind.ARTIST -> artistSlug?.let { "https://cassette.digital/artist/$it" }
            else -> albumSlug?.let { "https://cassette.digital/album/$it" }
        }
    }

    private val client by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build() }

    /** The best few of each kind (2 artists, 4 albums, 4 songs) in the site's order; empty on any failure. */
    @JvmStatic
    fun search(query: String): List<Hit> = try {
        val url = (Cassette.BASE_URL + "/api/search").toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(120)).addQueryParameter("perPage", "6").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful || body == null) emptyList() else parse(body)
        }
    } catch (e: Exception) {
        Log.w("CassetteCatalog", "catalog search failed", e)
        emptyList()
    }

    @JvmStatic
    fun parse(json: String): List<Hit> {
        val o = JsonParser.parseString(json).asJsonObject
        fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
        fun JsonObject.image() = get("image")?.takeIf { it.isJsonObject }?.asJsonObject?.s("src")
        fun arr(k: String) = o.getAsJsonArray(k)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
        val groups = mapOf(
            "artists" to arr("artists").mapNotNull { a ->
                Hit(Kind.ARTIST, a.s("name") ?: return@mapNotNull null, a.s("name")!!, a.image(), a.s("slug"), null)
            }.take(2),
            "albums" to arr("albums").filter { it.s("slug") != null }.mapNotNull { a ->
                Hit(Kind.ALBUM, a.s("title") ?: return@mapNotNull null, a.s("artistName") ?: "", a.image(), a.s("artistSlug"), a.s("slug"))
            }.take(4),
            "songs" to arr("songs").filter { it.s("albumSlug") != null }.mapNotNull { a ->
                Hit(Kind.SONG, a.s("title") ?: return@mapNotNull null, a.s("artistName") ?: "", a.image(), a.s("artistSlug"), a.s("albumSlug"))
            }.take(4),
        )
        val order = o.getAsJsonArray("order")?.mapNotNull { it.asString }?.filter { groups.containsKey(it) }.orEmpty()
        return (order.ifEmpty { listOf("artists", "albums", "songs") }).flatMap { groups[it].orEmpty() }
    }
}
