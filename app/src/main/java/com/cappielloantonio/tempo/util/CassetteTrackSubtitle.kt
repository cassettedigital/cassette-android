//
//  CassetteTrackSubtitle.kt
//  Tempo (Cassette fork) — util
//
//  Display-time track subtitle for album / artist contexts. Keep byte-aligned
//  with apps/cassette/src/lib/dashboard/track-subtitle.ts.
//

package com.cappielloantonio.tempo.util

object CassetteTrackSubtitle {
    private val FEAT_INFIX =
        Regex("^(.*?)\\s+(feat\\.|featuring|ft\\.|with)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val FEAT_PAREN =
        Regex(
            "^(.*?)\\s*[\\[({]\\s*(feat\\.|featuring|ft\\.|with)\\s+(.+?)\\s*[\\])}]\\s*$",
            RegexOption.IGNORE_CASE
        )

    /**
     * Subtitle under a track title when the page already names the primary artist.
     * Returns null when the line would add nothing.
     */
    @JvmStatic
    fun contextual(trackArtist: String?, contextArtist: String?): String? {
        val track = trackArtist?.trim().orEmpty()
        if (track.isEmpty()) return null
        val ctx = contextArtist?.trim().orEmpty()
        if (ctx.isEmpty()) return track

        // Exact match only — normalizeArtist strips "feat." and would hide guests.
        if (track.equals(ctx, ignoreCase = true)) return null

        for (re in listOf(FEAT_INFIX, FEAT_PAREN)) {
            val m = re.matchEntire(track) ?: continue
            val primary = m.groupValues[1].trim()
            val guests = m.groupValues[3].trim()
            if (primary.isNotEmpty() && guests.isNotEmpty() && sameArtist(primary, ctx)) {
                return "feat. ${guests.replace(Regex("\\s+"), " ")}"
            }
        }

        if (sameArtist(track, ctx)) return null

        return track
    }

    private fun sameArtist(a: String, b: String): Boolean =
        CassetteAlbumGroupKey.normalizeArtist(a) == CassetteAlbumGroupKey.normalizeArtist(b)
}
