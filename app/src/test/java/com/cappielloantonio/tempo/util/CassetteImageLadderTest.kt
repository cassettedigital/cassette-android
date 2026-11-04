//
//  CassetteImageLadderTest.kt
//  Tempo (Cassette fork) — the image ladder on Android.
//
//  The kit's picking rule (digital.cassette.kit.CassetteKitTokens, generated from
//  packages/cassette-kit tokens.json in the main repo) must agree with the shared worked
//  examples, exactly as the web, iPhone and hub copies are checked. If this fails, the
//  platforms have drifted: fix the code, not the examples.
//

package com.cappielloantonio.tempo.util

import digital.cassette.kit.CassetteKitTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CassetteImageLadderTest {
    @Test
    fun pickRungMatchesEverySharedExample() {
        val images = CassetteKitTokens.Images
        for (e in images.pickExamples) {
            assertEquals(
                "pickRung($e)",
                e.rung,
                images.pickRung(e.w, e.h, e.scale, e.aspect, e.fit, e.natural),
            )
        }
    }

    @Test
    fun aRequestedSizeRoundsUpToARungAndNoSizeIsTheTop() {
        assertEquals(1200, CassetteImageLadder.ladderSize(-1))
        assertEquals(1200, CassetteImageLadder.ladderSize(0))
        assertEquals(120, CassetteImageLadder.ladderSize(92))
        assertEquals(480, CassetteImageLadder.ladderSize(300))
        assertEquals(800, CassetteImageLadder.ladderSize(500))
        assertEquals(1200, CassetteImageLadder.ladderSize(3000))
    }

    @Test
    fun aRungIsFilledIntoTheTemplate() {
        assertEquals(
            "https://media.cassette.digital/i/1200/m/artist-images/1/a.jpg?v=27fa21a0",
            CassetteImageLadder.rungUrl("https://media.cassette.digital/i/{rung}/m/artist-images/1/a.jpg?v=27fa21a0", 1200),
        )
        assertNull(CassetteImageLadder.rungUrl("https://example.com/a.jpg", 1200))
        assertNull(CassetteImageLadder.rungUrl(null, 1200))
    }
}
