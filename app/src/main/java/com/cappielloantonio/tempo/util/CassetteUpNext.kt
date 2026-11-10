package com.cappielloantonio.tempo.util

/**
 * cassette (BUG-980): what UP NEXT lists.
 *
 * The saved song queue is kept on purpose (after the app restarts the mini player is back on those
 * songs), and it stays in its table while a station plays, because starting a station replaces what
 * the player holds but not what was saved. A live station has nothing next (the iPhone hides Up Next
 * for one), so while a station plays UP NEXT shows none of the kept songs, not as the next ones, and
 * says so; the songs come back with the queue the next time songs play.
 */
object CassetteUpNext {
    /** Whether a playing item's `type` extra marks it as a live station (MappingUtil.mapInternetRadioStation). */
    @JvmStatic
    fun isStation(type: String?): Boolean = type == Constants.MEDIA_TYPE_RADIO

    /** The rows UP NEXT lists: none while a station plays, else the saved queue as it is. */
    @JvmStatic
    fun <T> rows(saved: List<T>?, stationLive: Boolean): List<T> = if (stationLive) emptyList() else saved.orEmpty()
}
