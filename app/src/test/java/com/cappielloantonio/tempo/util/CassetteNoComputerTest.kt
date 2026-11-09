package com.cappielloantonio.tempo.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** cassette (BUG-876, BUG-899): the stand-in a phone signs in to before its account has a computer. */
class CassetteNoComputerTest {
    @Test
    fun theStandInIsKnownByItsAddress() {
        val standIn = Cassette.PlayerConfig.noComputer(null)
        assertTrue(standIn.isNoComputer())
        assertTrue(Cassette.isNoComputer(standIn.address()))
    }

    @Test
    fun aRealComputerIsNotTheStandIn() {
        assertFalse(Cassette.isNoComputer("http://michaels-MacBook-Pro.local:4533"))
        assertFalse(Cassette.isNoComputer("http://192.168.1.20:4533"))
        assertFalse(Cassette.isNoComputer(null))
        assertFalse(Cassette.isNoComputer("not a url ::"))
    }
}
