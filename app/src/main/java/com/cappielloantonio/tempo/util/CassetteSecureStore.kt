package com.cappielloantonio.tempo.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.cappielloantonio.tempo.App
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * cassette: what can sign this phone in, locked to this phone (security design rule 1, L3;
 * iPhone SecureSettingsStore, BUG-878).
 *
 * The Cassette sign-in, the computer's music-server password and the login made from it used to
 * sit in the plain settings file. Now each is sealed with an AES key that lives in the phone's
 * Android Keystore (the security chip on a Pixel), which no app, backup or phone-to-phone copy
 * can take off the phone. A copied file would be unreadable anywhere else, and the app's
 * data-extraction rules copy nothing in the first place.
 *
 * Usable while the phone is locked (sync and downloads run in the background), never asks for
 * the screen lock. Reads are cached in memory, because the sign-in is read on nearly every
 * screen and request. The first read of a value an older version left in the settings file
 * moves it across, and removes the old copy only once the sealed one is saved, so an update
 * never signs anyone out.
 *
 * If the Keystore ever refuses (it shouldn't on a real phone), a value is kept in memory for
 * this run only and never written out in the clear.
 */
object CassetteSecureStore {
    private const val TAG = "CassetteSecureStore"
    private const val FILE = "cassette_secure"
    private const val ALIAS = "cassette.secure.v1"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val lock = Any()
    private val cache = HashMap<String, String?>()

    private val secure: SharedPreferences by lazy {
        App.getContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val legacy: SharedPreferences
        get() = App.getInstance().preferences

    @JvmStatic
    fun getString(key: String): String? = synchronized(lock) {
        if (cache.containsKey(key)) return cache[key]
        var value = secure.getString(key, null)?.let { open(key, it) }
        if (value == null && secure.contains(key)) {
            // sealed with a key this phone no longer has: unreadable for good
            secure.edit().remove(key).apply()
        }
        if (value == null) {
            val old = legacy.getString(key, null)
            if (old != null) {
                value = old
                if (save(key, old)) legacy.edit().remove(key).apply()
            }
        }
        cache[key] = value
        value
    }

    @JvmStatic
    fun setString(key: String, value: String?) {
        val v = value?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (cache.containsKey(key) && cache[key] == v && !legacy.contains(key)) return
            cache[key] = v
            if (v == null) {
                secure.edit().remove(key).apply()
            } else if (!save(key, v)) {
                secure.edit().remove(key).apply()
            }
            if (legacy.contains(key)) legacy.edit().remove(key).apply()
        }
    }

    /** Seal and write one value; false when the Keystore refused (the value then lives in memory only). */
    private fun save(key: String, value: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            // the slot's name rides as associated data, so a sealed value can't be moved to another slot
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val blob = ByteArray(1 + iv.size + body.size)
            blob[0] = VERSION
            System.arraycopy(iv, 0, blob, 1, iv.size)
            System.arraycopy(body, 0, blob, 1 + iv.size, body.size)
            secure.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't seal $key; kept in memory only", e)
            false
        }
    }

    private fun open(key: String, stored: String): String? {
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= 1 + IV_BYTES || blob[0] != VERSION) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open $key", e)
            null
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
