package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.CassetteBitmapLoader
import com.cappielloantonio.tempo.util.CassetteDiagnostics
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class MediaService : MediaLibraryService(), SessionAvailabilityListener {
    private lateinit var automotiveRepository: AutomotiveRepository
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()

        // cassette: the now-playing notification showed Media3's default small
        // icon (read as a generic/Tempo mark). Use our cassette outline instead.
        setMediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(this).build().apply {
                    setSmallIcon(R.drawable.ic_notification_cassette)
                }
        )

        initializeRepository()
        initializePlayer()
        initializeCastPlayer()
        initializeMediaLibrarySession()
        initializePlayerListener()

        setPlayer(
                null,
                if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable) castPlayer else player
        )
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        com.cappielloantonio.tempo.util.CassetteKeepPlaying.removeListener(keepPlayingChanged)
        releasePlayer()
        super.onDestroy()
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
                .setRenderersFactory(getRenderersFactory())
                .setMediaSourceFactory(getMediaSourceFactory())
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(initializeLoadControl())
                .build()
    }

    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            castPlayer = CastPlayer(CastContext.getSharedInstance(this))
            castPlayer.setSessionAvailabilityListener(this)
        }
    }

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent =
                TaskStackBuilder.create(this).run {
                    addNextIntent(Intent(this@MediaService, MainActivity::class.java))
                    getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
                }

        mediaLibrarySession =
                MediaLibrarySession.Builder(this, player, createLibrarySessionCallback())
                        .setSessionActivity(sessionActivityPendingIntent)
                        // cassette: local-first now-playing artwork (notification /
                        // lock screen / Android Auto). Resolves the on-disk cover
                        // before the remote getCoverArt URL, so art shows instantly
                        // and offline, matching iOS. Wrapped in CacheBitmapLoader so
                        // same-album tracks reuse the decoded bitmap (no blink).
                        .setBitmapLoader(CacheBitmapLoader(CassetteBitmapLoader(this)))
                        // cassette: the car's Open album / Open artist menu (CassetteCar)
                        .setCommandButtonsForMediaItems(sessionCallback.browseActions)
                        .build()
    }

    private lateinit var sessionCallback: MediaLibrarySessionCallback

    private fun createLibrarySessionCallback(): MediaLibrarySession.Callback {
        sessionCallback = MediaLibrarySessionCallback(this, automotiveRepository)
        return sessionCallback
    }

    // cassette: the car's ∞ key follows keep playing however it is switched (the queue head too)
    private val keepPlayingChanged = Runnable {
        if (this::sessionCallback.isInitialized && this::mediaLibrarySession.isInitialized) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { sessionCallback.refreshLayout(mediaLibrarySession) }
        }
    }

    private fun initializePlayerListener() {
        com.cappielloantonio.tempo.util.CassetteKeepPlaying.addListener(keepPlayingChanged)
        // cassette: a station's song, show and picture, kept current on the playing item
        val clock = android.text.format.DateFormat.getTimeFormat(this)
        player.addListener(com.cappielloantonio.tempo.util.CassetteRadioLive.Follower(player) { t -> clock.format(java.util.Date(t)) })

        player.addListener(object : Player.Listener {
            // cassette: the car's Shuffle and Repeat keys show what the phone's player set too
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (this@MediaService::sessionCallback.isInitialized && this@MediaService::mediaLibrarySession.isInitialized) {
                    sessionCallback.refreshLayout(mediaLibrarySession)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (this@MediaService::sessionCallback.isInitialized && this@MediaService::mediaLibrarySession.isInitialized) {
                    sessionCallback.refreshLayout(mediaLibrarySession)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // a station's notification has no shuffle key; music's does (BUG-955)
                if (this@MediaService::sessionCallback.isInitialized && this@MediaService::mediaLibrarySession.isInitialized) {
                    sessionCallback.refreshLayout(mediaLibrarySession)
                }
                if (mediaItem == null) return

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)
                MediaManager.scrobble(player.currentMediaItem, false)

                if (player.currentMediaItemIndex + 1 == player.mediaItemCount)
                    MediaManager.continuousPlay(player.currentMediaItem, player.repeatMode == Player.REPEAT_MODE_OFF)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                            player.currentMediaItem,
                            player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (!player.hasNextMediaItem() &&
                        playbackState == Player.STATE_ENDED &&
                        player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
                }

                // cassette: the queue ran out: Keep playing's held pick carries on (repeat wins)
                if (playbackState == Player.STATE_ENDED && !player.hasNextMediaItem() &&
                        player.repeatMode == Player.REPEAT_MODE_OFF &&
                        player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    val songs = com.cappielloantonio.tempo.util.CassetteKeepPlaying.takePick()
                    val kept = songs?.let { com.cappielloantonio.tempo.util.CassetteOnPhone.keep(it) }
                    if (!kept.isNullOrEmpty()) {
                        // The record that carries on becomes the queue, as a tap on THEN does
                        // (MediaManager.startQueue): the player and the saved queue hold the same
                        // songs, so UP NEXT shows them and Keep playing's next question is about
                        // this record. Appending to the player alone left the saved queue on the
                        // old record: UP NEXT went blank and THEN offered the record now playing.
                        player.setMediaItems(com.cappielloantonio.tempo.util.MappingUtil.mapMediaItems(kept))
                        player.seekTo(0, 0)
                        player.prepare()
                        player.play()
                        Thread({ com.cappielloantonio.tempo.repository.QueueRepository().insertAll(kept, true, 0) },
                                "cassette-keep-playing-queue").start()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // cassette: do NOT auto-POST — a hub going briefly unreachable on
                // the LAN throws a torrent of transient errors. Record a breadcrumb
                // that enriches a later crash report instead (the diagnostics spine
                // has no 'player' component, so a standalone report would be wrong).
                CassetteDiagnostics.breadcrumb(
                        "player_error " + error.errorCodeName + "(" + error.errorCode + "): " + error.message
                )
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                // Recovery signal only: the error state cleared (e.g. a retry
                // succeeded). The set transition is already logged by onPlayerError.
                if (error == null) CassetteDiagnostics.breadcrumb("player_error_cleared")
            }

            override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                }
            }
        })
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        (DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        oldPlayer?.stop()
        mediaLibrarySession.player = newPlayer
    }

    private fun releasePlayer() {
        if (this::castPlayer.isInitialized) castPlayer.setSessionAvailabilityListener(null)
        if (this::castPlayer.isInitialized) castPlayer.release()
        player.release()
        mediaLibrarySession.release()
        automotiveRepository.deleteMetadata()
        clearListener()
    }

    private fun getRenderersFactory() = DownloadUtil.buildRenderersFactory(this, false)

    private fun getMediaSourceFactory() =
            DefaultMediaSourceFactory(this).setDataSourceFactory(DownloadUtil.getDataSourceFactory(this))

    override fun onCastSessionAvailable() {
        setPlayer(player, castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setPlayer(castPlayer, player)
    }
}