package com.cappielloantonio.tempo.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.cappielloantonio.tempo.R
import org.json.JSONObject

/**
 * cassette (security design rule 3, BUG-938, the phone half of BUG-859): a sign-in only lands in
 * the app that asked for it.
 *
 * Sign in opens the cloud's pairing page in a Custom Tab over the app (the iPhone's sheet,
 * Android's way) with a fresh one-time code and a fresh key. Both wait here, sealed to this phone,
 * for 30 minutes (as long as the computer app waits) and are used once. What comes back to
 * `cassette://auth/callback` is taken only when its code is the one this phone sent; then the
 * sealed sign-in is opened with this phone's key. Anything else, a link from another app or a web
 * page, or one that arrives when this phone isn't signing in, is dropped and changes nothing.
 *
 * Until the cloud seals for phones too, it sends the sign-in plain beside the code; that is
 * accepted only with the matching code, which already stops a planted link. Once the cloud seals
 * for phones, [decide]'s plain-token branch can go.
 */
object CassettePairing {
    private const val PENDING = "cassette_pending_pairing"
    const val WAIT_MS = 30L * 60 * 1000

    enum class Outcome { SIGN_IN, NOT_ASKED, EXPIRED, FAILED }

    class Result(@JvmField val outcome: Outcome, @JvmField val token: String?)

    /** A started sign-in, as kept while the person is in the Custom Tab. */
    class Pending(val state: String, val privateKey: ByteArray, val publicKey: ByteArray, val startedAt: Long)

    /** Start a sign-in: remember its code and key, then open the pairing page over the app. */
    @JvmStatic
    fun start(context: Context) {
        val key = CassetteSeal.newKeyPair()
        val state = CassetteSeal.newState()
        CassetteSecureStore.setString(
            PENDING,
            JSONObject()
                .put("state", state)
                .put("private", CassetteSeal.encode(key.privateKey))
                .put("public", key.publicText)
                .put("at", System.currentTimeMillis())
                .toString()
        )
        val url = Uri.parse(Cassette.pairingUrl(state, key.publicText))
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.cassette_pairing_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    /** What came back to `cassette://auth/callback`: the sign-in, or why it was dropped. */
    @JvmStatic
    fun accept(uri: Uri?): Result {
        val pending = readPending()
        val result = decide(
            pending,
            uri?.getQueryParameter("state"),
            uri?.getQueryParameter("sealed"),
            uri?.getQueryParameter("token"),
            System.currentTimeMillis()
        )
        // used once: an answer to this sign-in (or its expiry) ends it; a stray link leaves it waiting
        if (result.outcome != Outcome.NOT_ASKED) forget()
        return result
    }

    /** Signing out or signing in again: no sign-in is waiting any more. */
    @JvmStatic
    fun forget() {
        CassetteSecureStore.setString(PENDING, null)
    }

    /** The rule itself, with no Android in it, so it can be tested on its own. */
    @JvmStatic
    fun decide(pending: Pending?, state: String?, sealed: String?, token: String?, now: Long): Result {
        if (pending == null) return Result(Outcome.NOT_ASKED, null)
        if (!CassetteSeal.sameCode(pending.state, state)) return Result(Outcome.NOT_ASKED, null)
        if (now - pending.startedAt > WAIT_MS || now < pending.startedAt) return Result(Outcome.EXPIRED, null)
        if (!sealed.isNullOrEmpty()) {
            val opened = CassetteSeal.open(sealed, pending.privateKey, pending.publicKey)
            return if (opened != null) Result(Outcome.SIGN_IN, opened) else Result(Outcome.FAILED, null)
        }
        if (!token.isNullOrEmpty()) return Result(Outcome.SIGN_IN, token)
        return Result(Outcome.FAILED, null)
    }

    private fun readPending(): Pending? {
        val raw = CassetteSecureStore.getString(PENDING) ?: return null
        return try {
            val json = JSONObject(raw)
            Pending(
                json.getString("state"),
                CassetteSeal.decode(json.getString("private")) ?: return null,
                CassetteSeal.decode(json.getString("public")) ?: return null,
                json.getLong("at")
            )
        } catch (e: Exception) {
            null
        }
    }
}
