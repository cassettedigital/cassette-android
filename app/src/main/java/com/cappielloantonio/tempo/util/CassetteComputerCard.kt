package com.cappielloantonio.tempo.util

/**
 * cassette: whether Home leads with the computer card, and in which state (iPhone
 * ComputerCardModel, BUG-899), from facts the phone already has. The card shows until the
 * music is in: while the phone has no music, and through the phone's first sync. After that
 * first sync it comes back only if the phone is emptied. Pure, so it is tested.
 */
object CassetteComputerCard {
    enum class Phase { SYNCING, GETTING_READY, OUT_OF_REACH, NO_MUSIC, NOTHING_CHOSEN, NOT_SET_UP }

    data class Facts(
        /** The phone has a computer to sync from. */
        val isPaired: Boolean,
        /** The computer can't be reached. */
        val outOfReach: Boolean,
        /** Music is on its way (downloads in flight or albums still waiting). */
        val arriving: Boolean,
        /** Albums on this phone; null until the first read. */
        val onPhone: Int?,
        /** The phone's first sync has finished. */
        val firstSyncDone: Boolean,
        /** The computer's album count; null before the cloud has answered. */
        val computerAlbums: Int?,
        /** The computer has finished reading its library at least once. */
        val computerHasRead: Boolean,
        /** "everything" or "selective"; null until asked. */
        val scope: String?,
    )

    /** The card's state, or null when Home has its music and no card. */
    @JvmStatic
    fun phase(f: Facts): Phase? {
        if (!f.isPaired) return Phase.NOT_SET_UP
        // not read yet: say nothing rather than flash a card that is about to go
        val onPhone = f.onPhone ?: return null
        if (onPhone > 0 && (f.firstSyncDone || !f.arriving)) return null
        if (f.outOfReach) return Phase.OUT_OF_REACH
        if (f.arriving) return Phase.SYNCING
        val albums = f.computerAlbums
        if (albums == null || !f.computerHasRead) return Phase.GETTING_READY
        if (albums == 0) return Phase.NO_MUSIC
        if (f.scope == "selective") return Phase.NOTHING_CHOSEN
        return Phase.GETTING_READY
    }

    /** Whether the scope would change the answer (the one fact asked only when needed). */
    @JvmStatic
    fun needsScope(f: Facts): Boolean =
        f.isPaired && f.onPhone == 0 && !f.outOfReach && !f.arriving && f.computerHasRead && (f.computerAlbums ?: 0) > 0

    @JvmStatic
    fun headline(p: Phase): String = when (p) {
        Phase.SYNCING -> "Syncing"
        Phase.GETTING_READY -> "Getting ready"
        Phase.OUT_OF_REACH -> "Out of reach"
        Phase.NO_MUSIC -> "No music yet"
        Phase.NOTHING_CHOSEN -> "Nothing chosen"
        Phase.NOT_SET_UP -> "Not set up"
    }

    @JvmStatic
    fun body(p: Phase, onPhone: Int, total: Int): String = when (p) {
        Phase.SYNCING -> maxOf(total, onPhone).let { all -> "$onPhone of $all ${if (all == 1) "album" else "albums"} on this phone" }
        Phase.GETTING_READY -> "Reading your library"
        Phase.OUT_OF_REACH -> "New music arrives when it's back"
        Phase.NO_MUSIC -> "Add music to Cassette on your computer"
        Phase.NOTHING_CHOSEN -> "Pick the music for this phone in Cassette on your computer"
        Phase.NOT_SET_UP -> "Your music plays from Cassette on your computer"
    }

    /** One button only where the person can act ("title" to the site's path); none while they wait. */
    @JvmStatic
    fun action(p: Phase): Pair<String, String>? = when (p) {
        Phase.NO_MUSIC -> "How to add music" to "/help/adding-music"
        Phase.NOTHING_CHOSEN -> "How to choose music" to "/help/choose-albums-for-phone"
        Phase.NOT_SET_UP -> "Get it for your computer" to "/apps"
        else -> null
    }
}
