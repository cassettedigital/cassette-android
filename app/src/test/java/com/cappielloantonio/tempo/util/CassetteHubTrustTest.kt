package com.cappielloantonio.tempo.util

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * cassette (security design rule 2): the computer's door. A local https server stands in for the
 * computer with a certificate of its own, made like the hub's (`sidecar/lan-tls.go`: one ECDSA
 * P-256 key, no names in it, signed by itself).
 */
class CassetteHubTrustTest {
    private lateinit var server: MockWebServer
    private lateinit var computer: HeldCertificate

    @Before
    fun setUp() {
        computer = HeldCertificate.Builder()
            .commonName("Cassette on this computer")
            .ecdsa256()
            .build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(computer).build().sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun pinOfComputer(): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(computer.certificate.publicKey.encoded))

    private val base = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @Test
    fun thePinIsTheHubsSha256OfThePublicKey() {
        assertEquals(pinOfComputer(), CassetteHubTrust.pinOf(computer.certificate))
        assertTrue(Regex("^[A-Za-z0-9+/]{43}=$").matches(CassetteHubTrust.pinOf(computer.certificate)))
    }

    @Test
    fun theComputersKeyIsAcceptedAndTheRequestGoesEncrypted() {
        server.enqueue(MockResponse().setBody("ok"))
        val door = CassetteHubTrust.PinnedTls(pinOfComputer(), server.hostName)
        val calls = CassetteHubTrust.HubCallFactory(base) { if (it.host == server.hostName) door else null }
        // the app's addresses stay http; the door moves the wire to https on the same port
        val plain = server.url("/rest/ping").newBuilder().scheme("http").build()
        calls.newCall(Request.Builder().url(plain).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("https", response.request.url.scheme)
            assertEquals(server.port, response.request.url.port)
            assertEquals("ok", response.body!!.string())
        }
    }

    @Test
    fun anyOtherKeyIsRefused() {
        server.enqueue(MockResponse().setBody("ok"))
        val stranger = HeldCertificate.Builder().ecdsa256().build()
        val door = CassetteHubTrust.PinnedTls(CassetteHubTrust.pinOf(stranger.certificate), server.hostName)
        val calls = CassetteHubTrust.HubCallFactory(base) { door }
        try {
            calls.newCall(Request.Builder().url(server.url("/rest/ping")).build()).execute().close()
            fail("a certificate without the computer's key was accepted")
        } catch (e: IOException) {
            // refused in the handshake (OkHttp may then also try the other loopback address and
            // report that instead, with the refusal kept beside it): nothing was sent
            val refusals = (listOf(e) + e.suppressed).filterIsInstance<SSLException>()
            assertTrue("no handshake refusal in $e", refusals.any { it.message?.contains("not this account's computer") == true })
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun otherHostsGoOutUnchanged() {
        server.enqueue(MockResponse().setBody("ok"))
        val calls = CassetteHubTrust.HubCallFactory(base) { null }
        val call = calls.newCall(Request.Builder().url("http://radio.example/stream").build())
        assertEquals("http", call.request().url.scheme)
        assertEquals("radio.example", call.request().url.host)
    }

    @Test
    fun theComputerCantRedirectTheConnectionElsewhere() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://elsewhere.example/rest/ping"))
        val door = CassetteHubTrust.PinnedTls(pinOfComputer(), server.hostName)
        val calls = CassetteHubTrust.HubCallFactory(base) { door }
        calls.newCall(Request.Builder().url(server.url("/rest/ping")).build()).execute().use { response ->
            assertEquals(302, response.code)
            assertEquals(server.hostName, response.request.url.host)
        }
    }

    @Test
    fun base64MatchesTheJdk() {
        val random = java.util.Random(11)
        for (n in 0..70) {
            val bytes = ByteArray(n).also { random.nextBytes(it) }
            assertEquals(Base64.getEncoder().encodeToString(bytes), CassetteHubTrust.base64(bytes))
        }
    }
}
