package com.cappielloantonio.tempo.util

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.database.AppDatabase
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.cappielloantonio.tempo.subsonic.models.Child
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * cassette: what the car shows (Android Auto), the iPhone's CarPlay (One Home, 10-28):
 * Home, Albums, Artists and Playlists, and every one of them holds only what is on this
 * phone, because the car plays the phone's own copies (BUG-941) and nothing else.
 *
 * Home is the cloud's Home, the same rows in the same order and under the same names as
 * the phone (CassetteHome), each row holding what the phone holds. Resume has no card in
 * the car: its record rides as the first tile of On repeat. Radio stays on the phone.
 * Albums are A to Z; Artists are grouped by name ("feat." set aside), each opening their
 * records here. A record opens to its songs here; a tap plays from that song on.
 */
@OptIn(UnstableApi::class)
object CassetteCar {
    const val HOME = "[carHome]"
    const val ALBUMS = "[carAlbums]"
    const val ARTISTS = "[carArtists]"
    const val PLAYLISTS = "[carPlaylists]"
    private const val PLAYLIST = "[carPlaylist]"
    private const val ROW = "[carRow]"
    private const val ALBUM = "[carAlbum]"
    private const val ARTIST = "[carArtist]"
    private const val MIX = "[carMix]"

    private val io = Executors.newSingleThreadExecutor()

    /** Whether [id] is one of the car's own lists. */
    @JvmStatic
    fun owns(id: String): Boolean =
        id == HOME || id == ALBUMS || id == ARTISTS || id == PLAYLISTS || id.startsWith(PLAYLIST) ||
            id.startsWith(ROW) || id.startsWith(ALBUM) || id.startsWith(ARTIST) || id.startsWith(MIX)

    /** A folder the car can open. */
    @JvmStatic
    @JvmOverloads
    fun folder(title: String, id: String, type: @MediaMetadata.MediaType Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
               subtitle: String? = null, artwork: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setArtworkUri(artwork)
                    .setMediaType(type)
                    .build()
            )
            .setUri("")
            .build()

    /** One of the car's lists, built off the main thread. */
    @JvmStatic
    fun children(context: Context, id: String, repository: AutomotiveRepository): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val app = context.applicationContext
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        io.execute {
            future.set(
                try {
                    LibraryResult.ofItemList(ImmutableList.copyOf(build(app, id, repository)), null)
                } catch (e: Exception) {
                    android.util.Log.w("CassetteCar", "list $id failed", e)
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            )
        }
        return future
    }

