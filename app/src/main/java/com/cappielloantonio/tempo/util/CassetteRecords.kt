package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download

/**
 * cassette: what a RECORD is on this phone (BUG-970, BUG-979).
 *
 * The computer hands every song the id of the album it filed it under. Most of the time that
 * is the record, but not always: one song in a folder whose tags differ a little from the rest
 * (another release date, another MusicBrainz id) gets an album id of its own, and the phone then
 * showed the record twice, an 18-song one and a 1-song one, while the web (which groups by name)
 * showed it once. And the computer sends each song's album-artist tag, which the web reads before
 * it counts who sings the most, while the phone used to count only.
 *
 * Every place that lists, counts or opens records reads them through here, so one rule answers
 * everywhere:
 * - [albumArtist]: the album-artist tag when the songs that carry one agree, else the artist most
 *   of the songs are by, else "Various Artists" (the web's effectiveAlbumArtist).
 * - [foldMap]: which album ids are really one record. Songs fold together when the record name and
 *   its album artist are the same AND they cannot be two editions of it: no song slot (disc and
 *   track number) is taken twice, and the years are within a year of each other. Hozier's 2014 and
 *   2025 "Hozier" are two records (the same slots twice, eleven years apart); a Laufey record with
 *   one bonus song tagged apart is one. The record's id is the id the most of its songs carry, so
 *   an id already out there (a saved Home shelf, a bookmark) keeps opening it.
 *
 * Pure over [Download] rows, so the rule is unit-tested without a phone. Nothing here writes: the
 * files' tags and the stored rows are never changed, only how the phone reads them.
 */
object CassetteRecords {
    private const val SEP = '\u0000'

    /** The record's artist: the web's rule over these songs' album-artist tags and track artists. */
    @JvmStatic
    fun albumArtist(songs: List<Download>): String = CassetteAlbumGroupKey.effectiveAlbumArtist(
        songs.mapNotNull { it.artist?.let(CassetteAlbumGroupKey::stripFeatured) },
        songs.map { it.albumArtist },
    )

    /**
     * The album ids that fold into another record: folded id to the id of the record it joins.
     * Ids that stand on their own are not in the map, so [recordId] answers an id with itself.
     */
    @JvmStatic
    fun foldMap(songs: List<Download>): Map<String, String> {
        val byAlbum = LinkedHashMap<String, MutableList<Download>>()
        for (song in songs) {
            val id = song.albumId ?: continue
            byAlbum.getOrPut(id) { ArrayList() }.add(song)
        }
        if (byAlbum.size < 2) return emptyMap()

        // the web's group key: normalised artist + normalised record name
        val byName = LinkedHashMap<String, MutableList<Group>>()
        for ((id, group) in byAlbum) {
            val name = group.firstNotNullOfOrNull { it.album?.takeIf { n -> n.isNotBlank() } } ?: continue
            val key = CassetteAlbumGroupKey.normalizeArtist(albumArtist(group)) + SEP + CassetteAlbumGroupKey.normalizeTitle(name)
            byName.getOrPut(key) { ArrayList() }.add(Group(id, group))
        }

        val folded = HashMap<String, String>()
        for (groups in byName.values) {
            if (groups.size < 2) continue
            // the biggest first, so it names the record; a tie keeps the order the songs came in
            val clusters = ArrayList<Cluster>()
            for (group in groups.sortedByDescending { it.songs.size }) {
                val cluster = clusters.firstOrNull { it.accepts(group) }
                if (cluster == null) {
                    clusters.add(Cluster(group))
                } else {
                    cluster.add(group)
                    folded[group.id] = cluster.id
                }
            }
        }
        return folded
    }

    /** The record an album id belongs to: the id it folds into, else itself. */
    @JvmStatic
    fun recordId(fold: Map<String, String>, albumId: String?): String? = albumId?.let { fold[it] ?: it }

    /** The song a record looks like (its name, cover, year): one carrying the record's own id, not a song folded into it. */
    @JvmStatic
    fun lead(songs: List<Download>, recordId: String): Download = songs.firstOrNull { it.albumId == recordId } ?: songs.first()

    /** How many records these songs make up. */
    @JvmStatic
    fun recordCount(songs: List<Download>): Int {
        val fold = foldMap(songs)
        return songs.mapNotNullTo(HashSet()) { recordId(fold, it.albumId) }.size
    }

    /** One record's songs in play order, from songs already read. [albumId] may be any id the record answers to. */
    @JvmStatic
    fun songsOf(all: List<Download>, albumId: String): List<Download> {
        val fold = foldMap(all)
        val want = recordId(fold, albumId)
        return all.filter { recordId(fold, it.albumId) == want }
            .sortedWith(compareBy<Download> { it.discNumber ?: 0 }.thenBy { it.track ?: 0 })
    }

    /** One record's songs on this phone, in play order (the page, play, Resume, the car, Remove). Off the main thread. */
    @JvmStatic
    fun songsOf(albumId: String): List<Download> =
        songsOf(AppDatabase.getInstance().downloadDao().getAllDownloadedSync(), albumId)

    /** The songs under one album id as the computer filed them: the group the fold is decided over. */
    private class Group(val id: String, val songs: List<Download>) {
        /** The year most of the songs carry, if any carries one. */
        val year: Int? = songs.mapNotNull { it.year?.takeIf { y -> y > 0 } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        /** The (disc, track) slots the songs hold; a song with no track number holds none. */
        val slots: Set<Long> = songs.mapNotNull { s ->
            s.track?.takeIf { it > 0 }?.let { t -> (s.discNumber?.takeIf { d -> d > 0 } ?: 1).toLong() * 100_000L + t }
        }.toHashSet()
    }

    /** A record in the making: the biggest group, and the groups folded into it. */
    private class Cluster(first: Group) {
        val id = first.id
        private val year = first.year
        private val slots = HashSet(first.slots)

        fun accepts(g: Group): Boolean =
            g.slots.none { it in slots } && (year == null || g.year == null || kotlin.math.abs(year - g.year) <= 1)

        fun add(g: Group) {
            slots.addAll(g.slots)
        }
    }
}
