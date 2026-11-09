package com.cappielloantonio.tempo.util

import java.text.Normalizer
import kotlin.math.sqrt

/**
 * cassette: how alike two records are, ported from the iPhone's ListeningShelves
 * (AmperfyKit/Home/ListeningShelves.swift, "Mood matching" and "More like"). Pure:
 * no Android, no I/O, so the same tags score the same here as on the iPhone.
 *
 * A song's tags are weighted tokens: each mood 1, energy and valence a half, each
 * subgenre of its sound 1 ("sound:<subgenre>"), each scenario 1 ("scenario:<x>",
 * which no likeness reads). Two records are alike on mood (the cosine of the moods,
 * energy and valence) and, where both carry sound, on sound (the cosine of the
 * subgenres); the score is the mean of the two, or the mood alone.
 */
object CassetteLikeness {
    const val SOUND = "sound:"
    const val SCENARIO = "scenario:"

    fun tagVector(moods: List<String>, energy: String?, valence: String?,
                  sound: List<String> = emptyList(), scenarios: List<String> = emptyList()): Map<String, Double> {
        val v = HashMap<String, Double>()
        for (m in moods) v["mood:$m"] = (v["mood:$m"] ?: 0.0) + 1
        if (!energy.isNullOrEmpty()) v["energy:$energy"] = (v["energy:$energy"] ?: 0.0) + 0.5
        if (!valence.isNullOrEmpty()) v["valence:$valence"] = (v["valence:$valence"] ?: 0.0) + 0.5
        for (s in sound) if (s.isNotEmpty()) v["$SOUND$s"] = 1.0
        for (s in scenarios) if (s.isNotEmpty()) v["$SCENARIO$s"] = 1.0
        return v
    }

    fun add(v: Map<String, Double>, into: MutableMap<String, Double>, weight: Double = 1.0) {
        for ((k, x) in v) into[k] = (into[k] ?: 0.0) + x * weight
    }

    fun sum(vectors: List<Map<String, Double>>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (v in vectors) add(v, out)
        return out
    }

    /** Nine places: far below any bar's meaning, far above summing noise (BUG-494). */
    fun rounded(x: Double): Double = Math.round(x * 1e9) / 1e9

    /** A tag set ready to compare: its keys in order, its norms summed once in that order. */
    class Prepared(val tags: Map<String, Double>) {
        val keys: List<String> = tags.keys.sorted()
        val moodNorm: Double
        val soundNorm: Double

        init {
            var mood = 0.0
            var sound = 0.0
            for (k in keys) {
                val x = tags.getValue(k)
                if (k.startsWith(SOUND)) sound += x * x
                else if (!k.startsWith(SCENARIO)) mood += x * x
            }
            moodNorm = sqrt(mood)
            soundNorm = sqrt(sound)
        }
    }

    class Bar(val mood: Double, val sound: Double, val score: Double, val moodOnly: Double)

    /** Another record against a page's record, artist or playlist (MORE LIKE). */
    fun recordBar(moodOnly: Double) = Bar(mood = 0.45, sound = 0.25, score = 0.55, moodOnly = moodOnly)

    class Likeness(val mood: Double, val sound: Double?) {
        val score: Double get() = if (sound == null) mood else rounded((mood + sound) / 2)
        fun passes(bar: Bar): Boolean =
            if (sound != null) mood >= bar.mood && sound >= bar.sound && score >= bar.score
            else mood >= bar.moodOnly
    }

    fun likeness(a: Prepared, b: Prepared): Likeness {
        val small = if (a.keys.size <= b.keys.size) a else b
        val large = if (small === a) b else a
        var moodDot = 0.0
        var soundDot = 0.0
        for (k in small.keys) {
            val y = large.tags[k] ?: continue
            if (k.startsWith(SOUND)) soundDot += small.tags.getValue(k) * y
            else if (!k.startsWith(SCENARIO)) moodDot += small.tags.getValue(k) * y
        }
        val mood = if (a.moodNorm > 0 && b.moodNorm > 0) rounded(moodDot / (a.moodNorm * b.moodNorm)) else 0.0
        val sound = if (a.soundNorm > 0 && b.soundNorm > 0) rounded(soundDot / (a.soundNorm * b.soundNorm)) else null
        return Likeness(mood, sound)
    }

