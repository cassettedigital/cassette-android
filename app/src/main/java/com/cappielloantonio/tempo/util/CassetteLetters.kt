package com.cappielloantonio.tempo.util

import java.text.Normalizer

/**
 * cassette: how the Library files a record (iPhone Utilities.swift librarySortKey /
 * libraryLetter, ported from the phone-taste mock). Leading space, quotes, brackets and
 * inverted marks are ignored, then a leading "The ": "The Bends" files under B,
 * "(What's the Story)" under W. Accents fold ("Átta" is A); anything that does not fold
 * to A to Z (digits, symbols, other scripts) files under "#", which comes first.
 */
object CassetteLetters {
    /** Letters appear once a grid holds more than this many records (iPhone threshold). */
    const val THRESHOLD = 60

    @JvmField
    val LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    private val leadingMarks = setOf('"', '“', '”', '\'', '‘', '’', '(', '[', '{', '<', '¡', '¿', '.')

    @JvmStatic
    fun sortKey(title: String?): String {
        var key = (title ?: "").dropWhile { it.isWhitespace() || it in leadingMarks }
        if (key.length > 3 && key.substring(0, 3).equals("the", ignoreCase = true) && key[3].isWhitespace()) {
            key = key.substring(3).dropWhile { it.isWhitespace() }
        }
        return key
    }

    @JvmStatic
    fun letter(title: String?): String = letterForKey(sortKey(title))

    @JvmStatic
    fun letterForKey(key: String): String {
        val first = key.firstOrNull() ?: return "#"
        val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").uppercase()
        val c = folded.firstOrNull() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }
}
