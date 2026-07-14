//
//  CassetteLocalId.kt
//  Tempo (Cassette fork) — util
//
//  Cassette fork — Layer 1 (Identity). Additive only.
//
//  Cassette local-ID — the cross-device track identity fallback. Lets the same
//  track on two devices be recognised as the same track when it has no
//  MusicBrainz Recording ID. Until now Android only ever received a
//  server-computed cassette_local_id (see CassetteSyncApi); this lets the client
//  compute it locally so it can report PLAYS (which the server has not seen).
//
//  This MUST stay byte-for-byte compatible with:
//    - Swift: apps/cassette-player-ios/amperfy/AmperfyKit/Cassette/CassetteLocalID.swift
//    - Go:    apps/cassette-player/sidecar/local-id.go
//    - TS:    apps/cassette/src/lib/cassette-local-id.ts
//
//  The shared fixture at apps/cassette/test/fixtures/local-id-vectors.json is the
//  locked source of truth. CassetteLocalIdTest asserts every vector.
//
//  Pipeline (locked; mirrors the Swift/Go/TS comments):
//    1. NFKD-normalize each string (decompose + compatibility).
//    2. Strip Unicode combining marks (general category Mn).
//    3. NFKC-normalize (recompose to canonical form).
//    4. Lowercase (Unicode default, locale-independent).
//    5. Trim leading/trailing whitespace.
//    6. Collapse runs of whitespace to a single ASCII space.
//    7. Concatenate normalized artist, normalized title, and the
//       half-away-from-zero rounded integer duration, joined by U+001F
//       (unit separator) — a character no plausible artist/title contains.
//    8. SHA-256 the UTF-8 bytes.
//    9. Take the first 16 bytes (128 bits) and lowercase-hex-encode them.
//
//  Output is always exactly 32 lowercase hexadecimal characters.
//
//  Combining-mark rule (audit-flagged divergence): strips ALL Unicode Mn marks
//  (like Swift + Go), NOT just the U+0300–U+036F block that the TS impl currently
//  strips. Every fixture vector's marks fall inside that block, so all four agree
//  on the locked contract; the phones + sidecar additionally agree on exotic
//  marks. See CASSETTE_PLAYBACK_SPINE_PLAN.md for the recommendation to align TS.
//

package com.cappielloantonio.tempo.util

import java.security.MessageDigest
import java.text.Normalizer

object CassetteLocalId {
    // U+001F unit separator — a character no plausible artist/title contains.
    private val UNIT_SEPARATOR: Char = 0x1F.toChar()
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Compute the Cassette local-ID for a track.
     *
     * @param artist artist name — whitespace and case ignored, diacritics stripped.
     * @param title track title — same normalisation as artist.
     * @param durationSeconds length in seconds, rounded to the nearest integer
     *   (half away from zero). Non-finite or negative values are treated as 0.
     * @return a 32-character lowercase hex string.
     */
    @JvmStatic
    fun compute(artist: String, title: String, durationSeconds: Double): String {
        val a = normalize(artist)
        val t = normalize(title)
        val d = durationKey(durationSeconds)

        val payload = a + UNIT_SEPARATOR + t + UNIT_SEPARATOR + d
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        // First 16 bytes (128 bits) → 32 lowercase hex chars.
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Ergonomic overload for callers that already have an integer duration
     *  (e.g. Subsonic responses). Mirrors CassetteLocalIDFromSeconds in Go. */
    @JvmStatic
    fun compute(artist: String, title: String, durationSeconds: Int): String =
        compute(artist, title, durationSeconds.toDouble())

    // --- Normalization (steps 1–6) ---

    private fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val stripped = stripDiacritics(input)
        val lowered = stripped.lowercase()
        return collapseWhitespace(lowered)
    }

    /** NFKD → remove combining marks (category Mn) → NFKC. */
    private fun stripDiacritics(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFKC)
    }

    /**
     * Trim leading/trailing whitespace and collapse internal runs of Unicode
     * whitespace to a single ASCII space. Mirrors Swift's collapseWhitespace,
     * Go's strings.Join(strings.FieldsFunc(s, unicode.IsSpace), " "), and JS's
     * .trim().replace(/\s+/g, " "). Uses the Unicode White_Space property set.
     */
    private fun collapseWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var started = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isUnicodeWhitespace(cp)) {
                if (started) pendingSpace = true
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.appendCodePoint(cp)
                started = true
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** The Unicode White_Space property set — matches Swift's
     *  `Unicode.Scalar.Properties.isWhitespace` and Go's `unicode.IsSpace`. */
    private fun isUnicodeWhitespace(cp: Int): Boolean = when (cp) {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
        0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> cp in 0x2000..0x200A
    }

    // --- Duration key (step 7 input) ---

    /** Half-away-from-zero rounding for non-negative finite inputs; NaN, ±Inf,
     *  and negatives collapse to "0" — matching the Swift/Go/TS guards. */
    private fun durationKey(d: Double): String {
        if (!d.isFinite() || d < 0.0) return "0"
        return Math.round(d).toString()
    }
}
