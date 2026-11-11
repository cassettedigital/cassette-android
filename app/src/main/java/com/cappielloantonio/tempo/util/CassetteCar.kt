package com.cappielloantonio.tempo.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.cappielloantonio.tempo.subsonic.models.Child
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * cassette: what the car shows (Android Auto), scene for scene the iPhone's CarPlay, in Android
 * Auto's own parts. Every list holds only what is on this phone, because the car plays the
 * phone's own copies (BUG-941) and nothing else. Every picture comes from the phone
 * (CassetteCarArt), so the car shows covers with no signal, as CarPlay does.
 *
 * - Tabs: Home, Albums, Artists, Playlists, each with its glyph.
 * - Home: the cloud's Home, the same rows in the same order and under the same names as the
 *   phone (CassetteHome), each row a titled shelf of covers in one line of words ("On Repeat
 *   Lately"). A cover plays at once (an album from the top, an artist shuffled, a made-for-you
 *   list, a playlist, a station) and the car opens Now Playing: one tap to music behind the wheel.
 *   Resume rides as the first record of On repeat. All shelves is a plain row at the end.
 *   (CarPlay's row title also opens the row as a list; Android Auto's shelf titles can't be
 *   tapped, so a shelf shows up to [HOME_TILES] covers instead of one strip.)
 * - An album: Play (with "Artist · Year") and Shuffle, then its songs, each with its track number
 *   and length (the iPhone's album header and rows).
 * - An artist: Shuffle ("N albums"), Most Played (five at most), their albums as covers that play,
 *   then All Songs.
 * - A playlist: Shuffle, then its songs on this phone in order.
 * - Albums and Artists are A to Z; an artist's row is their photo when the phone has it, else
 *   their record's cover.
 * - An album anywhere plays from the top when tapped (founder, 11-10); its page is reached from
 *   its "Open album" action, or the album line on Now Playing, on cars that offer them.
 */
@OptIn(UnstableApi::class)
object CassetteCar {
    const val HOME = "[carHome]"
    const val ALBUMS = "[carAlbums]"
    const val ARTISTS = "[carArtists]"
    const val PLAYLISTS = "[carPlaylists]"
    private const val PLAYLIST = "[carPlaylist]"
    private const val ALBUM = "[carAlbum]"
    private const val ARTIST = "[carArtist]"
    private const val ARTIST_SONGS = "[carArtistSongs]"
    private const val SHELVES = "[carShelves]"
    private const val SHELF = "[carShelf]"
    private const val MIX = "[carMix]"
    /** A list played in order: PLAY + ALBUM/MIX/PLAYLIST + id. */
    private const val PLAY = "[carPlay]"
    /** A list played shuffled: SHUFFLE + ALBUM/ARTIST/MIX/PLAYLIST + id. */
    private const val SHUFFLE = "[carShuffle]"
    private const val STATION = "cassette-radio-"

    /** Covers a Home shelf shows. */
    private const val HOME_TILES = 8
    private const val TOP_SONGS = 5

    // Android Auto's content style hints (androidx.media.utils.MediaConstants)
    private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    private const val STYLE_SINGLE = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
    private const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    private const val LIST = 1
    private const val GRID = 2

    private val io = Executors.newSingleThreadExecutor()

    /** Whether [id] is one of the car's own lists. */
    @JvmStatic
    fun owns(id: String): Boolean =
        id == HOME || id == ALBUMS || id == ARTISTS || id == PLAYLISTS || id == SHELVES ||
            listOf(PLAYLIST, ALBUM, ARTIST, ARTIST_SONGS, SHELF).any { id.startsWith(it) }

    /** What the car is told at the root: lists by default (the iPhone's rows). */
    @JvmStatic
    fun rootStyle(): Bundle = Bundle().apply {
        putInt(STYLE_BROWSABLE, LIST)
        putInt(STYLE_PLAYABLE, LIST)
    }

    /** One of the four tabs, with its glyph. Home's covers are a grid, its All shelves row a list. */
    @JvmStatic
    fun tab(title: String, id: String, glyph: String): MediaItem = folder(
        title, id, artwork = CassetteCarArt.glyph(glyph, tab = true),
        extras = if (id == HOME) Bundle().apply { putInt(STYLE_PLAYABLE, GRID); putInt(STYLE_BROWSABLE, LIST) } else null,
    )

    /** One of the car's lists, built off the main thread. */
    @JvmStatic
    fun children(context: Context, id: String, repository: AutomotiveRepository): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val app = context.applicationContext
        return later {
            try {
                LibraryResult.ofItemList(ImmutableList.copyOf(build(app, id, repository)), null)
            } catch (e: Exception) {
                android.util.Log.w("CassetteCar", "list $id failed", e)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
            }
        }
    }

    /** Search in the car: what is on this phone (CassetteSearch), records first, then songs. */
    @JvmStatic
    fun search(query: String, repository: AutomotiveRepository): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
        val found = CassetteSearch.search(query)
        val here = found.songs.filter { CassetteOnPhone.playable(it) }
        if (here.isNotEmpty()) repository.setChildrenMetadata(here)
        val items = found.albums.map { albumRow(it, group = "Albums") } +
            here.map { songRow(it, it.artist, CassetteCarArt.cover(it.coverArtId, it.album), group = "Songs") }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), null)
    }

    /** Work the car waits on (lists, and what a tap plays), off the main thread: it reads the phone's database. */
    @JvmStatic
    fun <T> later(work: Callable<T>): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        io.execute {
            try {
                future.set(work.call())
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        return future
    }

    private fun build(app: Context, id: String, repository: AutomotiveRepository): List<MediaItem> = when {
        id == HOME -> home(app)
        id == ALBUMS -> phone().albums.map { albumRow(it) }
        id == ARTISTS -> phone().let { p -> p.artists.map { (key, name) -> artistRow(key, name, p.albumsBy(key)) } }
        id == PLAYLISTS -> playlists().mapNotNull { (p, here) ->
            if (here.isEmpty()) null
            else folder(p.name, PLAYLIST + p.id, MediaMetadata.MEDIA_TYPE_PLAYLIST,
                artwork = CassetteCarArt.web(p.coverImageUrl ?: p.derivedCoverUrl, p.name))
        }
        id == SHELVES -> shelves(app)
        id.startsWith(SHELF) -> shelf(app, id.removePrefix(SHELF))
        id.startsWith(ALBUM) -> albumPage(repository, id.removePrefix(ALBUM))
        id.startsWith(ARTIST_SONGS) -> artistSongs(repository, id.removePrefix(ARTIST_SONGS))
        id.startsWith(ARTIST) -> artistPage(repository, id.removePrefix(ARTIST))
        id.startsWith(PLAYLIST) -> id.removePrefix(PLAYLIST).let { pid ->
            val songs = playlists().firstOrNull { it.first.id == pid }?.second.orEmpty()
            if (songs.isEmpty()) emptyList()
            else {
                repository.setChildrenMetadata(songs)
                listOf(action("Shuffle", SHUFFLE + PLAYLIST + pid, "shuffle")) +
                    songs.map { songRow(it, it.artist, CassetteCarArt.cover(it.coverArtId, it.album)) }
            }
        }
        else -> emptyList()
    }

    // ---- Home ----

    private fun home(app: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        var allShelves: MediaItem? = null
        for (row in homeRows(app)) {
            val title = titleCase(listOfNotNull(row.shout, row.answer).filter { it.isNotBlank() }.joinToString(" "))
            // (the row is only drawn when today has a shelf the phone holds records of)
            if (row.kind == "all-shelves") {
                allShelves = folder(title, SHELVES)
                continue
            }
            tiles(row).take(HOME_TILES).forEach { out.add(grouped(it, title)) }
        }
        allShelves?.let { out.add(it) }
        return out
    }

    /** The rows, with Resume folded into On repeat as its first record (Resume alone keeps its own row). */
    private fun homeRows(app: Context): List<CassetteHome.DrawnRow> {
        val rows = CassetteHome.rowsNow(app)
        val resume = rows.firstOrNull { it.kind == "resume" }
        val repeat = rows.firstOrNull { it.kind == "records" }
        val record = resume?.albums?.firstOrNull()
        if (resume == null || repeat == null || record == null) return rows.filter { it.kind != "resume" || it.albums.isNotEmpty() }
        return rows.filter { it !== resume }.map { row ->
            if (row !== repeat) row
            else CassetteHome.DrawnRow(row.id, row.kind, row.shout, row.answer,
                albums = listOf(record) + row.albums.filter { it.id != record.id })
        }
    }

    /** A Home row's covers, each one tap from playing, as the phone's row draws them. */
    private fun tiles(row: CassetteHome.DrawnRow): List<MediaItem> {
        // the reaching row: its playlist first, then its records (a deep dive: the one record)
        if (row.kind == "reach") {
            if (row.id.startsWith("deep-dive")) return row.albums.take(1).map { albumTile(it) }
            return row.mixes.map { mixTile(it) } + row.albums.map { albumTile(it) }
        }
        val out = ArrayList<MediaItem>()
        row.mixes.forEach { out.add(mixTile(it)) }
        row.albums.forEach { out.add(albumTile(it)) }
        if (row.artists.isNotEmpty()) {
            val phone = phone()
            row.artists.forEach { a ->
                val name = a.name ?: return@forEach
                val key = artistKey(name)
                out.add(playable(name, SHUFFLE + ARTIST + key, MediaMetadata.MEDIA_TYPE_ARTIST,
                    artwork = artistArt(name, phone.albumsBy(key), a.id), menu = listOf(OPEN_ARTIST)))
            }
        }
        row.stations.forEach { out.add(stationTile(it)) }
        // your playlists by the cloud's id (the computer doesn't know it): their songs on this phone
        row.playlists.forEach { p ->
            out.add(playable(p.name ?: "", PLAY + PLAYLIST + p.id, MediaMetadata.MEDIA_TYPE_PLAYLIST,
                artwork = CassetteCarArt.web(p.coverArtId, p.name)))
        }
        return out
    }

    private fun albumTile(album: AlbumID3, subtitle: String? = album.artist): MediaItem =
        playable(album.name ?: "", PLAY + ALBUM + album.id, MediaMetadata.MEDIA_TYPE_ALBUM, subtitle,
            CassetteCarArt.cover(album.coverArtId, album.name), menu = listOf(OPEN_ALBUM))

    /** A made-for-you list: its drawn cover, else its first song's record, as the phone's tile. */
    private fun mixTile(mix: CassetteHome.DrawnMix): MediaItem =
        playable(mix.title, PLAY + MIX + mix.id, MediaMetadata.MEDIA_TYPE_PLAYLIST, "Playlist",
            if (mix.coverUrl != null) CassetteCarArt.web(mix.coverUrl, mix.title)
            else CassetteCarArt.cover(mix.songs.firstOrNull()?.coverArtId, mix.title))

    /**
     * A station plays from its own stream: its name, its network (or "Live" when the network is
     * the station), its logo or its name on its colour. The playing item leaves its title to the
     * live song; the car's list names the station.
     */
    private fun stationTile(s: CassetteRadio.Station): MediaItem {
        val item = MappingUtil.mapInternetRadioStation(s.toInternetRadioStation())
        val line = if (s.label.isNotBlank() && s.label != s.name) s.label else "Live"
        val art = if (s.image.isNullOrEmpty()) CassetteCarArt.word(s.name, s.accentColor()) else CassetteCarArt.web(s.image, s.name)
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setTitle(s.name).setDisplayTitle(s.name).setSubtitle(line).setArtist(line)
            .setArtworkUri(art).setExtras(null)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()).build()
    }

    /** "On Repeat Lately", "Weekday Evenings Around Now": the row's two voices in one line, in title case. */
    private fun titleCase(text: String): String = text.split(" ").filter { it.isNotEmpty() }.mapIndexed { i, word ->
        val lower = word.lowercase()
        if (i > 0 && lower in SMALL_WORDS) lower else word.replaceFirstChar { it.uppercaseChar() }
    }.joinToString(" ")

    private val SMALL_WORDS = setOf("a", "an", "and", "at", "by", "for", "in", "of", "on", "the", "to")

    // ---- All shelves ----

    /** Today's shelves, the one on Home first: a shelf opens as a list of its records. */
    private fun shelves(app: Context): List<MediaItem> = CassetteHome.shelvesNow(app).map { s ->
        val title = titleCase(listOfNotNull(s.shout, s.answer).filter { it.isNotBlank() }.joinToString(" "))
        val n = s.albums.size
        val count = if (n == 1) "1 album" else "$n albums"
        folder(title, SHELF + s.id, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            if (s.onHome) "On Home" else if (s.mix == null) count else "A playlist and $count",
            CassetteCarArt.cover(s.albums.firstOrNull()?.coverArtId, title))
    }

    /** One shelf: its playlist first (plays), then its records (open). */
    private fun shelf(app: Context, id: String): List<MediaItem> {
        // today's shelves are known once Home has been drawn in this run; draw it if not
        val s = CassetteHome.shelf(id) ?: CassetteHome.shelvesNow(app).firstOrNull { it.id == id } ?: return emptyList()
        return listOfNotNull(s.mix?.let { mixTile(it) }) + s.albums.map { albumRow(it) }
    }

    // ---- an album ----

    private fun albumPage(repository: AutomotiveRepository, albumId: String): List<MediaItem> {
        val songs = CassetteRecords.songsOf(albumId).filter { CassetteOnPhone.playable(it) }
        if (songs.isEmpty()) return emptyList()
        repository.setChildrenMetadata(songs)
        val lead = CassetteRecords.lead(songs, albumId)
        val artist = CassetteRecords.albumArtist(songs).ifEmpty { lead.artist ?: "" }
        val line = listOfNotNull(artist.takeIf { it.isNotBlank() }, lead.year?.takeIf { it > 0 }?.toString()).joinToString(" · ")
        return listOf(
            action("Play", PLAY + ALBUM + albumId, "play", line.ifEmpty { null }),
            action("Shuffle", SHUFFLE + ALBUM + albumId, "shuffle"),
        ) + songs.mapIndexed { i, s -> songRow(s, length(s), CassetteCarArt.number(s.track?.takeIf { it > 0 } ?: (i + 1))) }
    }

    private fun length(song: Child): String? {
        val seconds = song.duration ?: return null
        if (seconds <= 0) return null
        return if (seconds >= 3600) String.format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else String.format("%d:%02d", seconds / 60, seconds % 60)
    }

    // ---- an artist ----

    private fun artistPage(repository: AutomotiveRepository, key: String): List<MediaItem> {
        val phone = phone()
        val held = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        // reached from a song's artist line (Now Playing): a singer filed on someone else's
        // record still gets the records their songs are on
        val albums = phone.albumsBy(key).ifEmpty {
            val theirs = held.filter { artistKey(it.artist) == key }.mapNotNullTo(HashSet()) { it.albumId }
            val fold = CassetteRecords.foldMap(held)
            val records = theirs.mapNotNullTo(HashSet()) { CassetteRecords.recordId(fold, it) }
            phone.albums.filter { it.id in records }
        }
        if (albums.isEmpty()) return emptyList()
        val songs = albums.flatMap { CassetteRecords.songsOf(held, it.id!!) }.filter { CassetteOnPhone.playable(it) }
        val out = ArrayList<MediaItem>()
        val n = albums.size
        out.add(action("Shuffle", SHUFFLE + ARTIST + key, "shuffle", if (n == 1) "1 album" else "$n albums"))
        // Most Played: the songs played most, from the phone's own counts; a tap plays from it on
        val top = songs.filter { (it.playCount ?: 0L) > 0L }.sortedByDescending { it.playCount ?: 0L }.take(TOP_SONGS)
        if (top.isNotEmpty()) {
            repository.setChildrenMetadata(top)
            top.forEach { out.add(songRow(it, it.album, CassetteCarArt.cover(it.coverArtId, it.album), group = "Most Played")) }
        }
        // their albums as covers: a cover plays its album from the top
        albums.forEach { a ->
            out.add(grouped(albumTile(a, a.year.takeIf { it > 0 }?.toString()), "Albums", single = GRID))
        }
        out.add(folder("All Songs", ARTIST_SONGS + key, artwork = CassetteCarArt.glyph("songs")))
        return out
    }

    /** All of an artist's songs on this phone, A to Z, Shuffle first. */
    private fun artistSongs(repository: AutomotiveRepository, key: String): List<MediaItem> {
        val held = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        val songs = phone().albumsBy(key).flatMap { CassetteRecords.songsOf(held, it.id!!) }
            .filter { CassetteOnPhone.playable(it) }
            .sortedBy { CassetteLetters.sortKey(it.title) }
        if (songs.isEmpty()) return emptyList()
        repository.setChildrenMetadata(songs)
        return listOf(action("Shuffle", SHUFFLE + ARTIST + key, "shuffle")) +
            songs.map { songRow(it, it.album, CassetteCarArt.cover(it.coverArtId, it.album)) }
    }

    /** An artist's row: their photo when the phone has it (in a circle), else their record's cover. */
    private fun artistRow(key: String, name: String, albums: List<AlbumID3>): MediaItem =
        folder(name, ARTIST + key, MediaMetadata.MEDIA_TYPE_ARTIST, artwork = artistArt(name, albums))

    /**
     * An artist's picture, as the iPhone's artist page: their own photo in a circle when the
     * phone holds it, else the record that lights their page (their newest with a cover),
     * square, else their initial in a circle.
     */
    private fun artistArt(name: String, albums: List<AlbumID3>, artistId: String? = null): Uri? {
        val lead = albums.filter { it.coverArtId != null }.maxByOrNull { it.year }
        val photo = (artistId ?: albums.firstNotNullOfOrNull { it.artistId })?.let { "ar-$it" }
        return CassetteCarArt.cover(photo, name, circle = true, alt = lead?.coverArtId)
    }

    // ---- what a tap plays ----

    /**
     * What the car asked to play, when it is one of the car's own items: a list (Play, a Home
     * cover, Shuffle) as the phone's own copies, or a station (its own stream). Null for anything
     * else, which the session's stored lists answer. Off the main thread (it reads the database).
     */
    @JvmStatic
    fun resolve(context: Context, mediaId: String): List<MediaItem>? {
        if (mediaId.startsWith(STATION)) {
            val station = CassetteRadio.bySlug(context, mediaId.removePrefix(STATION)) ?: return emptyList()
            return listOf(MappingUtil.mapInternetRadioStation(station.toInternetRadioStation()))
        }
        val shuffled = mediaId.startsWith(SHUFFLE)
        if (!shuffled && !mediaId.startsWith(PLAY)) return null
        val list = mediaId.removePrefix(if (shuffled) SHUFFLE else PLAY)
        val songs: List<Child> = when {
            list.startsWith(ALBUM) -> CassetteRecords.songsOf(list.removePrefix(ALBUM))
            list.startsWith(ARTIST) -> list.removePrefix(ARTIST).let { key ->
                val held = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
                phone().albumsBy(key).flatMap { a -> CassetteRecords.songsOf(held, a.id!!) }
            }
            // a made-for-you list is known once Home has been drawn in this run; draw it if not
            list.startsWith(MIX) -> list.removePrefix(MIX).let { id ->
                (CassetteHome.mix(id) ?: CassetteHome.rowsNow(context).let { CassetteHome.mix(id) })?.songs.orEmpty()
            }
            list.startsWith(PLAYLIST) -> playlists().firstOrNull { it.first.id == list.removePrefix(PLAYLIST) }?.second.orEmpty()
            else -> emptyList()
        }
        val here = songs.filter { CassetteOnPhone.playable(it) }
        // the player's own items (the phone's copy, with the song's details the player reads),
        // never the downloader's (mapDownloads carries no details: the player crashed on the
        // first song, reading its id to log the play)
        return MappingUtil.mapMediaItems(if (shuffled) here.shuffled() else here)
    }

    // ---- playlists: yours, from the cloud, each with its songs on this phone in order ----

    @Volatile private var lastPlaylists: List<CassetteSyncApi.CloudPlaylist> = emptyList()

    private fun playlists(): List<Pair<CassetteSyncApi.CloudPlaylist, List<Child>>> {
        val bearer = Preferences.getCassetteBearerToken()
        if (bearer != null) {
            try {
                lastPlaylists = CassetteSyncApi.listPlaylists(bearer, true)
            } catch (e: Exception) {
                android.util.Log.w("CassetteCar", "playlists: keeping the last list", e)
            }
        }
        val dao = AppDatabase.getInstance().downloadDao()
        val wanted = lastPlaylists.flatMap { p -> p.items.mapNotNull { it.cassetteLocalId } }.distinct()
        val held = HashMap<String, Child>()
        wanted.chunked(500).forEach { chunk -> dao.getByCassetteLocalIds(chunk).forEach { d -> d.cassetteLocalId?.let { held[it] = d } } }
        return lastPlaylists.map { p ->
            p to p.items.sortedBy { it.position }.mapNotNull { held[it.cassetteLocalId] }.filter { CassetteOnPhone.playable(it) }
        }
    }

    // ---- what is on this phone ----

    private class Phone(val albums: List<AlbumID3>, val artists: List<Pair<String, String>>) {
        fun albumsBy(key: String): List<AlbumID3> = albums.filter { artistKey(it.artist) == key }
    }

    /** Every record on this phone, A to Z, and every artist with one, A to Z. */
    private fun phone(): Phone {
        val held: List<Download> = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
        val fold = CassetteRecords.foldMap(held)
        val byAlbum = held
            .filter { it.albumId != null }
            .groupBy { CassetteRecords.recordId(fold, it.albumId)!! }
        val albums = byAlbum.map { (albumId, songs) ->
            val first = CassetteRecords.lead(songs, albumId)
            val artist = CassetteRecords.albumArtist(songs)
            AlbumID3().apply {
                id = albumId; name = first.album; this.artist = artist.ifEmpty { first.artist }
                artistId = first.artistId; coverArtId = first.coverArtId; year = first.year ?: 0
            }
        }.sortedBy { CassetteLetters.sortKey(it.name) }
        val artists = LinkedHashMap<String, String>()
        albums.forEach { a ->
            val name = a.artist ?: return@forEach
            val key = artistKey(name)
            if (key.isNotEmpty() && key != "various artists") artists.putIfAbsent(key, name)
        }
        return Phone(albums, artists.entries.map { it.key to it.value }.sortedBy { CassetteLetters.sortKey(it.second) })
    }

    private fun artistKey(name: String?): String = CassetteLikeness.normalizedArtistName(name)

    // ---- the car's rows ----

    /** A folder the car can open. */
    @JvmStatic
    @JvmOverloads
    fun folder(title: String, id: String, type: @MediaMetadata.MediaType Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
               subtitle: String? = null, artwork: Uri? = null, extras: Bundle? = null): MediaItem =
        item(title, id, type, subtitle, artwork, browsable = true, extras = extras)

    /** Something the car plays at once; [menu] are its "Open album" / "Open artist" actions. */
    private fun playable(title: String, id: String, type: @MediaMetadata.MediaType Int, subtitle: String? = null,
                         artwork: Uri? = null, menu: List<String> = emptyList()): MediaItem =
        item(title, id, type, subtitle, artwork, browsable = false, extras = null, menu = menu)

    /** Play / Shuffle / the like: a glyph and the words, played at once. */
    private fun action(title: String, id: String, glyph: String, subtitle: String? = null): MediaItem =
        playable(title, id, MediaMetadata.MEDIA_TYPE_PLAYLIST, subtitle, CassetteCarArt.glyph(glyph))

    private fun item(title: String, id: String, type: @MediaMetadata.MediaType Int, subtitle: String?, artwork: Uri?,
                     browsable: Boolean, extras: Bundle?, menu: List<String> = emptyList()): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    // the display title is what makes the car show the subtitle as written
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(browsable)
                    .setIsPlayable(!browsable)
                    .setArtworkUri(artwork)
                    .setMediaType(type)
                    .setExtras(extras)
                    .setSupportedCommands(menu)
                    .build()
            )
            .build()

    /**
     * An album's row: its cover, name and artist. A tap plays it from the top (founder, 11-10:
     * tapping an album plays it); its page is one step away, from its "Open album" action or the
     * album line on Now Playing, where the car offers them.
     */
    private fun albumRow(album: AlbumID3, group: String? = null): MediaItem {
        val row = albumTile(album)
        return if (group != null) grouped(row, group) else row
    }

    // ---- getting to a page from a tap that plays ----

    /** The car's "Open album" action, on every album that plays when tapped. */
    const val OPEN_ALBUM = "digital.cassette.car.OPEN_ALBUM"
    /** The car's "Open artist" action, on Home's artists (which play shuffled when tapped). */
    const val OPEN_ARTIST = "digital.cassette.car.OPEN_ARTIST"

    /** The page an "Open album" / "Open artist" action on [mediaId] goes to, or null. */
    @JvmStatic
    fun pageFor(mediaId: String?): String? {
        val list = mediaId?.removePrefix(PLAY)?.removePrefix(SHUFFLE) ?: return null
        return if (list.startsWith(ALBUM) || list.startsWith(ARTIST)) list else null
    }

    private const val LINK_SUBTITLE = "androidx.car.app.mediaextensions.KEY_SUBTITLE_LINK_MEDIA_ID"
    private const val LINK_DESCRIPTION = "androidx.car.app.mediaextensions.KEY_DESCRIPTION_LINK_MEDIA_ID"

    /**
     * A song the car starts, with its artist line linked to the artist's page and its album line
     * to the album's page, for cars that let Now Playing's lines be tapped (Android's car
     * extensions). The words shown don't change: the artist stays the second line.
     */
    @JvmStatic
    fun linked(item: MediaItem): MediaItem {
        val extras = item.mediaMetadata.extras ?: return item
        if (extras.getString("type") != Constants.MEDIA_TYPE_MUSIC) return item
        val albumId = extras.getString("albumId")
        val artist = extras.getString("artist")
        val out = Bundle(extras)
        if (!artist.isNullOrBlank()) out.putString(LINK_SUBTITLE, ARTIST + artistKey(artist))
        if (!albumId.isNullOrBlank()) out.putString(LINK_DESCRIPTION, ALBUM + albumId)
        val m = item.mediaMetadata.buildUpon().setExtras(out)
        if (!artist.isNullOrBlank()) m.setSubtitle(artist)
        item.mediaMetadata.albumTitle?.let { m.setDescription(it) }
        return item.buildUpon().setMediaMetadata(m.build()).build()
    }

    /** One of the car's pages by id, for a linked line on Now Playing (the car asks for it by id). */
    @JvmStatic
    fun itemFor(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = later {
        val page: MediaItem? = when {
            mediaId.startsWith(ALBUM) -> {
                val songs = CassetteRecords.songsOf(mediaId.removePrefix(ALBUM))
                songs.firstOrNull()?.let { lead ->
                    folder(lead.album ?: "", mediaId, MediaMetadata.MEDIA_TYPE_ALBUM,
                        CassetteRecords.albumArtist(songs).ifEmpty { lead.artist ?: "" }.ifEmpty { null },
                        CassetteCarArt.cover(lead.coverArtId, lead.album))
                }
            }
            mediaId.startsWith(ARTIST) -> {
                val key = mediaId.removePrefix(ARTIST)
                phone().artists.firstOrNull { it.first == key }?.let { (_, name) -> folder(name, mediaId, MediaMetadata.MEDIA_TYPE_ARTIST) }
            }
            else -> null
        }
        if (page != null) LibraryResult.ofItem(page, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    /**
     * One song the car plays from, then on through its list (the list it was registered with in
     * the session's store). The row's second line and picture are the list's own: a track number
     * and length on an album, a cover and artist elsewhere. Nothing of the song rides in the row
     * but its id; the store holds what plays.
     */
    private fun songRow(song: Child, line: String?, artwork: Uri?, group: String? = null): MediaItem {
        val row = playable(song.title ?: "", song.id ?: "", MediaMetadata.MEDIA_TYPE_MUSIC, line, artwork)
        return if (group != null) grouped(row, group) else row
    }

    /** [item] under a titled group (a Home shelf, Most Played, Albums), drawn as [single] when given. */
    private fun grouped(item: MediaItem, title: String, single: Int? = null): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putString(GROUP_TITLE, title)
            if (single != null) putInt(STYLE_SINGLE, single)
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }
}
