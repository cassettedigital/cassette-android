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
    ) {
        fun toInternetRadioStation(): InternetRadioStation = InternetRadioStation().apply {
            id = "cassette-radio-$slug"
            name = this@Station.name
            streamUrl = this@Station.streamUrl
            homePageUrl = homepage
            label = this@Station.label
            image = this@Station.image
        }

        /** "kexp.org", for the menu's "Go to …". */
        fun site(): String? = homepage?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }
    }

    @Volatile private var refreshed = false

    /** The dial, in the registry's section order. Reads the cloud's copy when held, else the bundled one. */
    @JvmStatic
    fun stations(context: Context): List<Station> {
        val json = runCatching { File(context.filesDir, CACHE).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: context.assets.open("radio-catalog.json").bufferedReader().use { it.readText() }
        return parse(json)
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
            Station(slug, name, label, o.str("section") ?: "", stream, o.str("homepage"), o.str("supportUrl"), o.str("image"), o.str("accent"))
        }.orEmpty()
        return out.sortedBy { order.indexOf(it.section).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
}
