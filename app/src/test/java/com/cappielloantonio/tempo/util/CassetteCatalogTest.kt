//
//  CassetteCatalogTest.kt
//  Tempo (Cassette fork): Search's ON CASSETTE rows draw only image-ladder pictures (BUG-964).
//

package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CassetteCatalogTest {
    private val answer = """
        {"order":["albums","artists","songs"],
         "artists":[{"name":"The Beatles","slug":"the-beatles","imageUrl":"https://media.cassette.digital/artists/b.jpg",
                     "image":{"src":"https://media.cassette.digital/i/{rung}/m/artists/b.jpg?v=1","kind":"photo"}}],
         "albums":[{"title":"Abbey Road","slug":"abbey-road","artistName":"The Beatles","artistSlug":"the-beatles",
                    "coverArtUrl":"https://coverartarchive.org/release/x/front-500.jpg",
                    "image":{"src":"https://media.cassette.digital/i/{rung}/x/abc","kind":"cover"}},
                   {"title":"Let It Be","slug":"let-it-be","artistName":"The Beatles",
                    "coverArtUrl":"https://coverartarchive.org/release/y/front-500.jpg","image":null}],
         "songs":[]}
    """.trimIndent()

    @Test
    fun drawsTheLadderPictureNeverTheOldAddress() {
        val hits = CassetteCatalog.parse(answer)
        assertEquals(listOf("Abbey Road", "Let It Be", "The Beatles"), hits.map { it.title })
        // a 48dp square on a Pixel 8 (2.625x) is 126px: the 240 rung
        assertEquals("https://media.cassette.digital/i/240/x/abc", hits[0].imageUrl(126))
        assertNull("no ladder picture: the empty square, not coverArtUrl", hits[1].imageUrl(126))
        assertEquals("https://media.cassette.digital/i/120/m/artists/b.jpg?v=1", hits[2].imageUrl(100))
    }

    @Test
    fun anAnswerFromBeforeTheLadderDrawsNothing() {
        val old = """{"albums":[{"title":"Help!","slug":"help","artistName":"The Beatles","coverArtUrl":"https://coverartarchive.org/release/z/front-500.jpg"}]}"""
        assertNull(CassetteCatalog.parse(old).single().imageUrl(126))
    }
}
