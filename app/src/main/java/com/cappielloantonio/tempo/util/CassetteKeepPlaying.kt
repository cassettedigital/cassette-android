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
 * song ids as the last song starts, and holds the answer under THEN in the queue as one
 * card (iPhone KeepPlayingAlbumCell): Swap moves to the next record the cloud named, a tap
 * plays it now, and it plays on its own when the queue runs out (`takePick`, from the
 * service). Only ever music on this phone. Repeat wins: with repeat on, keep playing waits.
 * Offline, a record carries on with another record by the same artist here.
 */
object CassetteKeepPlaying {
    private const val TAG = "CassetteKeepPlaying"
    private const val PREF = "cassette_keep_playing"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val heading = MutableLiveData<String?>(null)

    /** What carries on: a record (or songs), held until the queue runs out. */
    class Pick(val songs: List<Child>, val isRecord: Boolean, val canSwap: Boolean) {
        val title: String? get() = if (isRecord) songs.firstOrNull()?.album else null
        val artist: String? get() = songs.firstOrNull()?.artist
        val coverArtId: String? get() = songs.firstOrNull()?.coverArtId
    }

    private val pick = MutableLiveData<Pick?>(null)
    /** Every record the cloud named that is on this phone, in its order; Swap walks it. */
    @Volatile private var records: List<List<Child>> = emptyList()
    @Volatile private var at = 0

    /** The held pick, for the queue's THEN card. */
    @JvmStatic
    fun pick(): LiveData<Pick?> = pick

    /** The next record the cloud named (round to the first after the last). */
    @JvmStatic
    fun swap() {
        val all = records
        if (all.size < 2) return
        at = (at + 1) % all.size
        pick.value = Pick(all[at], true, true)
    }

    /** The queue ran out (the service): what to play now, once; null when nothing is held. */
    @JvmStatic
    fun takePick(): List<Child>? {
        if (!isOn()) return null
        val p = pick.value ?: return null
        pick.value = null
        heading.value = null
        records = emptyList()
        noteStartedIds(p.songs)
        askedFor = null
        return p.songs
    }

    /**
     * Remember what the new queue starts from, off the main thread: it reads the phone's song
     * table, which Android refuses on the main thread. takePick runs in the player's own callback
     * and playNow from a tap, both on the main thread, and doing it there crashed the app the
     * moment a record ran out with a pick held (found on the Pixel 8, 2026-11-10).
     */
    private fun noteStartedIds(songs: List<Child>) {
        val ids = songs.mapNotNull { it.id }
        io.execute { started = localIds(ids) }
    }

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
        listeners.forEach { it.run() }
    }

    /**
     * The one press rule (the queue head's ∞ and the car's): while repeat is on, pressing turns
     * repeat off and keep playing on (repeat wins until then); otherwise it flips keep playing.
     */
    @JvmStatic
    fun press(player: androidx.media3.common.Player) {
        if (player.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            setOn(true)
        } else {
            setOn(!isOn())
        }
    }

    /** Whether keep playing acts now: on, and repeat off (what the car's key shows lit). */
    @JvmStatic
    fun isActive(repeatMode: Int): Boolean = isOn() && repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    /** Told when keep playing is switched, wherever from (the car's key follows the phone's). */
    @JvmStatic
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
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
            records = emptyList()
            pick.postValue(null)
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
                val found = askCloud(queueIds) ?: fallback(queue)?.also { records = listOf(it) }
                if (found.isNullOrEmpty()) return@execute
                at = 0
                val isRecord = records.isNotEmpty() && records[0] === found
                pick.postValue(Pick(found, isRecord, isRecord && records.size > 1))
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
        records = emptyList()
        return when (answer.get("kind")?.asString) {
            "album" -> {
                // every record it named that is here, so Swap can walk them
                val found = (answer.getAsJsonArray("records") ?: JsonArray()).mapNotNull { r ->
                    val ids = r.asJsonObject.getAsJsonArray("songs")?.map { it.asString } ?: return@mapNotNull null
                    held(ids).sortedWith(compareBy({ it.discNumber ?: 1 }, { it.track ?: 0 })).takeIf { it.isNotEmpty() }
                }
                if (found.isEmpty()) return null
                records = found
                heading.postValue(words)
                found.first()
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

    /** A tap on the THEN card: play the pick now, after what is playing. */
    @JvmStatic
    fun playNow() {
        val songs = pick.value?.songs ?: return
        pick.value = null
        heading.value = null
        records = emptyList()
        noteStartedIds(songs)
        askedFor = null
        val context = App.getContext()
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, MediaService::class.java))).buildAsync()
        io.execute {
            val kept = CassetteOnPhone.keep(songs)
            main.post { MediaManager.startQueue(future, kept, 0) }
        }
    }
}
