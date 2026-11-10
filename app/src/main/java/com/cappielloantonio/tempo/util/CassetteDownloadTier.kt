package com.cappielloantonio.tempo.util

/**
 * cassette: what each account download tier asks the computer for (BUG-529).
 *
 * The same three tiers the website's device page offers and the computer's card
 * sync writes (apps/cassette `DownloadQualityControl.tsx`, apps/cassette-player
 * sidecar `usbTierParams`): Lossless is the original file, High an MP3 at 256,
 * Efficient an MP3 at 128. Before this, High and Efficient both asked for a
 * plain MP3 and got the same file, at whatever rate the computer defaults to.
 */
object CassetteDownloadTier {
    const val LOSSLESS = "lossless"
    const val HIGH = "high"
    const val EFFICIENT = "efficient"

    /** True when the tier keeps the original file (no transcode). */
    @JvmStatic
    fun isOriginal(tier: String?): Boolean = LOSSLESS == tier

    /** The MP3 ceiling for a transcoded tier, in kbps. Anything unknown is High,
     *  the account's own default (`/api/sync/account` coerces to it too). */
    @JvmStatic
    fun maxBitRate(tier: String?): Int = if (EFFICIENT == tier) 128 else 256
}
