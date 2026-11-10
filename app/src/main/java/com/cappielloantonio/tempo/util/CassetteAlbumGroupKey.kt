//
//  CassetteAlbumGroupKey.kt
//  Tempo (Cassette fork) — util
//
//  Album group-key + name normalization, ported from the web so the phone lands
//  on the SAME album key the cloud uses. This is what lets the device match its
//  downloaded albums to the artwork manifest (GET /api/sync/devices/:id/artwork),
//  which is keyed by artist+album NAME, not by any id the phone holds — the
//  manifest carries the folder-cover fingerprint (local_cover_version) that tells
//  the phone a cover changed on the user's Mac.
//
//  Keep byte-compatible with:
//    - apps/cassette/src/lib/dashboard/match-normalize.ts
//    - apps/cassette/src/lib/dashboard/library-group-keys.ts
//  (the same cross-language discipline as CassetteLocalId).
//
//  The phone keeps each download's album-artist tag since BUG-970 (the hub's
//  displayAlbumArtist, Download.albumArtist), so the two-argument
//  effectiveAlbumArtist below runs the web's whole rule: the tag when every
//  song agrees, else the dominant track artist, else "Various Artists". A row
//  downloaded before that carries no tag until the sync fills it in, and the
//  one-argument form (the tagless branch) stands in for it meanwhile. The
//  artwork-manifest key (keyFromTracks) still reads track artists only: a record
//  the hub tagged with an explicit, differing album_artist won't match it and
//  falls back to a fetch-once cover with no fingerprint tracking.
//

package com.cappielloantonio.tempo.util

import java.text.Normalizer

object CassetteAlbumGroupKey {
    private const val VARIOUS_ARTISTS = "Various Artists"

    // Internal artist|album separator. Matches the web's NUL join; a plain space
    // could collide across the artist/album boundary ("a b"+"c" vs "a"+"b c").
    private const val SEP = '\u0000'

    // Trailing "(... deluxe / remastered / anniversary / ...)" edition tags.
    private val EDITION_QUALIFIER = Regex(
        "\\s*[\\[(][^\\])]*(?:deluxe edition|deluxe|remastered|remaster|anniversary|expanded|bonus|mono|stereo)[^\\])]*[\\])]\\s*$",
        RegexOption.IGNORE_CASE
    )

    // " feat. / featuring / ft. / with " and everything after.
    private val FEAT_SPLIT = Regex(
        "\\s+(?:feat\\.|featuring|ft\\.|with)\\s+",
        RegexOption.IGNORE_CASE
    )

    // Every Unicode dash/hyphen variant → ASCII "-".
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]")
    // \p{L}/\p{N}/\p{M} are Unicode categories on Android's ICU regex without any
    // flag; the (?U) UNICODE_CHARACTER_CLASS flag is NOT supported there (it
    // throws at compile). \s stays ASCII, which is fine: NON_WORD folds any
    // exotic space to a plain one first, then SPACES collapses. Both sides run
    // this same code, so keys stay mutually consistent regardless.
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
    private val SPACES = Regex("\\s+")

    private fun stripDiacritics(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

    private fun foldDashes(s: String): String = DASHES.replace(s, "-")

    /** Lowercase, trim, fold dashes, strip diacritics + punctuation, drop a
     *  trailing edition qualifier and a leading "the ". Comparison key only. */
    @JvmStatic
    fun normalizeTitle(s: String): String {
        var out = foldDashes(stripDiacritics(s)).lowercase().trim()
        out = EDITION_QUALIFIER.replace(out, "").trim()
        out = NON_WORD.replace(out, " ")
        out = SPACES.replace(out, " ").trim()
        if (out.startsWith("the ")) out = out.substring(4).trim()
        return out
    }

    /** Drop a featured-artist suffix; leave "&" and " x " (real duos) intact. */
    @JvmStatic
    fun stripFeatured(s: String): String {
        val m = FEAT_SPLIT.find(s) ?: return s.trim()
        return s.substring(0, m.range.first).trim()
    }

    @JvmStatic
    fun normalizeArtist(s: String): String = normalizeTitle(stripFeatured(s))

    /** Dominant track artist, or "Various Artists" on a tie — the phone's
     *  approximation of the server's effectiveAlbumArtist (no album_artist tag
     *  on device). */
    @JvmStatic
    fun effectiveAlbumArtist(trackArtists: List<String>): String {
        val counts = LinkedHashMap<String, Int>()
        for (raw in trackArtists) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            counts[name] = (counts[name] ?: 0) + 1
        }
        if (counts.isEmpty()) return ""
        val ranked = counts.entries.sortedByDescending { it.value }
        if (ranked.size == 1) return ranked[0].key
        return if (ranked[0].value > ranked[1].value) ranked[0].key else VARIOUS_ARTISTS
    }

    /** The web's whole rule (library-group-keys.ts effectiveAlbumArtist): the album-artist
     *  tag when the songs that carry one all say the same thing, else the one-argument
     *  rule over the track artists. A blank or missing tag says nothing. */
    @JvmStatic
    fun effectiveAlbumArtist(trackArtists: List<String>, albumArtistTags: List<String?>): String {
        val tags = LinkedHashSet<String>()
        for (raw in albumArtistTags) {
            val tag = raw?.trim()
            if (!tag.isNullOrEmpty()) tags.add(tag)
        }
        if (tags.size == 1) return tags.first()
        return effectiveAlbumArtist(trackArtists)
    }

    /** Group key for a manifest album, whose artist_name is ALREADY the server's
     *  effective album artist. */
    @JvmStatic
    fun keyFromEffective(effectiveArtist: String, albumName: String): String =
        normalizeArtist(effectiveArtist) + SEP + normalizeTitle(albumName)

    /** Group key for a downloaded album, derived from its track artists. */
    @JvmStatic
    fun keyFromTracks(trackArtists: List<String>, albumName: String): String =
        keyFromEffective(effectiveAlbumArtist(trackArtists), albumName)
}
