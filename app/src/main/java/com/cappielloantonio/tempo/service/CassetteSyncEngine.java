package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadManager;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.CassetteArtistArt;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

/**
 * The Cassette sync engine — Android's twin of the iOS IntentExecutor.
 *
 * The account's web dashboard is the control plane: it queues per-album
 * add/remove intents targeted at THIS device. Each pass here converges the
 * phone toward that desired state using Tempo's existing Media3 download
 * machinery as the ownership store:
 *
 *   poll intents → enqueue missing tracks / remove dropped ones →
 *   report state transitions → report the actual inventory back.
 *
 * Every step is idempotent and re-derived from local truth (the Media3
 * download index + Room rows), so a pass interrupted by a crash, an
 * unreachable hub, or the app being killed simply finishes on a later poll.
 * Nothing here must run to completion.
 */
@UnstableApi
public final class CassetteSyncEngine {
    private static final String TAG = "CassetteSyncEngine";

    /** Full inventory snapshots stamp server-side freshness; keep them cheap. */
    private static final long FULL_INVENTORY_MIN_INTERVAL_MS = 120_000;

    /**
     * Stop enqueuing new downloads below this much free space. A library
     * mirror can be bigger than the device; parking the remaining intents
     * (they stay pending/syncing — honest state, visible on the web) beats
     * writing the disk to zero. Media3's DEVICE_STORAGE_NOT_LOW requirement
     * is NOT a substitute — it hangs off a deprecated sticky broadcast that
     * modern Android fires far too late, proven empirically on the emulator.
     */
    private static final long FREE_SPACE_FLOOR_BYTES = 1024L * 1024 * 1024;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean LISTENER_ATTACHED = new AtomicBoolean(false);
    private static volatile boolean deviceRegisteredThisProcess = false;
    private static volatile long lastFullInventoryAt = 0;

    private CassetteSyncEngine() {
    }

