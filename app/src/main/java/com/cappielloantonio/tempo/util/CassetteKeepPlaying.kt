package com.cappielloantonio.tempo.util

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.service.MediaManager
import com.cappielloantonio.tempo.service.MediaService
import com.cappielloantonio.tempo.subsonic.models.Child
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * cassette: Keep playing on Android (founder 2026-11-02, iPhone KeepPlaying.swift).
 *
 * When the queue is about to run out, the cloud decides what carries on (the one
 * contract: monorepo apps/cassette/src/lib/keep-playing/contract.ts): a record
 * that started with a record carries on with another record, anything else with
 * songs. The phone keeps the switch (on by default), sends its queue as Cassette
 * song ids as the last song starts, and plays the answer when the queue runs out,
 * only ever music on this phone. Repeat wins: with repeat on, keep playing waits.
 * Offline, a record carries on with another record by the same artist here.
 */
object CassetteKeepPlaying {
    private const val TAG = "CassetteKeepPlaying"
    private const val PREF = "cassette_keep_playing"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val heading = MutableLiveData<String?>(null)

    /** What the queue started from (Cassette song ids), set when a queue starts. */
    @Volatile
    private var started: List<String> = emptyList()
    @Volatile
    private var askedFor: String? = null

    @JvmStatic
    fun isOn(): Boolean = App.getInstance().preferences.getBoolean(PREF, true)

    @JvmStatic
    fun setOn(on: Boolean) {
        App.getInstance().preferences.edit().putBoolean(PREF, on).apply()
    }

    /** The cloud's words for what comes next ("a record like it"), for the queue's THEN. */
    @JvmStatic
    fun heading(): LiveData<String?> = heading

    /** A queue started from these songs (an album, a playlist, a search). */
    @JvmStatic
    fun noteStarted(songs: List<Child>) {
        io.execute {
            started = localIds(songs.mapNotNull { it.id })
            askedFor = null
            heading.postValue(null)
        }
    }

    private fun localIds(subsonicIds: List<String>): List<String> {
        val dao = AppDatabase.getInstance().downloadDao()
        return subsonicIds.mapNotNull { id -> dao.getOne(id)?.cassetteLocalId }
    }

    /** The last song of the queue just started: ask what comes next, and queue it. */
    @JvmStatic
    fun onLastSongStarted(currentId: String?, repeatOff: Boolean) {
        if (!isOn() || !repeatOff || currentId == null || currentId == askedFor) return
        askedFor = currentId
        io.execute {
            try {
                val queue: List<Child> = QueueRepository().media ?: return@execute
                val queueIds = localIds(queue.mapNotNull { it.id })
                if (queueIds.isEmpty()) return@execute
                val pick = askCloud(queueIds) ?: fallback(queue)
                if (pick.isNullOrEmpty()) return@execute
                started = localIds(pick.mapNotNull { it.id })
                main.post { append(pick) }
            } catch (e: Exception) {
                Log.w(TAG, "keep playing failed", e)
            }
        }
    }

    private fun askCloud(queueIds: List<String>): List<Child>? {
        val bearer = Preferences.getCassetteBearerToken() ?: return null
        val body = JsonObject().apply {
            add("started", JsonArray().apply { (started.ifEmpty { queueIds }).forEach { add(it) } })
            add("seed", JsonArray().apply { queueIds.takeLast(50).forEach { add(it) } })
            add("exclude", JsonArray().apply { queueIds.forEach { add(it) } })
            addProperty("tz", TimeZone.getDefault().id)
        }
        val json = CassetteSyncApi.keepPlaying(bearer, body.toString()) ?: return null
        val answer = JsonParser.parseString(json).asJsonObject
        val dao = AppDatabase.getInstance().downloadDao()
        fun held(ids: List<String>): List<Download> = ids.chunked(500).flatMap { dao.getByCassetteLocalIds(it) }
            .filter { CassetteOnPhone.playable(it) }
        val words = answer.getAsJsonObject("heading")?.get("answer")?.asString
        return when (answer.get("kind")?.asString) {
            "album" -> {
                for (r in answer.getAsJsonArray("records") ?: JsonArray()) {
                    val ids = r.asJsonObject.getAsJsonArray("songs")?.map { it.asString } ?: continue
                    val songs = held(ids).sortedWith(compareBy({ it.discNumber ?: 1 }, { it.track ?: 0 }))
                    if (songs.isNotEmpty()) {
                        heading.postValue(words)
                        return songs
                    }
                }
                null
            }
            "songs" -> {
                val ids = answer.getAsJsonArray("songs")?.map { it.asString } ?: return null
                val byId = held(ids).associateBy { it.cassetteLocalId }
                ids.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }?.also { heading.postValue(words) }
            }
            else -> null
        }
    }

    /** Offline: a record carries on with another record by the same artist on this phone. */
    private fun fallback(queue: List<Child>): List<Child>? {
        val last = queue.lastOrNull() ?: return null
        val albumIds = queue.mapNotNull { it.albumId }.toSet()
        val artistId = last.artistId ?: return null
        val dao = AppDatabase.getInstance().downloadDao()
        val others = dao.getArtistSync(artistId).filter { it.albumId != null && it.albumId !in albumIds }
        val next = others.groupBy { it.albumId }.values.firstOrNull() ?: return null
        return next.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.track ?: 0 }))
    }

    private fun append(songs: List<Child>) {
        val context = App.getContext()
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, MediaService::class.java))).buildAsync()
        MediaManager.enqueue(future, songs, false)
    }
}
