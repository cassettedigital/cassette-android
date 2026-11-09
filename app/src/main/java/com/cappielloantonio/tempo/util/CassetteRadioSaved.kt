package com.cappielloantonio.tempo.util

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * cassette: your stations (iPhone RadioSaved, 535f7b63). One save, the web's: read from
 * `GET /api/radio/saved`, written through `POST` / `DELETE /api/follows` (type radio_station),
 * so a save on the phone is a save on the site. Kept on the phone, latest first, so the
 * dial, the player and a station's page know at once; forgotten on sign-out.
 */
object CassetteRadioSaved {
    private const val TAG = "CassetteRadioSaved"
    private const val FILE = "radio-saved.json"
    private val io = Executors.newSingleThreadExecutor()
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val live = MutableLiveData<List<String>>()

    /** The saved slugs, latest first, as they change. */
    @JvmStatic
    fun saved(context: Context): LiveData<List<String>> {
        if (live.value == null) live.value = read(context.applicationContext)
        return live
    }

    @JvmStatic
    fun isSaved(context: Context, slug: String): Boolean = saved(context).value.orEmpty().contains(slug)

    private fun read(context: Context): List<String> = try {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) emptyList() else JsonParser.parseString(f.readText()).asJsonArray.map { it.asString }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(context: Context, slugs: List<String>) {
        val arr = JsonArray()
        slugs.forEach { arr.add(it) }
        runCatching { File(context.filesDir, FILE).writeText(arr.toString()) }
        live.postValue(slugs)
    }

    /** The account's list from the cloud, when signed in. */
    @JvmStatic
    fun refresh(context: Context) {
        val app = context.applicationContext
        val bearer = Preferences.getCassetteBearerToken() ?: return
        io.execute {
            try {
                val req = Request.Builder().url(Cassette.BASE_URL + "/api/radio/saved")
                    .header("Authorization", "Bearer $bearer").build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 401) CassetteSession.cloudRefusedToken()
                    val body = r.body?.string()
                    if (!r.isSuccessful || body == null) return@use
                    val o = JsonParser.parseString(body).asJsonObject
                    // an older deploy never said signedIn: keep the phone's own copy then
                    if (o.get("signedIn")?.asBoolean != true) return@use
                    write(app, o.getAsJsonArray("saved")?.map { it.asString }.orEmpty())
                }
            } catch (e: Exception) {
                Log.w(TAG, "saved stations read failed", e)
            }
        }
    }

    fun interface Done {
        fun done(ok: Boolean)
    }

    /** Save or unsave at once on the phone; undone if the cloud says no. [done] runs on the main thread. */
    @JvmStatic
    fun set(context: Context, slug: String, save: Boolean, done: Done?) {
        val app = context.applicationContext
        val before = saved(app).value.orEmpty()
        val after = if (save) listOf(slug) + before.filter { it != slug } else before.filter { it != slug }
        write(app, after)
        val bearer = Preferences.getCassetteBearerToken()
        io.execute {
            var ok = false
            if (bearer != null) try {
                val body = JsonObject().apply {
                    addProperty("type", "radio_station")
                    addProperty("id", slug)
                    addProperty("source", "android")
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder().url(Cassette.BASE_URL + "/api/follows")
                    .header("Authorization", "Bearer $bearer")
                    .method(if (save) "POST" else "DELETE", body).build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 401) CassetteSession.cloudRefusedToken()
                    ok = r.isSuccessful
                }
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
            }
            if (!ok) write(app, before)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done?.done(ok) }
        }
    }

    /** Sign-out: these are the account's. */
    @JvmStatic
    fun forget(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
        live.postValue(emptyList())
    }
}