    /** "Björk " and "bjork" are one artist. */
    @JvmStatic
    fun normalizedArtistName(s: String?): String =
        Normalizer.normalize((s ?: "").trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()

    /**
     * An interlude, intro or sketch rather than a song (BUG-496): under a minute, or
     * "interlude" / "skit" / "segue" in the title, or a short one (under two and a half
     * minutes) titled intro, outro, reprise and the like, or wholly in brackets. 0 = unknown.
     */
    @JvmStatic
    fun isInterlude(title: String?, duration: Int): Boolean {
        if (duration in 1..59) return true
        val text = (title ?: "").lowercase()
        val words = text.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.toSet()
        if (words.any { it in setOf("interlude", "skit", "segue") }) return true
        if (!(duration in 1..149)) return false
        if (words.any { it in setOf("intro", "introduction", "outro", "reprise", "instrumental", "untitled", "prelude", "overture", "foreword") }) return true
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return "([{".contains(trimmed.first()) && ")]}".contains(trimmed.last())
    }

    /** One record on the phone: its id, whose it is (normalised name) and each song's tags. */
    class Candidate(val albumId: String, val artistKey: String, val songTags: List<Map<String, Double>>) {
        val summed: Map<String, Double> by lazy { sum(songTags) }
    }

    /**
     * MORE LIKE for any tag profile (a record's or a playlist's songs): records whose summed
     * tags match it (recordBar; [minMatch] when either side has no sound), best first, ties
     * by id, at most [perArtist] per artist, leaving out [excluding]. Returns album ids.
     */
    fun moreLike(profile: Map<String, Double>, albums: List<Candidate>, excluding: Set<String>,
                 minMatch: Double = 0.7, perArtist: Int = 1, limit: Int = 8): List<String> {
        if (profile.isEmpty()) return emptyList()
        val prepared = Prepared(profile)
        val bar = recordBar(minMatch)
        val scored = albums.mapNotNull { album ->
            if (album.albumId in excluding || album.songTags.isEmpty()) return@mapNotNull null
            val match = likeness(Prepared(album.summed), prepared)
            if (match.passes(bar)) album to match.score else null
        }.sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.albumId })
        val perArtistCount = HashMap<String, Int>()
        val picked = ArrayList<String>()
        for ((album, _) in scored) {
            if (picked.size >= limit) break
            val n = (perArtistCount[album.artistKey] ?: 0) + 1
            perArtistCount[album.artistKey] = n
            if (n <= perArtist) picked.add(album.albumId)
        }
        return picked
    }

    /**
     * MORE LIKE on an artist page: other artists on the phone whose music carries the same
     * moods and sound, each shown by their record with the most tag weight, best first, ties
     * by name. The page artist and Various Artists are left out. Returns album ids.
     */
    fun moreLikeArtist(pageArtistName: String, albums: List<Candidate>,
                       minMatch: Double = 0.75, limit: Int = 8): List<String> {
        val page = normalizedArtistName(pageArtistName)
        if (page.isEmpty()) return emptyList()
        val vectorByArtist = HashMap<String, MutableMap<String, Double>>()
        val tileByArtist = HashMap<String, Pair<String, Double>>()
        for (album in albums.sortedBy { it.albumId }) {
            if (album.songTags.isEmpty() || album.artistKey.isEmpty()) continue
            val v = album.summed
            add(v, vectorByArtist.getOrPut(album.artistKey) { HashMap() })
            val weight = rounded(v.keys.sorted().sumOf { v.getValue(it) })
            val tile = tileByArtist[album.artistKey]
            if (tile != null && (tile.second > weight || (tile.second == weight && tile.first < album.albumId))) continue
            tileByArtist[album.artistKey] = album.albumId to weight
        }
        val pageVector = vectorByArtist[page]
        if (pageVector.isNullOrEmpty()) return emptyList()
        val prepared = Prepared(pageVector)
        val bar = recordBar(minMatch)
        return vectorByArtist.mapNotNull { (name, vector) ->
            if (name == page || name == "various artists") return@mapNotNull null
            val tile = tileByArtist[name] ?: return@mapNotNull null
            val match = likeness(Prepared(vector), prepared)
            if (match.passes(bar)) Triple(name, tile.first, match.score) else null
        }.sortedWith(compareByDescending<Triple<String, String, Double>> { it.third }.thenBy { it.first })
            .take(limit)
            .map { it.second }
    }
}
