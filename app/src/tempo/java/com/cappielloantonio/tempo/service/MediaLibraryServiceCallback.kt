package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.util.CassetteCar
import com.cappielloantonio.tempo.util.CassetteKeepPlaying
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

open class MediaLibrarySessionCallback(
    context: Context,
    automotiveRepository: AutomotiveRepository
) :
    MediaLibraryService.MediaLibrarySession.Callback {

    init {
        MediaBrowserTree.initialize(automotiveRepository)
    }

    private val customLayoutCommandButtons: List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName(context.getString(R.string.exo_controls_shuffle_on_description))
            .setSessionCommand(
                SessionCommand(
                    CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY
                )
            ).setIconResId(R.drawable.exo_icon_shuffle_off).build(),

        CommandButton.Builder()
            .setDisplayName(context.getString(R.string.exo_controls_shuffle_off_description))
            .setSessionCommand(
                SessionCommand(
                    CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY
                )
            ).setIconResId(R.drawable.exo_icon_shuffle_on).build()
    )

    /**
     * cassette: Repeat beside Shuffle, as CarPlay's Now Playing has them (Shuffle, then Repeat).
     * One key showing where repeat is now (off, all, one); a press moves it on, in the iPhone's
     * order: off, all, one, off.
     */
    private val repeatButtons: List<CommandButton> = listOf(
        Player.REPEAT_MODE_OFF to R.drawable.exo_icon_repeat_off,
        Player.REPEAT_MODE_ALL to R.drawable.exo_icon_repeat_all,
        Player.REPEAT_MODE_ONE to R.drawable.exo_icon_repeat_one,
    ).map { (_, icon) ->
        CommandButton.Builder()
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REPEAT_NEXT, Bundle.EMPTY))
            .setIconResId(icon).build()
    }

    /**
     * cassette: Keep playing (∞) beside Shuffle and Repeat in the car, as the queue head has it.
     * Lit while it acts (on, repeat off); faint when off or waiting on repeat.
     */
    private val keepPlayingButtons: List<CommandButton> = listOf(
        R.drawable.ic_car_keep_playing_off, R.drawable.ic_car_keep_playing_on,
    ).map { icon ->
        CommandButton.Builder()
            .setDisplayName("Keep playing")
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_KEEP_PLAYING, Bundle.EMPTY))
            .setIconResId(icon).build()
    }

    /**
     * cassette: the car's menu on a cover that plays when tapped (Android Auto's custom browse
     * actions): Open album / Open artist go to the page, so a tap can play and the page stays a
     * step away. Shown where the car offers them.
     */
    val browseActions: List<CommandButton> = listOf(
        Triple(CassetteCar.OPEN_ALBUM, "Open album", R.drawable.ic_lucide_disc),
        Triple(CassetteCar.OPEN_ARTIST, "Open artist", R.drawable.ic_lucide_user),
    ).map { (id, label, icon) ->
        CommandButton.Builder()
            .setDisplayName(label)
            .setSessionCommand(SessionCommand(id, Bundle.EMPTY))
            .setIconResId(icon)
            .setIconUri(
                android.net.Uri.Builder().scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(context.resources.getResourcePackageName(icon))
                    .appendPath(context.resources.getResourceTypeName(icon))
                    .appendPath(context.resources.getResourceEntryName(icon)).build()
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .also { builder ->
                customLayoutCommandButtons.forEach { commandButton ->
                    commandButton.sessionCommand?.let { builder.add(it) }
                }
                builder.add(SessionCommand(CUSTOM_COMMAND_REPEAT_NEXT, Bundle.EMPTY))
                builder.add(SessionCommand(CUSTOM_COMMAND_KEEP_PLAYING, Bundle.EMPTY))
                browseActions.forEach { button -> button.sessionCommand?.let { builder.add(it) } }
            }.build()

    /**
     * cassette (BUG-955): the notification, lock screen and car get the shuffle key only for
     * music; a station is one live thing (Play/Pause), as the phone's own players already show.
     * With a car connected Repeat and Keep playing join Shuffle (CarPlay's Now Playing); otherwise
     * Shuffle alone.
     */
    @OptIn(UnstableApi::class)
    private fun layoutFor(session: MediaSession, car: Boolean): ImmutableList<CommandButton> {
        val type = session.player.currentMediaItem?.mediaMetadata?.extras?.getString("type")
        if (type == com.cappielloantonio.tempo.util.Constants.MEDIA_TYPE_RADIO) return ImmutableList.of()
        val shuffle = customLayoutCommandButtons[if (session.player.shuffleModeEnabled) 1 else 0]
        if (!car) return ImmutableList.of(shuffle)
        val repeat = when (session.player.repeatMode) {
            Player.REPEAT_MODE_ALL -> repeatButtons[1]
            Player.REPEAT_MODE_ONE -> repeatButtons[2]
            else -> repeatButtons[0]
        }
        val keep = keepPlayingButtons[if (CassetteKeepPlaying.isActive(session.player.repeatMode)) 1 else 0]
        return ImmutableList.of(shuffle, repeat, keep)
    }

    @OptIn(UnstableApi::class)
    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        session.isAutomotiveController(controller) || session.isAutoCompanionController(controller)

    /**
     * Whether a car is connected. Android Auto on a phone draws the keys of the phone's one media
     * session, which are the notification's (Media3 hands only the notification controller's
     * layout to it), so Repeat joins the notification while a car is here and leaves with it.
     */
    @OptIn(UnstableApi::class)
    private fun carHere(session: MediaSession, gone: MediaSession.ControllerInfo? = null): Boolean =
        session.connectedControllers.any { it != gone && isCar(session, it) }

    /** The keys again for the playing item: called as the item, shuffle or repeat changes, and as a car comes or goes. */
    @OptIn(UnstableApi::class)
    @JvmOverloads
    fun refreshLayout(session: MediaSession, gone: MediaSession.ControllerInfo? = null) {
        val car = carHere(session, gone)
        for (controller in session.connectedControllers) {
            if (controller == gone) continue
            if (session.isMediaNotificationController(controller) || isCar(session, controller)) {
                session.setCustomLayout(controller, layoutFor(session, car))
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (isCar(session, controller)) refreshLayout(session)
    }

    @OptIn(UnstableApi::class)
    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (isCar(session, controller)) refreshLayout(session, gone = controller)
    }

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        if (session.isMediaNotificationController(controller) || session.isAutomotiveController(
                controller
            ) || session.isAutoCompanionController(controller)
        ) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setCustomLayout(layoutFor(session, isCar(session, controller) || carHere(session))).build()
        }

        // cassette: any other browser that draws the car's lists (a car's own media app, a test
        // host) may use the Open album / Open artist actions too: they only move between pages
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().also { builder ->
                    browseActions.forEach { button -> button.sessionCommand?.let { builder.add(it) } }
                }.build()
            ).build()
    }

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        // cassette: every surface's keys follow (the car's too, not only the notification's)
        if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON == customCommand.customAction) {
            session.player.shuffleModeEnabled = true
            refreshLayout(session)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF == customCommand.customAction) {
            session.player.shuffleModeEnabled = false
            refreshLayout(session)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else if (CassetteCar.OPEN_ALBUM == customCommand.customAction || CassetteCar.OPEN_ARTIST == customCommand.customAction) {
            // the car moves to the page (Android Auto reads the browse node from the result)
            val page = CassetteCar.pageFor(args.getString(androidx.media3.session.MediaConstants.EXTRA_KEY_MEDIA_ID))
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            val result = Bundle().apply { putString(RESULT_BROWSE_NODE, page) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
        } else if (CUSTOM_COMMAND_KEEP_PLAYING == customCommand.customAction) {
            // the queue head's rule: while repeat is on, ∞ turns repeat off and takes over
            CassetteKeepPlaying.press(session.player)
            refreshLayout(session)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else if (CUSTOM_COMMAND_REPEAT_NEXT == customCommand.customAction) {
            session.player.repeatMode = when (session.player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            refreshLayout(session)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // cassette: the car's lists are rows unless a list says otherwise (Home's covers)
        val root = MediaLibraryService.LibraryParams.Builder()
            .setExtras(com.cappielloantonio.tempo.util.CassetteCar.rootStyle())
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), root))
    }

    // cassette: a page by its id, which the car asks for to follow a linked line on Now Playing
    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = CassetteCar.itemFor(mediaId)

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.getChildren(parentId)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        // The phone's own screens hand over playable songs: as before, at once.
        if (mediaItems.all { it.localConfiguration?.uri != null }) {
            return super.onAddMediaItems(mediaSession, controller, MediaBrowserTree.getItems(mediaItems))
        }
        // cassette: the car (and voice) send only an id; finding what it means reads the phone's
        // database, which the main thread may not (BUG-981), so it is found off it.
        return com.cappielloantonio.tempo.util.CassetteCar.later { MediaBrowserTree.getItems(mediaItems) }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.search(query)
    }

    companion object {
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON =
            "android.media3.session.demo.SHUFFLE_ON"
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF =
            "android.media3.session.demo.SHUFFLE_OFF"
        private const val CUSTOM_COMMAND_REPEAT_NEXT = "digital.cassette.REPEAT_NEXT"
        private const val CUSTOM_COMMAND_KEEP_PLAYING = "digital.cassette.KEEP_PLAYING"
        private const val RESULT_BROWSE_NODE = "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_BROWSE_NODE"
    }
}