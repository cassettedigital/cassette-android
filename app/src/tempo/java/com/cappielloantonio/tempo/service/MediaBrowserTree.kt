package com.cappielloantonio.tempo.service

import com.cappielloantonio.tempo.util.CassetteCar
import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.util.Preferences.getServerId
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

object MediaBrowserTree {

    private lateinit var automotiveRepository: AutomotiveRepository

    private var treeNodes: MutableMap<String, MediaItemNode> = mutableMapOf()

    private var isInitialized = false

    // Root
    private const val ROOT_ID = "[rootID]"

    // First level
    private const val HOME_ID = "[homeID]"
    private const val LIBRARY_ID = "[libraryID]"
    private const val OTHER_ID = "[otherID]"

    // Second level HOME_ID
    private const val MOST_PLAYED_ID = "[mostPlayedID]"
    private const val LAST_PLAYED_ID = "[lastPlayedID]"
    private const val RECENTLY_ADDED_ID = "[recentlyAddedID]"
    private const val RECENT_SONGS_ID = "[recentSongsID]"
    private const val MADE_FOR_YOU_ID = "[madeForYouID]"
    private const val STARRED_TRACKS_ID = "[starredTracksID]"
    private const val STARRED_ALBUMS_ID = "[starredAlbumsID]"
    private const val STARRED_ARTISTS_ID = "[starredArtistsID]"
    private const val RANDOM_ID = "[randomID]"

    // Second level LIBRARY_ID
    private const val FOLDER_ID = "[folderID]"
    private const val INDEX_ID = "[indexID]"
    private const val DIRECTORY_ID = "[directoryID]"
    private const val PLAYLIST_ID = "[playlistID]"

    // Second level OTHER_ID
    private const val PODCAST_ID = "[podcastID]"
    private const val RADIO_ID = "[radioID]"

    private const val ALBUM_ID = "[albumID]"
    private const val ARTIST_ID = "[artistID]"

    private class MediaItemNode(val item: MediaItem) {
        private val children: MutableList<MediaItem> = ArrayList()

        fun addChild(childID: String) {
            this.children.add(treeNodes[childID]!!.item)
        }

        fun getChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val listenableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val libraryResult = LibraryResult.ofItemList(children, null)

            listenableFuture.set(libraryResult)

