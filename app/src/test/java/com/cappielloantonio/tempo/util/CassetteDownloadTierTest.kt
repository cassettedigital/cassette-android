package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUG-529: the three account tiers ask the computer for three different files. */
class CassetteDownloadTierTest {
    @Test
    fun losslessKeepsTheOriginal() {
        assertTrue(CassetteDownloadTier.isOriginal("lossless"))
        assertFalse(CassetteDownloadTier.isOriginal("high"))
        assertFalse(CassetteDownloadTier.isOriginal("efficient"))
        assertFalse(CassetteDownloadTier.isOriginal(null))
    }

    @Test
    fun highAndEfficientAreDifferentFiles() {
        // the website's device page and the computer's card sync: MP3 256 and MP3 128
        assertEquals(256, CassetteDownloadTier.maxBitRate("high"))
        assertEquals(128, CassetteDownloadTier.maxBitRate("efficient"))
    }

    @Test
    fun anythingUnknownIsHigh() {
        assertEquals(256, CassetteDownloadTier.maxBitRate(null))
        assertEquals(256, CassetteDownloadTier.maxBitRate(""))
        assertEquals(256, CassetteDownloadTier.maxBitRate("ultra"))
    }
}
