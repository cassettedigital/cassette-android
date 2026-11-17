package com.cappielloantonio.tempo.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * cassette: On now, the top of the Radio page, the one the Mac app and the website draw
 * (apps/cassette/src/app/components/radio/RadioDial.tsx; the founder, 2026-11-16: one Radio page on every
 * surface). The person's saved stations, then the live stations that publish what's on in today's turn, six
 * at most (CassetteRadio.onNowStations). Each card asks what's on once when it shows, and the cards fill in
 * one after another in reading order (the founder, 2026-11-07: "load sequentially"): a card settles once what
 * it's playing is known and its cover has fully arrived, and only after the card before it has. A card slow
 * to answer gets a short wait, then the next goes ahead and the slow one settles whenever it's ready.
 *
 * This holds what each card knows; the dial's adapter draws it and hears about every change.
 */
class CassetteOnNowBoard(context: Context, private val listener: Listener) {

    fun interface Listener {
        /** The card for `slug` has something new to show. */
        fun changed(slug: String)
    }

    /** What one card knows. */
    class Card internal constructor(station: CassetteRadio.Station) {
        var station: CassetteRadio.Station = station
            internal set
        internal var answered = false
        var answer: CassetteRadioLive.OnAir? = null
            internal set
        /** The address the cover is drawn from (a ladder rung when the cloud sent one), once it has arrived. */
        var coverUrl: String? = null
            internal set
        internal var coverSettled = false
        internal var wanted: String? = null
        /** The card has settled: its words say what's on, and its cover (if any) is worn. */
        var shown = false
            internal set

        /** The picture of what's playing, when it isn't the station's own mark. */
        internal fun wear(): String? = answer?.cover?.takeIf { it != station.image }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val coverPx = (COVER_DP * app.resources.displayMetrics.density).roundToInt()
    private val cards = LinkedHashMap<String, Card>()
    private val asking = HashMap<String, Int>()
    private var turnAt = 0
    private var beat: Runnable? = null
    private var wait: Runnable? = null
    private var released = false

    /** The cards' stations' slugs, in reading order. */
    var order: List<String> = emptyList()
        private set

    /** When the cards last asked what's on (0 before they have). */
    var lastRead = 0L
        private set

    fun card(slug: String): Card? = cards[slug]

    /**
     * The board's stations, in reading order. The same list again changes nothing; a new one keeps the cards it
     * already had (and what they know) and turns the new ones in after them.
     */
    fun show(stations: List<CassetteRadio.Station>) {
        if (released) return
        val slugs = stations.map { it.slug }
        for (s in stations) cards[s.slug]?.station = s
        if (slugs == order) return
        order = slugs
        val kept = LinkedHashMap<String, Card>()
        for (s in stations) kept[s.slug] = cards[s.slug] ?: Card(s)
        cards.clear()
        cards.putAll(kept)
        asking.keys.retainAll(kept.keys)
        for (s in stations) if (!kept.getValue(s.slug).answered && s.slug !in asking) ask(s)
        turnAt = order.indexOfFirst { cards[it]?.shown != true }.let { if (it < 0) order.size else it }
        startTurn()
        update()
    }

    /** Ask every card what's on again (the page back after a while). */
    fun reread() {
        if (released) return
        for (slug in order) cards[slug]?.let { ask(it.station) }
    }

    /** The page is gone: nothing more is asked or drawn. */
    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    // ── reading what's on ──

    private fun ask(station: CassetteRadio.Station) {
        val slug = station.slug
        if (!station.live) {
            cards[slug]?.answered = true
            update()
            return
        }
        lastRead = System.currentTimeMillis()
        val ticket = (asking[slug] ?: 0) + 1
        asking[slug] = ticket
        io.execute {
            val answer = CassetteRadioLive.onAir(slug)
            main.post {
                if (released || asking[slug] != ticket) return@post
                asking.remove(slug)
                answered(slug, answer)
            }
        }
    }

    private fun answered(slug: String, answer: CassetteRadioLive.OnAir?) {
        val card = cards[slug] ?: return
        val before = card.wear()
        card.answered = true
        card.answer = answer
        val after = card.wear()
        if (after != before) {
            card.coverUrl = null
            card.coverSettled = after == null
            card.wanted = after
            if (after != null) loadCover(card, after, answer?.coverSrc)
        }
        if (card.shown) listener.changed(slug)
        update()
    }

    /** The cover, through the image ladder at the size of its square; the card waits for it a moment. */
    private fun loadCover(card: Card, cover: String, src: String?) {
        val slug = card.station.slug
        main.postDelayed({
            if (cards[slug] === card && card.wanted == cover && !card.coverSettled) {
                card.coverSettled = true
                update()
            }
        }, COVER_WAIT_MS)
        fetchCover(card, cover, CassetteImageLadder.rungUrl(src, CassetteImageLadder.ladderSize(coverPx)) ?: cover)
    }

    /**
     * One address for the cover. A ladder rung that fails (it can refuse a size it hasn't made yet: "today's sizes
     * are used up") falls back to the picture's own address once, as the iPhone's loader does.
     */
    private fun fetchCover(card: Card, cover: String, url: String) {
        Glide.with(app).load(url).centerCrop().listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                main.post { if (url != cover) fetchCover(card, cover, cover) else arrived(card, cover, null) }
                return false
            }

            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                main.post { arrived(card, cover, url) }
                return false
            }
        }).preload(coverPx, coverPx)
    }

    private fun arrived(card: Card, cover: String, url: String?) {
        val slug = card.station.slug
        if (released || cards[slug] !== card || card.wanted != cover) return
        card.coverUrl = url
        // the station's page draws the same picture from the same address, with no new load
        if (url != null) CassetteRadioLive.noteDrawnCover(slug, cover, url)
        card.coverSettled = true
        if (card.shown) listener.changed(slug)
        update()
    }

    // ── turns ──

    private fun ready(card: Card): Boolean = card.answered && (card.wear() == null || card.coverSettled)

    /** A turn begins: the longest it waits for its card, from now (not from when the last card got ready). */
    private fun startTurn() {
        beat?.let(main::removeCallbacks)
        beat = null
        wait?.let(main::removeCallbacks)
        wait = null
        if (turnAt >= order.size) return
        val at = turnAt
        wait = Runnable { advance(at) }.also { main.postDelayed(it, TURN_WAIT_MS) }
    }

    private fun advance(at: Int) {
        if (released || turnAt != at) return
        turnAt = at + 1
        startTurn()
        update()
    }

    /** Every card shows what it knows once its turn has come (or gone by) and it's ready; the card whose turn it is goes a beat after it's ready. */
    private fun update() {
        if (released) return
        for ((index, slug) in order.withIndex()) {
            val card = cards[slug] ?: continue
            if (index > turnAt || card.shown || !ready(card)) continue
            card.shown = true
            listener.changed(slug)
        }
        if (beat == null && turnAt < order.size) {
            val card = cards[order[turnAt]]
            if (card != null && ready(card)) {
                val at = turnAt
                beat = Runnable { advance(at) }.also { main.postDelayed(it, BEAT_MS) }
            }
        }
    }

    companion object {
        /** The card's picture square, in dp. */
        const val COVER_DP = 112
        /** The beat between one card settling and the next. */
        private const val BEAT_MS = 160L
        /** How long a card's turn waits for it before the next card goes ahead anyway. */
        private const val TURN_WAIT_MS = 450L
        /** How long a cover gets to arrive before its card settles without it (it still takes its place when it lands). */
        private const val COVER_WAIT_MS = 1500L
    }
}