    /** Run one convergence pass on a worker thread. No-op when unpaired or already running. */
    public static void kick(Context context) {
        Context appContext = context.getApplicationContext();
        if (Preferences.getCassetteBearerToken() == null) return;
        if (!RUNNING.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                attachQueueDrainListener(appContext);
                run(appContext);
            } catch (Exception e) {
                Log.w(TAG, "sync pass aborted", e);
            } finally {
                RUNNING.set(false);
            }
        }, "cassette-sync").start();
    }

    /**
     * When the download queue drains, converge immediately instead of waiting
     * for the next 30s poll — this is what flips a finished album's intent to
     * complete right as its last track lands.
     */
    private static void attachQueueDrainListener(Context appContext) {
        if (!LISTENER_ATTACHED.compareAndSet(false, true)) return;
        DownloadUtil.getDownloadManager(appContext).addListener(new DownloadManager.Listener() {
            @Override
            public void onIdle(DownloadManager downloadManager) {
                // Parked, not drained (off Wi-Fi / low storage): nothing
                // finished, so a converge pass would just spin. The 30s
                // poller keeps ownership of retries.
                if (downloadManager.getNotMetRequirements() != 0) return;
                kick(appContext);
            }
        });
    }

    private static void run(Context context) {
        String token = Preferences.getCassetteBearerToken();
        if (token == null) return;

        // Account first: the download tier must be fresh before any enqueue
        // builds URLs. Offline/cloud-down keeps the cached values.
        try {
            CassetteSyncApi.Account account = CassetteSyncApi.getAccount(token);
            if (account.downloadQuality != null) Preferences.setCassetteDownloadQuality(account.downloadQuality);
            if (account.themeId != null) Preferences.setCassetteThemeId(account.themeId);
        } catch (Exception e) {
            Log.d(TAG, "account refresh skipped: " + e.getMessage());
        }

        try {
            if (!deviceRegisteredThisProcess) {
                CassetteSyncApi.registerDevice(token);
                deviceRegisteredThisProcess = true;
            }
        } catch (Exception e) {
            Log.d(TAG, "device registration deferred: " + e.getMessage());
        }

        List<CassetteSyncApi.Intent> intents;
        try {
            intents = CassetteSyncApi.getActionableIntents(token);
        } catch (Exception e) {
            Log.d(TAG, "intent poll skipped: " + e.getMessage());
            return;
        }

        // The floor must also govern the ACTIVE queue: already-enqueued
        // downloads would otherwise keep writing straight past it (the
        // enqueue-time check alone can't help a queue that outsizes the
        // disk). Paused work resumes automatically once space frees up.
        try {
            if (hasFreeSpaceForDownloads(context)) {
                androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(
                        context, DownloaderService.class, false);
            } else {
                Log.w(TAG, "below free-space floor: pausing download queue");
                androidx.media3.exoplayer.offline.DownloadService.sendPauseDownloads(
                        context, DownloaderService.class, false);
            }
        } catch (Exception e) {
            Log.d(TAG, "queue pause/resume skipped: " + e.getMessage());
        }

        boolean hubReachable = true;
        for (CassetteSyncApi.Intent intent : intents) {
            try {
                boolean isRemove = "remove".equals(intent.intentKind);
                if (!isRemove && !hubReachable) continue; // adds need the hub; stop hammering it

                CassetteSyncApi.IntentTracks resolved = CassetteSyncApi.getIntentTracks(token, intent.id);
                if (isRemove) {
                    executeRemove(context, token, intent, resolved);
                } else {
                    hubReachable = executeAdd(context, token, intent, resolved);
                }
            } catch (Exception e) {
                // Transient (hub or cloud unreachable mid-pass): leave the
                // intent as-is — the next poll re-derives and retries.
                Log.d(TAG, "intent " + intent.id + " deferred: " + e.getMessage());
            }
        }

        reportFullInventoryIfDue(token);
    }

    // ------------------------------------------------------------------
    // Add

    /** Returns false when the hub looked unreachable (caller skips further adds this pass). */
    private static boolean executeAdd(Context context, String token, CassetteSyncApi.Intent intent,
                                      CassetteSyncApi.IntentTracks resolved) throws Exception {
        DownloaderManager manager = DownloadUtil.getDownloadTracker(context);

        List<CassetteSyncApi.SyncTrack> tracks = new ArrayList<>();
        for (CassetteSyncApi.SyncTrack track : resolved.tracks) {
            if (!track.subsonicTrackId.isEmpty()) tracks.add(track);
        }
        if (tracks.isEmpty()) {
            CassetteSyncApi.updateIntent(token, intent.id, "complete", null);
            return true;
        }

        boolean allComplete = true;
        List<CassetteSyncApi.SyncTrack> toEnqueue = new ArrayList<>();
        for (CassetteSyncApi.SyncTrack track : tracks) {
            if (!manager.isCompleted(track.subsonicTrackId)) allComplete = false;
            if (!manager.isDownloaded(track.subsonicTrackId)) toEnqueue.add(track);
        }

        if (allComplete) {
            CassetteSyncApi.updateIntent(token, intent.id, "complete", null);
            List<CassetteSyncApi.InventoryItem> added = new ArrayList<>();
            for (CassetteSyncApi.SyncTrack track : tracks) {
                added.add(new CassetteSyncApi.InventoryItem(track.cassetteLocalId, track.mbid));
            }
            CassetteSyncApi.reportInventoryDiff(token, added, new ArrayList<>());
            materializeArtwork(context, resolved, tracks);
            Log.d(TAG, "intent " + intent.id + " complete (" + tracks.size() + " tracks)");
            return true;
        }

        if (!hasFreeSpaceForDownloads(context)) {
            Log.w(TAG, "intent " + intent.id + " parked: below free-space floor");
            return true;
        }

        if ("pending".equals(intent.state)) {
            try {
                CassetteSyncApi.updateIntent(token, intent.id, "syncing", null);
            } catch (Exception e) {
                Log.d(TAG, "syncing transition deferred: " + e.getMessage());
            }
        }

        // Resolve each missing track against the hub and hand it to Tempo's
        // downloader. The first getSong doubles as the reachability probe —
        // off the home LAN it fails and the intent stays syncing until the
        // phone is back in range.
        DownloadDao dao = AppDatabase.getInstance().downloadDao();
        Child firstChild = null;
        int enqueued = 0;
        for (CassetteSyncApi.SyncTrack track : toEnqueue) {
            Child child = fetchSong(track.subsonicTrackId);
            if (child == null) continue;
            if (firstChild == null) firstChild = child;

            Download row = new Download(child);
            row.setCassetteLocalId(track.cassetteLocalId);
            row.setMbid(track.mbid);
            manager.download(MappingUtil.mapDownload(child), row);
            enqueued++;
        }

        // Stamp identity onto tracks that were already enqueued/completed
        // outside this intent (manual downloads predating sync).
        for (CassetteSyncApi.SyncTrack track : tracks) {
            Download existing = dao.getOne(track.subsonicTrackId);
            if (existing != null && existing.getCassetteLocalId() == null) {
                existing.setCassetteLocalId(track.cassetteLocalId);
                existing.setMbid(track.mbid);
                dao.insert(existing);
            }
        }

        if (firstChild == null && !toEnqueue.isEmpty()) {
            // Hub unreachable — nothing resolved. Leave the intent for later.
            Log.d(TAG, "intent " + intent.id + " waiting on hub reachability");
            return false;
        }

        materializeArtwork(context, resolved, tracks);
        Log.d(TAG, "intent " + intent.id + " enqueued " + enqueued + "/" + tracks.size() + " tracks");
        return true;
    }

    // ------------------------------------------------------------------
    // Remove

    private static void executeRemove(Context context, String token, CassetteSyncApi.Intent intent,
                                      CassetteSyncApi.IntentTracks resolved) throws Exception {
        DownloaderManager manager = DownloadUtil.getDownloadTracker(context);
        DownloadDao dao = AppDatabase.getInstance().downloadDao();

        List<CassetteSyncApi.InventoryItem> removed = new ArrayList<>();
        for (CassetteSyncApi.SyncTrack track : resolved.tracks) {
            String mediaId = null;
            if (!track.subsonicTrackId.isEmpty()) {
                mediaId = track.subsonicTrackId;
            } else {
                // Orphan removal keyed only by cassette_local_id.
                Download row = dao.getByCassetteLocalId(track.cassetteLocalId);
                if (row != null) mediaId = row.getId();
            }

            if (mediaId != null && (manager.isDownloaded(mediaId) || dao.getOne(mediaId) != null)) {
                manager.removeById(mediaId);
            }
            // Report every track: the server treats unknown ids as no-ops, so
            // this also heals inventory rows for files the phone lost track of.
            removed.add(new CassetteSyncApi.InventoryItem(track.cassetteLocalId, track.mbid));
        }

        CassetteSyncApi.updateIntent(token, intent.id, "complete", null);
        CassetteSyncApi.reportInventoryDiff(token, new ArrayList<>(), removed);
        Log.d(TAG, "remove intent " + intent.id + " complete (" + removed.size() + " tracks)");
    }

    // ------------------------------------------------------------------
    // Artwork

    /**
     * Album cover into Glide's disk cache (same URL the UI loads, so it hits
     * offline) and the attributed artist image from the Cassette catalog into
     * the local artist-art store. Both best-effort.
     */
    private static void materializeArtwork(Context context, CassetteSyncApi.IntentTracks resolved,
                                           List<CassetteSyncApi.SyncTrack> tracks) {
        // The Room row (a Child) carries coverArtId/artistId without a network
        // round-trip; fall back to the hub only when no row landed yet.
        Child sample = AppDatabase.getInstance().downloadDao().getOne(tracks.get(0).subsonicTrackId);
        if (sample == null) sample = fetchSong(tracks.get(0).subsonicTrackId);
        if (sample == null) return;

        String coverArtId = sample.getCoverArtId();
        if (coverArtId != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    CustomGlideRequest.Builder.from(context, coverArtId, CustomGlideRequest.ResourceType.Album)
                            .build()
                            .preload());
        }

        if (resolved.artistImageUrl != null && sample.getArtistId() != null) {
            CassetteArtistArt.materialize(context, sample.getArtistId(), resolved.artistImageUrl);
        }
    }

    // ------------------------------------------------------------------
    // Inventory

    private static void reportFullInventoryIfDue(String token) {
        long now = System.currentTimeMillis();
        if (now - lastFullInventoryAt < FULL_INVENTORY_MIN_INTERVAL_MS) return;

        try {
            List<Download> rows = AppDatabase.getInstance().downloadDao().getAllDownloadedSync();
            List<CassetteSyncApi.InventoryItem> items = new ArrayList<>();
            for (Download row : rows) {
                if (row.getCassetteLocalId() != null) {
                    items.add(new CassetteSyncApi.InventoryItem(row.getCassetteLocalId(), row.getMbid()));
                }
            }
            CassetteSyncApi.reportFullInventory(token, items);
            lastFullInventoryAt = now;
        } catch (Exception e) {
            Log.d(TAG, "full inventory deferred: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private static boolean hasFreeSpaceForDownloads(Context context) {
        try {
            // Same volume selection as DownloadUtil.getDownloadDirectory.
            java.io.File[] dirs = context.getExternalFilesDirs(null);
            int preferred = Preferences.getDownloadStoragePreference();
            java.io.File dir = (preferred == 1 && dirs.length > 1 && dirs[1] != null)
                    ? dirs[1]
                    : (dirs.length > 0 && dirs[0] != null ? dirs[0] : context.getFilesDir());
            return new android.os.StatFs(dir.getPath()).getAvailableBytes() > FREE_SPACE_FLOOR_BYTES;
        } catch (Exception e) {
            Log.w(TAG, "free-space check failed, allowing downloads: " + e.getMessage());
            return true;
        }
    }

    private static Child fetchSong(String id) {
        try {
            Response<ApiResponse> response = App
                    .getSubsonicClientInstance(false)
                    .getBrowsingClient()
                    .getSong(id)
                    .execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().getSubsonicResponse().getSong();
            }
        } catch (Exception e) {
            Log.d(TAG, "getSong " + id + " failed: " + e.getMessage());
        }
        return null;
    }
}
