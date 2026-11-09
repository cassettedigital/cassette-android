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

    class Summary(val hostname: String?, val lastSeenAt: Long?, val lastSyncAt: Long?, val albums: Int, val tracks: Int, val registered: Boolean)

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

    /** The computer's name from its address ("Michaels-MacBook-Pro.local" reads "Michaels MacBook Pro"). */
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
        if (Preferences.getCassetteBearerToken().isNullOrEmpty()) return "not paired"
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
    fun syncedLine(): String? {
        val at = summary?.lastSyncAt ?: return null
        val mins = ((System.currentTimeMillis() - at) / 60000).coerceAtLeast(0)
        return when {
            mins < 1 -> "Synced just now"
            mins < 60 -> "Synced $mins min ago"
            mins < 60 * 24 -> "Synced ${mins / 60} hr ago"
            else -> "Synced ${mins / (60 * 24)} days ago"
        }
    }
}