    /** Search in the car: what is on this phone (CassetteSearch), records first, then songs. */
    @JvmStatic
    fun search(query: String, repository: AutomotiveRepository): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        io.execute {
            val found = CassetteSearch.search(query)
            val items = found.albums.map(::albumFolder) + songs(repository, found.songs)
            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), null))
        }
        return future
    }

    private fun build(app: Context, id: String, repository: AutomotiveRepository): List<MediaItem> = when {
        id == HOME -> homeRows(app).map { row -> folder(row.shout, ROW + row.id, subtitle = row.answer?.takeIf { it.isNotBlank() }) }
        id == ALBUMS -> phone().albums.map(::albumFolder)
        id == ARTISTS -> phone().artists.map { (key, name) -> folder(name, ARTIST + key, MediaMetadata.MEDIA_TYPE_ARTIST) }
        id.startsWith(ROW) -> rowItems(app, id.removePrefix(ROW))
        id.startsWith(ALBUM) -> songs(repository, AppDatabase.getInstance().downloadDao().getAlbumSync(id.removePrefix(ALBUM)))
        id.startsWith(ARTIST) -> id.removePrefix(ARTIST).let { key -> phone().albums.filter { artistKey(it.artist) == key }.map(::albumFolder) }
        id.startsWith(MIX) -> songs(repository, CassetteHome.mix(id.removePrefix(MIX))?.songs.orEmpty())
        id == PLAYLISTS -> playlists().mapNotNull { (p, here) ->
            if (here.isEmpty()) null
            else folder(p.name, PLAYLIST + p.id, MediaMetadata.MEDIA_TYPE_PLAYLIST,
                app.resources.getQuantityString(com.cappielloantonio.tempo.R.plurals.cassette_song_count, here.size, here.size),
                cover(p.coverImageUrl ?: p.derivedCoverUrl))
        }
        id.startsWith(PLAYLIST) -> id.removePrefix(PLAYLIST).let { pid ->
            songs(repository, playlists().firstOrNull { it.first.id == pid }?.second.orEmpty())
        }
        else -> emptyList()
    }

    // ---- Home ----

    /** The rows, with Resume folded into On repeat as its first record. */
    private fun homeRows(app: Context): List<CassetteHome.DrawnRow> {
        val rows = CassetteHome.rowsNow(app).filter { it.kind != "stations" }
        val resume = rows.firstOrNull { it.kind == "resume" }?.albums?.firstOrNull()
        return rows.filter { it.kind != "resume" }.map { row ->
            if (resume != null && row.kind == "records" && row === rows.first { it.kind == "records" }) {
                CassetteHome.DrawnRow(row.id, row.kind, row.shout, row.answer,
                    albums = listOf(resume) + row.albums.filter { it.id != resume.id })
            } else row
        }
    }

    private fun rowItems(app: Context, rowId: String): List<MediaItem> {
        val row = homeRows(app).firstOrNull { it.id == rowId } ?: return emptyList()
        val out = ArrayList<MediaItem>()
        row.albums.forEach { out.add(albumFolder(it)) }
        row.mixes.forEach { m ->
            out.add(folder(m.title, MIX + m.id, MediaMetadata.MEDIA_TYPE_PLAYLIST, m.subtitle, m.coverUrl?.let(Uri::parse)))
        }
        // your playlists by the cloud's id (the computer doesn't know it): their songs on this phone
        row.playlists.forEach { p ->
            out.add(folder(p.name ?: "", PLAYLIST + p.id, MediaMetadata.MEDIA_TYPE_PLAYLIST, artwork = cover(p.coverArtId)))
        }
        row.artists.forEach { a ->
            val name = a.name ?: return@forEach
            out.add(folder(name, ARTIST + artistKey(name), MediaMetadata.MEDIA_TYPE_ARTIST))
        }
        return out
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

    /** A cover address the car can fetch: the site's own paths made whole. */
    private fun cover(url: String?): Uri? = when {
        url.isNullOrEmpty() -> null
        url.startsWith("/") -> Uri.parse("https://cassette.digital$url")
        url.startsWith("http") -> Uri.parse(url)
        else -> Uri.parse(CustomGlideRequest.createUrl(url, Preferences.getImageSize()))
    }

    // ---- what is on this phone ----

    private class Phone(val albums: List<AlbumID3>, val artists: List<Pair<String, String>>)

    /** Every record on this phone, A to Z, and every artist with one, A to Z. */
    private fun phone(): Phone {
        val byAlbum = AppDatabase.getInstance().downloadDao().getAllDownloadedSync()
            .filter { it.albumId != null }
            .groupBy { it.albumId!! }
        val albums = byAlbum.map { (albumId, songs) ->
            val first = songs.first()
            val artist = CassetteAlbumGroupKey.effectiveAlbumArtist(songs.mapNotNull { it.artist?.let(CassetteAlbumGroupKey::stripFeatured) })
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

    private fun albumFolder(album: AlbumID3): MediaItem =
        folder(album.name ?: "", ALBUM + album.id, MediaMetadata.MEDIA_TYPE_ALBUM, album.artist,
            album.coverArtId?.let { Uri.parse(CustomGlideRequest.createUrl(it, Preferences.getImageSize())) })

    /** Songs the car can play: only those on this phone, remembered so a tap plays from it on. */
    private fun songs(repository: AutomotiveRepository, list: List<Child>): List<MediaItem> {
        val here = list.filter { CassetteOnPhone.playable(it) }
        if (here.isEmpty()) return emptyList()
        repository.setChildrenMetadata(here)
        return MappingUtil.mapDownloads(here)
    }
}