            return listenableFuture
        }
    }

    private fun buildMediaItem(
        title: String,
        mediaId: String,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        mediaType: @MediaMetadata.MediaType Int,
        subtitleConfigurations: List<SubtitleConfiguration> = mutableListOf(),
        album: String? = null,
        artist: String? = null,
        genre: String? = null,
        sourceUri: Uri? = null,
        imageUri: Uri? = null
    ): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setAlbumTitle(album)
                .setTitle(title)
                .setArtist(artist)
                .setGenre(genre)
                .setIsBrowsable(isBrowsable)
                .setIsPlayable(isPlayable)
                .setArtworkUri(imageUri)
                .setMediaType(mediaType)
                .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(metadata)
            .setUri(sourceUri)
            .build()
    }

    fun initialize(automotiveRepository: AutomotiveRepository) {
        this.automotiveRepository = automotiveRepository

        if (isInitialized) return

        isInitialized = true

        // Root level

        treeNodes[ROOT_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Root Folder",
                    mediaId = ROOT_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        // First level

        treeNodes[HOME_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Home",
                    mediaId = HOME_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[LIBRARY_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Library",
                    mediaId = LIBRARY_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[OTHER_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Other",
                    mediaId = OTHER_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        // cassette: the car's four tabs, the iPhone's CarPlay: Home, Albums, Artists and
        // Playlists, each with its glyph and holding only what is on this phone (CassetteCar).
        // Tempo's own Home / Library / Other browsed the computer's whole library and its podcasts.
        treeNodes[CassetteCar.HOME] = MediaItemNode(CassetteCar.tab("Home", CassetteCar.HOME, "home"))
        treeNodes[CassetteCar.ALBUMS] = MediaItemNode(CassetteCar.tab("Albums", CassetteCar.ALBUMS, "albums"))
        treeNodes[CassetteCar.ARTISTS] = MediaItemNode(CassetteCar.tab("Artists", CassetteCar.ARTISTS, "artists"))
        treeNodes[CassetteCar.PLAYLISTS] = MediaItemNode(CassetteCar.tab("Playlists", CassetteCar.PLAYLISTS, "playlists"))

        // Second level HOME_ID

        treeNodes[MOST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Most played",
                    mediaId = MOST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[LAST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Last played",
                    mediaId = LAST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[RECENTLY_ADDED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Recently added",
                    mediaId = RECENTLY_ADDED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[RECENT_SONGS_ID] =
                MediaItemNode(
                        buildMediaItem(
                                title = "Recent songs",
                                mediaId = RECENT_SONGS_ID,
                                isPlayable = false,
                                isBrowsable = true,
                                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        )
                )

        treeNodes[MADE_FOR_YOU_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Made for you",
                    mediaId = MADE_FOR_YOU_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[STARRED_TRACKS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Starred tracks",
                    mediaId = STARRED_TRACKS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[STARRED_ALBUMS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Starred albums",
                    mediaId = STARRED_ALBUMS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[STARRED_ARTISTS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Starred artists",
                    mediaId = STARRED_ARTISTS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                )
            )

        treeNodes[RANDOM_ID] =
                MediaItemNode(
                        buildMediaItem(
                                title = "Random",
                                mediaId = RANDOM_ID,
                                isPlayable = false,
                                isBrowsable = true,
                                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        )
                )

        treeNodes[HOME_ID]!!.addChild(MOST_PLAYED_ID)
        treeNodes[HOME_ID]!!.addChild(LAST_PLAYED_ID)
        treeNodes[HOME_ID]!!.addChild(RECENTLY_ADDED_ID)
        treeNodes[HOME_ID]!!.addChild(RECENT_SONGS_ID)
        treeNodes[HOME_ID]!!.addChild(MADE_FOR_YOU_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_TRACKS_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_ALBUMS_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_ARTISTS_ID)
        treeNodes[HOME_ID]!!.addChild(RANDOM_ID)

        // Second level LIBRARY_ID

        treeNodes[FOLDER_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Folders",
                    mediaId = FOLDER_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[PLAYLIST_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Playlists",
                    mediaId = PLAYLIST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[LIBRARY_ID]!!.addChild(FOLDER_ID)
        treeNodes[LIBRARY_ID]!!.addChild(PLAYLIST_ID)

        // Second level OTHER_ID

        treeNodes[PODCAST_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Podcasts",
                    mediaId = PODCAST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS
                )
            )

        treeNodes[RADIO_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Radio stations",
                    mediaId = RADIO_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                )
            )

        treeNodes[OTHER_ID]!!.addChild(PODCAST_ID)
        treeNodes[OTHER_ID]!!.addChild(RADIO_ID)

        treeNodes[ROOT_ID]!!.addChild(CassetteCar.HOME)
        treeNodes[ROOT_ID]!!.addChild(CassetteCar.ALBUMS)
        treeNodes[ROOT_ID]!!.addChild(CassetteCar.ARTISTS)
        treeNodes[ROOT_ID]!!.addChild(CassetteCar.PLAYLISTS)
    }

    fun getRootItem(): MediaItem {
        return treeNodes[ROOT_ID]!!.item
    }

    fun getChildren(
        id: String
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (CassetteCar.owns(id)) {
            return CassetteCar.children(com.cappielloantonio.tempo.App.getContext(), id, automotiveRepository)
        }
        return when (id) {
            ROOT_ID -> treeNodes[ROOT_ID]?.getChildren()!!
            HOME_ID -> treeNodes[HOME_ID]?.getChildren()!!
            LIBRARY_ID -> treeNodes[LIBRARY_ID]?.getChildren()!!
            OTHER_ID -> treeNodes[OTHER_ID]?.getChildren()!!

            MOST_PLAYED_ID -> automotiveRepository.getAlbums(id, "frequent", 100)
            LAST_PLAYED_ID -> automotiveRepository.getAlbums(id, "recent", 100)
            RECENTLY_ADDED_ID -> automotiveRepository.getAlbums(id, "newest", 100)
            RECENT_SONGS_ID -> automotiveRepository.getRecentlyPlayedSongs(getServerId(),100)
            MADE_FOR_YOU_ID -> automotiveRepository.getStarredArtists(id)
            STARRED_TRACKS_ID -> automotiveRepository.starredSongs
            STARRED_ALBUMS_ID -> automotiveRepository.getStarredAlbums(id)
            STARRED_ARTISTS_ID -> automotiveRepository.getStarredArtists(id)
            RANDOM_ID -> automotiveRepository.getRandomSongs(100)
            FOLDER_ID -> automotiveRepository.getMusicFolders(id)
            PLAYLIST_ID -> automotiveRepository.getPlaylists(id)
            PODCAST_ID -> automotiveRepository.getNewestPodcastEpisodes(100)
            RADIO_ID -> automotiveRepository.internetRadioStations

            else -> {
                if (id.startsWith(MOST_PLAYED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            MOST_PLAYED_ID
                        )
                    )
                }

                if (id.startsWith(LAST_PLAYED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            LAST_PLAYED_ID
                        )
                    )
                }

                if (id.startsWith(RECENTLY_ADDED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            RECENTLY_ADDED_ID
                        )
                    )
                }

                if (id.startsWith(MADE_FOR_YOU_ID)) {
                    return automotiveRepository.getMadeForYou(
                        id.removePrefix(
                            MADE_FOR_YOU_ID
                        ),
                        20
                    )
                }

                if (id.startsWith(STARRED_ALBUMS_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            STARRED_ALBUMS_ID
                        )
                    )
                }

                if (id.startsWith(STARRED_ARTISTS_ID)) {
                    return automotiveRepository.getArtistAlbum(
                        STARRED_ALBUMS_ID,
                        id.removePrefix(
                            STARRED_ARTISTS_ID
                        )
                    )
                }

                if (id.startsWith(FOLDER_ID)) {
                    return automotiveRepository.getIndexes(
                        INDEX_ID,
                        id.removePrefix(
                            FOLDER_ID
                        )
                    )
                }

                if (id.startsWith(INDEX_ID)) {
                    return automotiveRepository.getDirectories(
                        DIRECTORY_ID,
                        id.removePrefix(
                            INDEX_ID
                        )
                    )
                }

                if (id.startsWith(DIRECTORY_ID)) {
                    return automotiveRepository.getDirectories(
                        DIRECTORY_ID,
                        id.removePrefix(
                            DIRECTORY_ID
                        )
                    )
                }

                if (id.startsWith(PLAYLIST_ID)) {
                    // cassette: only the playlist's songs on this phone, the ones the car can play
                    return com.google.common.util.concurrent.Futures.transform(
                        automotiveRepository.getPlaylistSongs(id.removePrefix(PLAYLIST_ID)),
                        { result -> phoneOnly(result) },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }

                if (id.startsWith(ALBUM_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            ALBUM_ID
                        )
                    )
                }

                if (id.startsWith(ARTIST_ID)) {
                    return automotiveRepository.getArtistAlbum(
                        ALBUM_ID,
                        id.removePrefix(
                            ARTIST_ID
                        )
                    )
                }

                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }
    }

    // https://github.com/androidx/media/issues/156
    // cassette: called off the main thread (CassetteCar.later): it reads the phone's database
    fun getItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val updatedMediaItems = ArrayList<MediaItem>()

        mediaItems.forEach {
            // cassette: the car's own Shuffle rows and stations
            val own = CassetteCar.resolve(com.cappielloantonio.tempo.App.getContext(), it.mediaId)
            if (own != null) {
                updatedMediaItems.addAll(own)
                return@forEach
            }
            if (it.localConfiguration?.uri != null) {
                updatedMediaItems.add(it)
            } else {
                val sessionMediaItem = automotiveRepository.getSessionMediaItem(it.mediaId)

                if (sessionMediaItem != null) {
                    var toAdd = automotiveRepository.getMetadatas(sessionMediaItem.timestamp!!)
                    val index = toAdd.indexOfFirst { mediaItem -> mediaItem.mediaId == it.mediaId }

                    toAdd = toAdd.subList(index, toAdd.size)

                    // cassette (BUG-941): the car plays the phone's own copies and
                    // only songs on the phone, from the one tapped on, like CarPlay
                    // (fbd10d85). The stored list carried the computer's streaming
                    // address for every song, which streamed at home and played
                    // nothing away from it.
                    val tracker = com.cappielloantonio.tempo.util.DownloadUtil.getDownloadTracker(com.cappielloantonio.tempo.App.getContext())
                    toAdd = toAdd.mapNotNull { item ->
                        val type = item.mediaMetadata.extras?.getString("type") ?: com.cappielloantonio.tempo.util.Constants.MEDIA_TYPE_MUSIC
                        if (type != com.cappielloantonio.tempo.util.Constants.MEDIA_TYPE_MUSIC) return@mapNotNull item
                        if (tracker.isCompleted(item.mediaId)) {
                            item.buildUpon().setUri(com.cappielloantonio.tempo.util.MappingUtil.getDownloadUri(item.mediaId)).build()
                        } else if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
                            null
                        } else {
                            item
                        }
                    }

                    updatedMediaItems.addAll(toAdd)
                }
            }
        }

        return updatedMediaItems
    }

    private fun phoneOnly(result: LibraryResult<ImmutableList<MediaItem>>): LibraryResult<ImmutableList<MediaItem>> {
        val items = result.value ?: return result
        if (!com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) return result
        val tracker = com.cappielloantonio.tempo.util.DownloadUtil.getDownloadTracker(com.cappielloantonio.tempo.App.getContext())
        return LibraryResult.ofItemList(ImmutableList.copyOf(items.filter { tracker.isCompleted(it.mediaId) }), result.params)
    }

    fun search(query: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        // cassette: the car searches what is on this phone, like the phone's own Search (BUG-940)
        return CassetteCar.search(query, automotiveRepository)
    }
}
