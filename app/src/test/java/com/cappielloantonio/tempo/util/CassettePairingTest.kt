package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.util.CassettePairing.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** cassette (security design rule 3, BUG-938): what the pairing callback takes, and what it drops. */
class CassettePairingTest {
    private val started = 1_000_000L
    private val key = CassetteSeal.newKeyPair()
    private val pending = CassettePairing.Pending("the-code-this-phone-sent-1234567890", key.privateKey, key.publicKey, started)

    private fun decide(p: CassettePairing.Pending?, state: String?, sealed: String? = null, token: String? = null, now: Long = started + 60_000) =
        CassettePairing.decide(p, state, sealed, token, now)

    @Test
    fun aLinkWhenThisPhoneIsntSigningInIsDropped() {
        val r = decide(null, "anything", token = "someone-elses-sign-in")
        assertEquals(Outcome.NOT_ASKED, r.outcome)
        assertNull(r.token)
    }

    @Test
    fun aLinkWithoutThisSignInsCodeIsDropped() {
        assertEquals(Outcome.NOT_ASKED, decide(pending, null, token = "planted").outcome)
        assertEquals(Outcome.NOT_ASKED, decide(pending, "a-guessed-code-0000000000000000", token = "planted").outcome)
    }

    @Test
    fun theAnswerToThisSignInIsTaken() {
        val r = decide(pending, pending.state, token = "the-real-sign-in")
        assertEquals(Outcome.SIGN_IN, r.outcome)
        assertEquals("the-real-sign-in", r.token)
    }

    @Test
    fun aSealedAnswerIsOpenedWithThisSignInsKey() {
        // the cloud's own vector (CassetteSealTest), with its key as the waiting sign-in's
        val cloudKeyPrivate = CassetteSeal.decode("SKtczQc8xBPPFnFn-5FkHjKLWtDbPftpzbSZnbodPVM")!!
        val cloudKeyPublic = CassetteSeal.decode("oCYRhX9Z3k0SvXxq_5MnsT8_ZMhK1rf6Aesl4xoWkjA")!!
        val waiting = CassettePairing.Pending("code-for-the-sealed-answer-0000", cloudKeyPrivate, cloudKeyPublic, started)
        val sealed =
            "CBKNEkVkIK6vPP9MMuGC_l_Q-56uIygJohuwDsP3WE_i4Avbt65OCvN-QjUyYbrzxoS0Ay329CO5_1Epc7dD6BNOiSR4m8VpKO-e7Xj-Ge2bqZ85GuQfzgPlStDOTVA14iQ"
        val r = decide(waiting, waiting.state, sealed = sealed)
        assertEquals(Outcome.SIGN_IN, r.outcome)
        assertEquals("cassette-test-token-not-a-real-sign-in", r.token)
    }

    @Test
    fun aSealThisKeyCantOpenFailsAndDoesntFallBackToAPlainToken() {
        val sealed =
            "CBKNEkVkIK6vPP9MMuGC_l_Q-56uIygJohuwDsP3WE_i4Avbt65OCvN-QjUyYbrzxoS0Ay329CO5_1Epc7dD6BNOiSR4m8VpKO-e7Xj-Ge2bqZ85GuQfzgPlStDOTVA14iQ"
        val r = decide(pending, pending.state, sealed = sealed, token = "plain-beside-it")
        assertEquals(Outcome.FAILED, r.outcome)
        assertNull(r.token)
    }

    @Test
    fun aSignInWaitsThirtyMinutes() {
        assertEquals(Outcome.SIGN_IN, decide(pending, pending.state, token = "t", now = started + CassettePairing.WAIT_MS).outcome)
        assertEquals(Outcome.EXPIRED, decide(pending, pending.state, token = "t", now = started + CassettePairing.WAIT_MS + 1).outcome)
        // a clock that went backwards doesn't keep a sign-in open
        assertEquals(Outcome.EXPIRED, decide(pending, pending.state, token = "t", now = started - 1).outcome)
    }

    @Test
    fun anAnswerWithNothingInItFails() {
        assertEquals(Outcome.FAILED, decide(pending, pending.state).outcome)
        assertEquals(Outcome.FAILED, decide(pending, pending.state, sealed = "", token = "").outcome)
    }
}
