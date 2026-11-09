package com.cappielloantonio.tempo.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * cassette (security design rule 3, BUG-938): the phone opens what the cloud seals. The vector was
 * sealed by the cloud's own `sealToken` (apps/cassette/src/lib/pairing-seal.ts, run with tsx on
 * 2026-11-09) to a throwaway key made for this test; the sign-in inside is made up.
 */
class CassetteSealTest {
    private val privateKey = CassetteSeal.decode("SKtczQc8xBPPFnFn-5FkHjKLWtDbPftpzbSZnbodPVM")!!
    private val publicKey = CassetteSeal.decode("oCYRhX9Z3k0SvXxq_5MnsT8_ZMhK1rf6Aesl4xoWkjA")!!
    private val sealed =
        "CBKNEkVkIK6vPP9MMuGC_l_Q-56uIygJohuwDsP3WE_i4Avbt65OCvN-QjUyYbrzxoS0Ay329CO5_1Epc7dD6BNOiSR4m8VpKO-e7Xj-Ge2bqZ85GuQfzgPlStDOTVA14iQ"

    @Test
    fun opensWhatTheCloudSealed() {
        assertEquals("cassette-test-token-not-a-real-sign-in", CassetteSeal.open(sealed, privateKey, publicKey))
    }

    @Test
    fun anotherKeyCantOpenIt() {
        val other = CassetteSeal.newKeyPair()
        assertNull(CassetteSeal.open(sealed, other.privateKey, other.publicKey))
        // the right private key with the wrong public half changes the key too (both halves salt it)
        assertNull(CassetteSeal.open(sealed, privateKey, other.publicKey))
    }

    @Test
    fun aDamagedOrShortSealOpensToNothing() {
        val flipped = sealed.toCharArray().also { it[60] = if (it[60] == 'A') 'B' else 'A' }.concatToString()
        assertNull(CassetteSeal.open(flipped, privateKey, publicKey))
        assertNull(CassetteSeal.open(sealed.substring(0, 70), privateKey, publicKey))
        assertNull(CassetteSeal.open("", privateKey, publicKey))
        assertNull(CassetteSeal.open("not base64 at all!", privateKey, publicKey))
    }

    @Test
    fun keysAndCodesAreShapedAsTheCloudAccepts() {
        // pairingKey: /^[A-Za-z0-9_-]{43}$/ ; pairingState: /^[A-Za-z0-9_-]{16,128}$/
        val key = CassetteSeal.newKeyPair()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(key.publicText))
        val state = CassetteSeal.newState()
        assertTrue(Regex("^[A-Za-z0-9_-]{16,128}$").matches(state))
        assertFalse(state == CassetteSeal.newState())
    }

    @Test
    fun codesCompare() {
        assertTrue(CassetteSeal.sameCode("abc", "abc"))
        assertFalse(CassetteSeal.sameCode("abc", "abd"))
        assertFalse(CassetteSeal.sameCode("abc", null))
        assertFalse(CassetteSeal.sameCode(null, null))
    }

    @Test
    fun base64urlMatchesTheJdk() {
        val random = java.util.Random(7)
        for (n in 0..70) {
            val bytes = ByteArray(n).also { random.nextBytes(it) }
            val jdk = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals(jdk, CassetteSeal.encode(bytes))
            assertArrayEquals(bytes, CassetteSeal.decode(jdk))
        }
    }
}
