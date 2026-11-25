package com.cappielloantonio.tempo.util

import java.util.concurrent.ConcurrentHashMap

/**
 * D46 (design wave 6): an artist is their photo, else one of their records, never a letter or a
 * stock figure. Library's and Home's artist lists are built from the records on this phone; each
 * remembers the first of an artist's records with a cover here, and an artist with no photo of
 * their own on the phone draws that record (CustomGlideRequest), as the iPhone's
 * Artist.getArtworkCollection and the artist page's fan already do. Only an artist with neither
 * falls to the kind's mark.
 */
object CassetteArtistRecord {
    private val covers = ConcurrentHashMap<String, String>()

    private fun key(artist: String): String = artist.removePrefix("ar-")

    /** The first record with a cover seen for this artist stays theirs. */
    @JvmStatic
    fun remember(artistId: String?, coverArtId: String?) {
        if (artistId.isNullOrEmpty() || coverArtId.isNullOrEmpty()) return
        covers.putIfAbsent(key(artistId), coverArtId)
    }

    /** One of this artist's records' cover ids, or null when none is known. */
    @JvmStatic
    fun cover(artist: String?): String? = if (artist.isNullOrEmpty()) null else covers[key(artist)]
}
