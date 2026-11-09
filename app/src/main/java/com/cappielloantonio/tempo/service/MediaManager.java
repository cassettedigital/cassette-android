package com.cappielloantonio.tempo.service;

import android.content.ComponentName;

import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.MediaIndexCallback;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.repository.ChronologyRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.InternetRadioStation;
import com.cappielloantonio.tempo.subsonic.models.PodcastEpisode;
import com.cappielloantonio.tempo.util.CassetteOnPhone;
import com.cappielloantonio.tempo.util.CassettePlaySync;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class MediaManager {
    private static final String TAG = "MediaManager";

    public static void reset(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().isPlaying()) {
                            mediaBrowserListenableFuture.get().pause();
                        }

                        mediaBrowserListenableFuture.get().stop();
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        clearDatabase();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void hide(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().isPlaying()) {
                            mediaBrowserListenableFuture.get().pause();
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void check(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().getMediaItemCount() < 1) {
                            List<Child> media = getQueueRepository().getMedia();
                            if (media != null && media.size() >= 1) {
                                init(mediaBrowserListenableFuture, media);
                            }
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void init(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        // cassette (BUG-939): restore only songs still on the phone, and
                        // land on the saved song's place in that list, not the old one.
                        List<Child> kept = CassetteOnPhone.keep(media);
                        if (kept.isEmpty()) {
                            clearDatabase();
                            return;
                        }
                        int start = CassetteOnPhone.startIn(media, getQueueRepository().getLastPlayedMediaIndex());
                        long at = start >= 0 ? getQueueRepository().getLastPlayedMediaTimestamp() : 0;
                        if (start < 0) start = 0;
                        if (kept.size() != media.size()) enqueueDatabase(kept, true, 0);
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItems(MappingUtil.mapMediaItems(kept));
                        mediaBrowserListenableFuture.get().seekTo(start, at);
                        mediaBrowserListenableFuture.get().prepare();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startQueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int startIndex) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        // cassette (BUG-939): queue only songs on the phone, and start on
                        // the tapped song's place in that list (or the next that can play).
                        List<Child> kept = CassetteOnPhone.keep(media);
                        int start = CassetteOnPhone.startIn(media, startIndex);
                        if (kept.isEmpty() || start < 0) return;
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItems(MappingUtil.mapMediaItems(kept));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().seekTo(start, 0);
                        mediaBrowserListenableFuture.get().play();
                        enqueueDatabase(kept, true, 0);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    /** cassette: set the player repeat mode (album-page repeat button). */
    /**
     * cassette: jump to a saved position. The queue table has carried a
     * paused-at timestamp since the fork (Queue.playingChanged, written by the
     * flavour's MediaService), but nothing ever read it, so every resume
     * started the track from zero.
     */
    public static void seekTo(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, long positionMs) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().seekTo(positionMs);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    /**
     * cassette: pause if sounding, resume if not. Lets a play control that is
     * pointed at the record already playing behave like a pause instead of
     * restarting the queue from the top.
     */
    public static void togglePlayPause(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        MediaBrowser browser = mediaBrowserListenableFuture.get();
                        if (browser.isPlaying()) browser.pause(); else browser.play();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void setRepeatMode(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, int repeatMode) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().setRepeatMode(repeatMode);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startQueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, Child media) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (!CassetteOnPhone.playable(media)) return; // cassette (BUG-942)
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapMediaItem(media));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                        enqueueDatabase(media, true, 0);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    /**
     * cassette (BUG-943): Resume. When the saved queue is loaded and holds this
     * song, carry on from it there, at the saved position. Otherwise play the
     * song's record from that song on (only songs on the phone), the way the
     * iPhone's Resume picks the record up where it was.
     */
    public static void resume(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, Child song, long positionMs) {
        if (mediaBrowserListenableFuture == null || song == null) return;
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                if (!mediaBrowserListenableFuture.isDone()) return;
                MediaBrowser browser = mediaBrowserListenableFuture.get();
                for (int i = 0; i < browser.getMediaItemCount(); i++) {
                    if (song.getId().equals(browser.getMediaItemAt(i).mediaId)) {
                        browser.seekTo(i, Math.max(0, positionMs));
                        browser.prepare();
                        browser.play();
                        return;
                    }
                }
                new Thread(() -> {
                    List<Child> record = new java.util.ArrayList<>();
                    if (song.getAlbumId() != null) {
                        record.addAll(com.cappielloantonio.tempo.database.AppDatabase.getInstance()
                                .downloadDao().getAlbumSync(song.getAlbumId()));
                    }
                    int at = -1;
                    for (int i = 0; i < record.size(); i++) {
                        if (song.getId().equals(record.get(i).getId())) { at = i; break; }
                    }
                    if (at < 0) { record.clear(); record.add(song); at = 0; }
                    final List<Child> queue = record;
                    final int start = at;
                    androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper()).post(() -> {
                        startQueue(mediaBrowserListenableFuture, queue, start);
                        if (positionMs > 0) seekTo(mediaBrowserListenableFuture, positionMs);
                    });
                }).start();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * cassette: a play disc on a record tile. Plays that record's songs on this
     * phone from the top (or shuffled), and the screen stays where it is.
     */
    public static void playAlbum(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, String albumId, boolean shuffle) {
        if (mediaBrowserListenableFuture == null || albumId == null) return;
        new Thread(() -> {
            List<Child> record = new java.util.ArrayList<>(com.cappielloantonio.tempo.database.AppDatabase
                    .getInstance().downloadDao().getAlbumSync(albumId));
            if (record.isEmpty()) return;
            if (shuffle) java.util.Collections.shuffle(record);
            androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper())
                    .post(() -> startQueue(mediaBrowserListenableFuture, record, 0));
        }, "cassette-play-album").start();
    }

    /** cassette: an artist tile's disc shuffles their songs on this phone. */
    public static void playArtist(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, String artistId) {
        if (mediaBrowserListenableFuture == null || artistId == null) return;
        new Thread(() -> {
            List<Child> songs = new java.util.ArrayList<>(com.cappielloantonio.tempo.database.AppDatabase
                    .getInstance().downloadDao().getArtistSync(artistId));
            if (songs.isEmpty()) return;
            java.util.Collections.shuffle(songs);
            androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper())
                    .post(() -> startQueue(mediaBrowserListenableFuture, songs, 0));
        }, "cassette-play-artist").start();
    }

    public static void startRadio(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, InternetRadioStation internetRadioStation) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapInternetRadioStation(internetRadioStation));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startPodcast(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, PodcastEpisode podcastEpisode) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapMediaItem(podcastEpisode));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> songs, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    // cassette (BUG-942): only songs on the phone join the queue.
                    List<Child> media = CassetteOnPhone.keep(songs);
                    if (media.isEmpty()) return;
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (playImmediatelyAfter && mediaBrowserListenableFuture.get().getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getNextMediaItemIndex());
                            mediaBrowserListenableFuture.get().addMediaItems(mediaBrowserListenableFuture.get().getNextMediaItemIndex(), MappingUtil.mapMediaItems(media));
                        } else {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getMediaItemCount());
                            mediaBrowserListenableFuture.get().addMediaItems(MappingUtil.mapMediaItems(media));
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, Child media, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (!CassetteOnPhone.playable(media)) return; // cassette (BUG-942)
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (playImmediatelyAfter && mediaBrowserListenableFuture.get().getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getNextMediaItemIndex());
                            mediaBrowserListenableFuture.get().addMediaItem(mediaBrowserListenableFuture.get().getNextMediaItemIndex(), MappingUtil.mapMediaItem(media));
                        } else {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getMediaItemCount());
                            mediaBrowserListenableFuture.get().addMediaItem(MappingUtil.mapMediaItem(media));
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void shuffle(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int startIndex, int endIndex) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().removeMediaItems(startIndex, endIndex + 1);
                        mediaBrowserListenableFuture.get().addMediaItems(MappingUtil.mapMediaItems(media).subList(startIndex, endIndex + 1));
                        swapDatabase(media);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void swap(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int from, int to) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().moveMediaItem(from, to);
                        swapDatabase(media);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void remove(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int toRemove) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().getMediaItemCount() > 1 && mediaBrowserListenableFuture.get().getCurrentMediaItemIndex() != toRemove) {
                            mediaBrowserListenableFuture.get().removeMediaItem(toRemove);
                            removeDatabase(media, toRemove);
                        } else {
                            removeDatabase(media, -1);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void removeRange(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int fromItem, int toItem) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().removeMediaItems(fromItem, toItem);
                        removeRangeDatabase(media, fromItem, toItem);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void getCurrentIndex(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, MediaIndexCallback callback) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        callback.onRecovery(mediaBrowserListenableFuture.get().getCurrentMediaItemIndex());
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void setLastPlayedTimestamp(MediaItem mediaItem) {
        if (mediaItem != null) getQueueRepository().setLastPlayedTimestamp(mediaItem.mediaId);
    }

    public static void setPlayingPausedTimestamp(MediaItem mediaItem, long ms) {
        if (mediaItem != null)
            getQueueRepository().setPlayingPausedTimestamp(mediaItem.mediaId, ms);
    }

    public static void scrobble(MediaItem mediaItem, boolean submission) {
        if (mediaItem != null && Preferences.isScrobblingEnabled()) {
            getSongRepository().scrobble(mediaItem.mediaMetadata.extras.getString("id"), submission);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public static void continuousPlay(MediaItem mediaItem) {
        if (mediaItem != null && Preferences.isContinuousPlayEnabled() && Preferences.isInstantMixUsable()) {
            Preferences.setLastInstantMix();

            LiveData<List<Child>> instantMix = getSongRepository().getInstantMix(mediaItem.mediaId, 10);
            instantMix.observeForever(new Observer<List<Child>>() {
                @Override
                public void onChanged(List<Child> media) {
                    if (media != null) {
                        ListenableFuture<MediaBrowser> mediaBrowserListenableFuture = new MediaBrowser.Builder(
                                App.getContext(),
                                new SessionToken(App.getContext(), new ComponentName(App.getContext(), MediaService.class))
                        ).buildAsync();

                        enqueue(mediaBrowserListenableFuture, media, true);
                    }

                    instantMix.removeObserver(this);
                }
            });
        }
    }

    public static void saveChronology(MediaItem mediaItem) {
        if (mediaItem != null) {
            getChronologyRepository().insert(new Chronology(mediaItem));
            // cassette play spine: a completed play was just logged — flush it
            // (and any backlog) to /api/sync/plays on a background thread.
            CassettePlaySync.flushAsync();
        }
    }

    private static QueueRepository getQueueRepository() {
        return new QueueRepository();
    }

    private static SongRepository getSongRepository() {
        return new SongRepository();
    }

    private static ChronologyRepository getChronologyRepository() {
        return new ChronologyRepository();
    }

    private static void enqueueDatabase(List<Child> media, boolean reset, int afterIndex) {
        getQueueRepository().insertAll(media, reset, afterIndex);
    }

    private static void enqueueDatabase(Child media, boolean reset, int afterIndex) {
        getQueueRepository().insert(media, reset, afterIndex);
    }

    private static void swapDatabase(List<Child> media) {
        getQueueRepository().insertAll(media, true, 0);
    }

    private static void removeDatabase(List<Child> media, int toRemove) {
        if (toRemove != -1) {
            media.remove(toRemove);
            getQueueRepository().insertAll(media, true, 0);
        }
    }

    private static void removeRangeDatabase(List<Child> media, int fromItem, int toItem) {
        List<Child> toRemove = media.subList(fromItem, toItem);

        media.removeAll(toRemove);

        getQueueRepository().insertAll(media, true, 0);
    }

    public static void clearDatabase() {
        getQueueRepository().deleteAll();
    }
}
