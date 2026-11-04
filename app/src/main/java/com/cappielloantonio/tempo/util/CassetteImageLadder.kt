package com.cappielloantonio.tempo.util

import digital.cassette.kit.CassetteKitTokens

/**
 * cassette: the image ladder (docs/reference/CASSETTE_IMAGES.md in the main repo). Every picture Cassette
 * shows comes in five sizes, 120 to 1200 on the long side (CassetteKitTokens.Images, generated from the
 * kit). The phone asks its hub for covers at one of them, never the original, and keeps the top rung for
 * offline; Glide shrinks that to each view.
 */
object CassetteImageLadder {
    private val rungs: List<Int> get() = CassetteKitTokens.Images.rungs

    /** The top rung, 1200: what the phone keeps on disk. */
    @JvmStatic
    fun top(): Int = rungs.last()

    /** The rung for a requested size: the smallest that holds it; the top rung for no size (-1) or more. */
    @JvmStatic
    fun ladderSize(size: Int): Int {
        if (size <= 0) return top()
        return rungs.firstOrNull { size <= it } ?: top()
    }

    /** One rung of an image reference's `src` template (`{rung}`), or null when there is none. */
    @JvmStatic
    fun rungUrl(src: String?, rung: Int): String? =
        src?.takeIf { it.contains("{rung}") }?.replace("{rung}", rung.toString())
}
