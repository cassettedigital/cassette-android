package com.cappielloantonio.tempo.util

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * cassette (security design rule 3, BUG-938): the sealed sign-in, the same as the computer app's
 * (cloud `lib/pairing-seal.ts`, desktop `pairing.rs` open_sealed; change one, change all three).
 *
 * Each sign-in starts with a fresh X25519 key and a one-time code. The cloud seals the sign-in to
 * the key, so the address it travels back in holds a blob only this phone could open, once.
 *
 * Sealed = base64url( cloud public key (32) | nonce (12) | AES-256-GCM ciphertext and tag ). The
 * key is HKDF-SHA256 over the X25519 shared secret, salted with both public keys (cloud's first),
 * info "cassette-pairing-v1".
 *
 * Plain Kotlin and Tink, no Android classes, so the JVM tests open what the cloud's own code seals.
 */
object CassetteSeal {
    private const val INFO = "cassette-pairing-v1"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    private val random = SecureRandom()

    /** A sign-in's own key: the private half never leaves the phone, the public half rides the pairing address. */
    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
        /** As the cloud expects it (`pairingKey`): 43 characters of base64url, no padding. */
        val publicText: String get() = encode(publicKey)
    }

    @JvmStatic
    fun newKeyPair(): KeyPair {
        val private = X25519.generatePrivateKey()
        return KeyPair(private, X25519.publicFromPrivate(private))
    }

    /** A one-time code as the cloud keeps it (`pairingState`): 43 characters of base64url. */
    @JvmStatic
    fun newState(): String = encode(ByteArray(32).also { random.nextBytes(it) })

    /** Whether two codes are the same, in time that doesn't depend on where they differ. */
    @JvmStatic
    fun sameCode(a: String?, b: String?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** The sign-in inside [sealed], or null when this key can't open it (not ours, damaged, or empty). */
    @JvmStatic
    fun open(sealed: String, privateKey: ByteArray, publicKey: ByteArray): String? {
        return try {
            val raw = decode(sealed) ?: return null
            if (raw.size < KEY_BYTES + NONCE_BYTES + TAG_BYTES) return null
            val cloud = raw.copyOfRange(0, KEY_BYTES)
            val nonce = raw.copyOfRange(KEY_BYTES, KEY_BYTES + NONCE_BYTES)
            val shared = X25519.computeSharedSecret(privateKey, cloud)
            val key = Hkdf.computeHkdf("HMACSHA256", shared, cloud + publicKey, INFO.toByteArray(Charsets.UTF_8), 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            val plain = cipher.doFinal(raw, KEY_BYTES + NONCE_BYTES, raw.size - KEY_BYTES - NONCE_BYTES)
            String(plain, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // base64url without padding, by hand: java.util.Base64 needs Android 8 and this runs from Android 7.
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    @JvmStatic
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    @JvmStatic
    fun decode(text: String): ByteArray? {
        val s = text.trimEnd('=')
        if (s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xffff
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
