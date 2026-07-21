package com.cappielloantonio.tempo.service;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.util.DownloadUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

@UnstableApi
public class DownloaderManager {
    private static final String TAG = "DownloaderManager";

    private final Context context;
    private final DataSource.Factory dataSourceFactory;
    private final DownloadIndex downloadIndex;

    // cassette: concurrent — the download service writes state changes on its
    // own thread while the UI/notification read the tally. A plain HashMap
    // threw ConcurrentModificationException the moment anything iterated it.
    private static java.util.concurrent.ConcurrentHashMap<String, Download> downloads;

    // cassette: ids handed to sendAddDownload whose state change hasn't come
    // back yet. The `downloads` map alone only learns about a download when
    // the service reports it, so without this set a caller enqueuing in bulk
    // (the sync engine) would see queued tracks as missing and re-send them
    // every pass — hundreds of redundant startService dispatches that starve
    // the main thread once the queue is parked (the Wi-Fi-off ANR).
    private static final java.util.Set<String> pendingIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public DownloaderManager(Context context, DataSource.Factory dataSourceFactory, DownloadManager downloadManager) {
        this.context = context.getApplicationContext();
        this.dataSourceFactory = dataSourceFactory;

        downloads = new java.util.concurrent.ConcurrentHashMap<>();
        downloadIndex = downloadManager.getDownloadIndex();

        loadDownloads();
    }

    private DownloadRequest buildDownloadRequest(MediaItem mediaItem) {
        return DownloadHelper
                .forMediaItem(
                        context,
                        mediaItem,
                        DownloadUtil.buildRenderersFactory(context, false),
                        dataSourceFactory)
                .getDownloadRequest(Util.getUtf8Bytes(checkNotNull(mediaItem.mediaId)))
                .copyWithId(mediaItem.mediaId);
    }

    public boolean isDownloaded(String mediaId) {
        @Nullable Download download = downloads.get(mediaId);
        if (download != null && download.state != Download.STATE_FAILED) return true;
        return pendingIds.contains(mediaId);
    }

    /** cassette: fully on disk (vs merely queued) — the sync engine's completion signal. */
    public boolean isCompleted(String mediaId) {
        @Nullable Download download = downloads.get(mediaId);
        return download != null && download.state == Download.STATE_COMPLETED;
    }

    public boolean isDownloaded(MediaItem mediaItem) {
        return isDownloaded(mediaItem.mediaId);
    }

    public boolean areDownloaded(List<MediaItem> mediaItems) {
        return mediaItems.stream().anyMatch(this::isDownloaded);
    }

    public void download(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download) {
        download.setDownloadUri(mediaItem.requestMetadata.mediaUri.toString());

        pendingIds.add(mediaItem.mediaId);
        DownloadService.sendAddDownload(context, DownloaderService.class, buildDownloadRequest(mediaItem), false);
        insertDatabase(download);
    }

    public void download(List<MediaItem> mediaItems, List<com.cappielloantonio.tempo.model.Download> downloads) {
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            download(mediaItems.get(counter), downloads.get(counter));
        }
    }

    public void remove(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download) {
        DownloadService.sendRemoveDownload(context, DownloaderService.class, buildDownloadRequest(mediaItem).id, false);
        deleteDatabase(download.getId());
        downloads.remove(download.getId());
        pendingIds.remove(download.getId());
    }

    public void remove(List<MediaItem> mediaItems, List<com.cappielloantonio.tempo.model.Download> downloads) {
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            remove(mediaItems.get(counter), downloads.get(counter));
        }
    }

    /**
     * cassette: remove by bare media id. Download request ids ARE media ids
     * (buildDownloadRequest copies the id), so the sync engine can drop a
     * track without reconstructing a MediaItem whose URI no longer matters.
     */
    public void removeById(String mediaId) {
        DownloadService.sendRemoveDownload(context, DownloaderService.class, mediaId, false);
        deleteDatabase(mediaId);
        downloads.remove(mediaId);
        pendingIds.remove(mediaId);
    }

    /**
     * cassette: keep the in-memory view current for EVERY state change (the
     * completed-only update left queued/downloading entries invisible).
     * Map-only — the Room completed flag still flips solely on COMPLETED.
     */
    public static void noteState(Download download) {
        downloads.put(download.request.id, download);
        pendingIds.remove(download.request.id);
    }

    /**
     * cassette: track tally for the sync banner + notification — [done, total].
     * Total counts everything this process knows about (indexed downloads plus
     * ids handed to the service that haven't reported back yet), so a bulk
     * enqueue shows its real size immediately instead of climbing from zero.
     */
    public static int[] trackTally() {
        if (downloads == null) return new int[]{0, 0};
        int done = 0;
        for (Download download : downloads.values()) {
            if (download.state == Download.STATE_COMPLETED) done++;
        }
        return new int[]{done, downloads.size() + pendingIds.size()};
    }

    public void removeAll() {
        DownloadService.sendRemoveAllDownloads(context, DownloaderService.class, false);
        deleteAllDatabase();
        DownloadUtil.eraseDownloadFolder(context);
        pendingIds.clear();
    }

    private void loadDownloads() {
        try (DownloadCursor loadedDownloads = downloadIndex.getDownloads()) {
            while (loadedDownloads.moveToNext()) {
                Download download = loadedDownloads.getDownload();
                downloads.put(download.request.id, download);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to query downloads", e);
        }
    }

    public static String getDownloadNotificationMessage(String id) {
        com.cappielloantonio.tempo.model.Download download = getDownloadRepository().getDownload(id);
        return download != null ? download.getTitle() : null;
    }

    public static void updateRequestDownload(Download download) {
        updateDatabase(download.request.id);
        downloads.put(download.request.id, download);
    }

    public static void removeRequestDownload(Download download) {
        deleteDatabase(download.request.id);
        downloads.remove(download.request.id);
        pendingIds.remove(download.request.id);
    }

    private static DownloadRepository getDownloadRepository() {
        return new DownloadRepository();
    }

    private static void insertDatabase(com.cappielloantonio.tempo.model.Download download) {
        getDownloadRepository().insert(download);
    }

    private static void deleteDatabase(String id) {
        getDownloadRepository().delete(id);
    }

    private static void deleteAllDatabase() {
        getDownloadRepository().deleteAll();
    }

    private static void updateDatabase(String id) {
        getDownloadRepository().update(id);
    }
}