package com.cappielloantonio.tempo.util

import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.BuildConfig
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * cassette (security design rule 2, L4, BUG-879 on the iPhone): the phone talks only to its own
 * computer, encrypted, whenever the cloud has the computer's key.
 *
 * Each computer made its own certificate once (hub `sidecar/lan-tls.go`) and answers phones on
 * its usual port both encrypted and plain. The cloud hands the phones the SHA-256 of its public
 * key (`lanTlsPin` from /api/player/me). While the phone has that key, every request to the
 * computer, the library, the music, the downloads, the covers and the lock-screen art, goes out
 * as https and is accepted only from a certificate carrying exactly that key. The computer's name
 * isn't checked against the certificate: the key is the identity, as on the iPhone.
 *
 * Addresses everywhere else in the app stay http (the saved server, download and cover keys);
 * only the wire changes, in [callFactory]. Without a key the phone stays on plain http, as before,
 * until the cloud has one. With a key it never falls back to plain on its own: a phone that did
 * would let anyone on the Wi-Fi switch the encryption off by blocking it. Only the cloud, over
 * its own checked connection, can withdraw a key (a computer that no longer has one).
 */
object CassetteHubTrust {
    private const val TAG = "CassetteHubTrust"
    private const val PIN = "cassette_lan_tls_pin"
    private const val HOST = "cassette_lan_tls_host"
    // Debug builds only, for trying the encrypted path before the cloud hands out keys (0126):
    // set both with `adb shell run-as` in the settings file. Never read in a release build, and
    // only ever adds a check, never removes one.
    private const val DEBUG_PIN = "cassette_debug_lan_tls_pin"
    private const val DEBUG_HOST = "cassette_debug_lan_tls_host"
    private val PIN_SHAPE = Regex("^[A-Za-z0-9+/]{43}=$")

    @Volatile
    private var tls: PinnedTls? = null

    private val prefs get() = App.getInstance().preferences

    /** The computer's key as the cloud last reported it (or, in a debug build, as set by hand), or null. */
    @JvmStatic
    fun pin(): String? = known()?.first

    private fun host(): String? = known()?.second

    private fun known(): Pair<String, String>? {
        val pin = prefs.getString(PIN, null)
        val host = prefs.getString(HOST, null)
        if (pin != null && host != null) return if (PIN_SHAPE.matches(pin)) pin to host else null
        if (!BuildConfig.DEBUG) return null
        val debugPin = prefs.getString(DEBUG_PIN, null) ?: return null
        val debugHost = prefs.getString(DEBUG_HOST, null) ?: return null
        return if (PIN_SHAPE.matches(debugPin)) debugPin to debugHost.lowercase() else null
    }

    /**
     * Remember the key the cloud reports for the account's computer, with the computer's name (at
     * sign-in, when the computer arrives, and on every account check, so a new key reaches the
     * phone). No key, or the stand-in for no computer, forgets it.
     */
    @JvmStatic
    fun record(config: Cassette.PlayerConfig) {
        val pin = config.lanTlsPin?.takeIf { PIN_SHAPE.matches(it) && !config.isNoComputer }
        val host = config.lanHostname?.lowercase()
        if (pin == null || host.isNullOrEmpty()) {
            forget()
            return
        }
        if (pin == prefs.getString(PIN, null) && host == prefs.getString(HOST, null)) return
        prefs.edit().putString(PIN, pin).putString(HOST, host).apply()
        tls = null
        Log.i(TAG, "the computer's key is known: talking to it encrypted")
    }

    /** Sign out: no computer is known any more. */
    @JvmStatic
    fun forget() {
        if (!prefs.contains(PIN) && !prefs.contains(HOST)) return
        prefs.edit().remove(PIN).remove(HOST).apply()
        tls = null
    }

    /** The checked door for [url], or null when it isn't the computer or the computer's key isn't known. */
    private fun tlsFor(url: HttpUrl): PinnedTls? {
        val (pin, host) = known() ?: return null
        if (!url.host.equals(host, ignoreCase = true)) return null
        tls?.let { if (it.pin == pin && it.host == host) return it }
        return PinnedTls(pin, host).also { tls = it }
    }

    /**
     * Every request to the computer goes through here. Requests elsewhere (the cloud, radio
     * stations) go to [base] unchanged; requests to the computer, while its key is known, go out
     * as https on the same port through a client that accepts that key only.
     */
    @JvmStatic
    fun callFactory(base: OkHttpClient): Call.Factory = HubCallFactory(base) { tlsFor(it) }

    internal class HubCallFactory(
        private val base: OkHttpClient,
        private val doorFor: (HttpUrl) -> PinnedTls?,
    ) : Call.Factory {
        @Volatile
        private var pinned: Pair<PinnedTls, OkHttpClient>? = null

        override fun newCall(request: Request): Call {
            val door = doorFor(request.url) ?: return base.newCall(request)
            val client = pinned?.takeIf { it.first === door }?.second
                ?: door.client(base).also { pinned = door to it }
            val secure = request.url.newBuilder().scheme("https").port(request.url.port).build()
            return client.newCall(request.newBuilder().url(secure).build())
        }
    }

    /**
     * A shared client for music, downloads and covers (through [callFactory]). Reading timeouts
     * are per read, so a long download or a radio stream is fine.
     */
    @JvmStatic
    val mediaClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        // debug builds only, like the library client's (RetrofitClient): which address each music,
        // download and cover request went to, so the encrypted path can be seen working
        if (BuildConfig.DEBUG) builder.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        builder.build()
    }

    /** One key, one host: the TLS setup that trusts that key and nothing else. */
    class PinnedTls(val pin: String, val host: String) {
        private val trust = PinnedTrustManager(pin)
        private val sockets: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), null)
        }.socketFactory

        fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .sslSocketFactory(sockets, trust)
            // the certificate names no host: the key, already checked in the handshake, is the identity
            .hostnameVerifier(HostnameVerifier { name, _ -> name.equals(host, ignoreCase = true) })
            // never be redirected off the checked connection (to plain http, or another host)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Accepts a certificate only when its public key is the computer's. */
    class PinnedTrustManager(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (!MessageDigest.isEqual(pinOf(leaf).toByteArray(), pin.toByteArray())) {
                throw CertificateException("not this account's computer")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("the phone doesn't accept connections")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** SHA-256 of the certificate's public key (its SubjectPublicKeyInfo), base64: how the hub states it. */
    @JvmStatic
    fun pinOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return base64(digest)
    }

    // standard base64 with padding, by hand (android.util.Base64 isn't in the JVM tests, java.util's needs Android 8)
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    @JvmStatic
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }
}
