package com.cappielloantonio.tempo.service;

import android.content.Context;
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
import com.cappielloantonio.tempo.util.CassetteAlbumArt;
import com.cappielloantonio.tempo.util.CassetteAlbumGroupKey;
import com.cappielloantonio.tempo.util.CassetteArtistArt;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.CassetteSyncStatus;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Cover freshness sweep: throttle the pass and the (larger) manifest fetch,
     *  and cap how many covers can be (re)pulled per pass, so a big library
     *  backfills over several passes instead of one burst and a broken hub never
     *  loops. */
    private static final long COVER_SWEEP_MIN_INTERVAL_MS = 120_000;
    private static final long COVER_MANIFEST_TTL_MS = 300_000;
    private static final int MAX_COVER_PULLS_PER_PASS = 40;

    /** Track content-freshness sweep: the audio sibling of the cover sweep above.
     *  Same throttle shape — a re-rip that changes a track's bytes without
     *  changing its title/duration (invisible to cassette_local_id by design)
     *  is rare enough that checking every pass would be wasted chatter. No
     *  per-pass pull cap needed here: this pass only marks a stale track for
     *  removal, it never downloads — the removal converges through the SAME
     *  add-intent machinery a normal sync already uses, which has its own
     *  pacing. */
    private static final long TRACK_FRESHNESS_SWEEP_MIN_INTERVAL_MS = 120_000;
    private static volatile long lastTrackFreshnessSweepAt = 0;

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
    private static volatile long lastCoverSweepAt = 0;
    private static volatile long coverManifestFetchedAt = 0;
    private static volatile List<CassetteSyncApi.ArtworkAlbum> cachedCoverManifest = null;

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
            // cassette: Server Mode is account-sourced (mirrors iOS). The phone
            // only reads it; the desktop writes it. Default/offline-first is off.
            Preferences.setServerMode(account.serverMode);
            // cassette diagnostics: opt-out consent rides the same account rails.
            // Gates AUTO crash/hang uploads only — manual feedback ignores it.
            Preferences.setCassetteDiagnosticsConsent(account.diagnosticsConsent);
            // cassette: profile avatar (photo URL + default disc) for the toolbar chip.
            Preferences.setCassetteAccountImage(account.image);
            Preferences.setCassetteAvatarPreset(account.avatarPreset != null ? account.avatarPreset : "silhouette");
            Preferences.setCassetteAccountName(account.name);
            Preferences.setCassetteAccountEmail(account.email);
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

        // The banner and the notification read from here: how many albums this
        // pass still has to move, before any of the work starts.
        int outstanding = 0;
        for (CassetteSyncApi.Intent intent : intents) {
            if (!"remove".equals(intent.intentKind)) outstanding++;
        }
        CassetteSyncStatus.notePass(outstanding);

        // The floor must also govern the ACTIVE queue: already-enqueued
        // downloads would otherwise keep writing straight past it (the
        // enqueue-time check alone can't help a queue that outsizes the
        // disk). Paused work resumes automatically once space frees up.
        try {
            boolean roomToWork = hasFreeSpaceForDownloads(context);
            CassetteSyncStatus.noteBlocked(
                    DownloadUtil.getDownloadManager(context).getNotMetRequirements() != 0,
                    !roomToWork);
            if (roomToWork) {
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
        syncAlbumCoversIfDue(context, token);
        sweepTrackFreshnessIfDue(context, token);
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
            CassetteSyncStatus.noteAlbumComplete();
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
            // Sync the cover to a local file so it's instant, offline, and stable
            // — the same treatment artist art gets, and what makes covers behave
            // like an iPod's. The freshness sweep (syncAlbumCovers) stamps the
            // folder fingerprint later; here we just get the album its cover the
            // moment it lands.
            CassetteAlbumArt.materialize(context, coverArtId,
                    CustomGlideRequest.createUrl(coverArtId, Preferences.getImageSize()));
        }

        if (resolved.artistImageUrl != null && sample.getArtistId() != null) {
            CassetteArtistArt.materialize(context, sample.getArtistId(), resolved.artistImageUrl,
                    resolved.artistImageFallbackUrl);
        }
    }

    /**
     * Bring every downloaded album's cover to a fresh local file — the freshness
     * half of cover sync. Reads the artwork manifest (one entry per owned album,
     * carrying the folder-cover fingerprint), matches each downloaded album to it
     * by group key, and re-pulls a cover only when its fingerprint has moved — or
     * when no local file exists yet, which backfills a library synced before
     * covers went local. Bounded three ways so it can't run away: the pass is
     * throttled, the manifest is cached, and at most MAX_COVER_PULLS_PER_PASS
     * covers are (re)pulled per pass; failures back off for ten minutes.
     */
    private static void syncAlbumCoversIfDue(Context context, String token) {
        long now = System.currentTimeMillis();
        if (now - lastCoverSweepAt < COVER_SWEEP_MIN_INTERVAL_MS) return;
        lastCoverSweepAt = now;

        try {
            if (cachedCoverManifest == null || now - coverManifestFetchedAt > COVER_MANIFEST_TTL_MS) {
                cachedCoverManifest = CassetteSyncApi.getDeviceArtworkManifest(token);
                coverManifestFetchedAt = now;
            }
            List<CassetteSyncApi.ArtworkAlbum> manifest = cachedCoverManifest;
            if (manifest == null) return;

            // Fingerprint by album group key — artist_name + album_name is the
            // only join the manifest offers.
            Map<String, String> fingerprintByKey = new HashMap<>();
            // Full manifest row by the same key, so the artist face can re-pull off
            // its own artist_version alongside the cover's local_cover_version.
            Map<String, CassetteSyncApi.ArtworkAlbum> manifestByKey = new HashMap<>();
            for (CassetteSyncApi.ArtworkAlbum a : manifest) {
                if (a.artistName == null || a.albumName == null) continue;
                String key = CassetteAlbumGroupKey.keyFromEffective(a.artistName, a.albumName);
                manifestByKey.put(key, a);
                if (a.localCoverVersion != null) fingerprintByKey.put(key, a.localCoverVersion);
            }

            // Owned albums, grouped by album id (fall back to the cover id for a
            // row with no album id).
            List<Download> rows = AppDatabase.getInstance().downloadDao().getAllDownloadedSync();
            Map<String, List<Download>> byAlbum = new LinkedHashMap<>();
            for (Download row : rows) {
                String albumId = row.getAlbumId() != null ? row.getAlbumId() : ("cover:" + row.getCoverArtId());
                byAlbum.computeIfAbsent(albumId, k -> new ArrayList<>()).add(row);
            }

            int pulls = 0;
            int matched = 0;
            for (Map.Entry<String, List<Download>> entry : byAlbum.entrySet()) {
                if (pulls >= MAX_COVER_PULLS_PER_PASS) break;

                List<Download> group = entry.getValue();
                Download sample = group.get(0);
                String coverArtId = sample.getCoverArtId();
                if (coverArtId == null) continue;

                List<String> artists = new ArrayList<>();
                for (Download row : group) if (row.getArtist() != null) artists.add(row.getArtist());
                String albumName = sample.getAlbum() != null ? sample.getAlbum() : "";
                String groupKey = CassetteAlbumGroupKey.keyFromTracks(artists, albumName);

                // Artist face freshness — run BEFORE the cover logic below, which
                // `continue`s for a current cover and would otherwise skip an artist
                // photo that changed while the cover didn't. Re-pull when the catalog's
                // artist_version moved (a corrected or user-picked face); the per-artist
                // applied-version store keeps it idempotent across the many albums an
                // artist owns. Shares the per-pass pull budget so the sweep stays bounded.
                CassetteSyncApi.ArtworkAlbum manifestRow = manifestByKey.get(groupKey);
                String rowArtistId = sample.getArtistId();
                if (pulls < MAX_COVER_PULLS_PER_PASS && manifestRow != null
                        && manifestRow.artistImageUrl != null && rowArtistId != null) {
                    boolean artistPulled = CassetteArtistArt.materializeVersioned(
                            context, rowArtistId, manifestRow.artistImageUrl, manifestRow.artistImageFallbackUrl,
                            manifestRow.artistVersion);
                    if (artistPulled) pulls++;
                }

                String fingerprint = fingerprintByKey.get(groupKey); // may be null
                if (fingerprint != null) matched++;
                String coverKey = CassetteAlbumArt.keyFor(coverArtId);
                String applied = CassetteAlbumArt.getAppliedFingerprint(context, coverKey);
                boolean localExists = CassetteAlbumArt.localFile(context, coverArtId) != null;
                boolean changed = fingerprint != null && !fingerprint.equals(applied);

                if (localExists && !changed) {
                    // Current. If we've only just learned the fingerprint for a
                    // cover we already hold, stamp it — no redundant re-pull.
                    if (fingerprint != null && applied == null) {
                        CassetteAlbumArt.setAppliedFingerprint(context, coverKey, fingerprint);
                    }
                    continue;
                }

                if (!CassetteAlbumArt.mayAttempt(coverKey)) continue; // backing off

                // Cache-bust the (static-suffixed) getCoverArt URL with the
                // fingerprint so no intermediary can hand back stale bytes.
                String url = CustomGlideRequest.createUrl(coverArtId, Preferences.getImageSize())
                        + "&_cv=" + (fingerprint != null ? fingerprint : "0");
                boolean ok = CassetteAlbumArt.materialize(context, coverArtId, url);
                if (ok) {
                    if (fingerprint != null) CassetteAlbumArt.setAppliedFingerprint(context, coverKey, fingerprint);
                    pulls++;
                } else {
                    CassetteAlbumArt.noteFailure(coverKey);
                }
            }

            Log.d(TAG, "cover sweep: " + byAlbum.size() + " albums, " + matched
                    + " matched a fingerprint, " + pulls + " (re)pulled this pass");
        } catch (Throwable e) {
            // Best-effort artwork pass: a bad cover, a manifest hiccup, or any
            // static-init surprise must never crash the sync thread (which would
            // take the app down). Swallow and retry next pass.
            Log.w(TAG, "cover sweep skipped: " + e);
        }
    }

    /**
     * Content-freshness sweep: the hub is the source, this phone a satellite that follows every change at the
     * source without needing a reason, and holds nothing of its own (the founder, 2026-11-07; BUG-897). For every
     * owned track the manifest (GET /devices/:id/track-freshness) gives the hub's current audio_version, when the
     * song's file came into being on the hub, and when this phone made its copy.
     *
     * Download.contentFingerprint is the version this phone's copy was made from, set only when that is known:
     * - known, same kind, different value: the hub changed the song; fetch it again.
     * - known, different kind (the part before ':'): the same song measured a new way (BUG-882); take it.
     * - unknown (a copy made before this rule, or freshly downloaded): if the hub's file is newer than the copy,
     *   the copy was made from a file the hub has since replaced, so fetch it again; otherwise the copy was made
     *   from this very file, so record its version. With no dates yet, wait, and never adopt a version blind.
     *
     * Fetching again reuses the proven path: remove the copy and report it, the server's reconciler sees the
     * track still DESIRED but no longer ACTUAL and queues a normal add intent, and the re-added copy, reported
     * with a fresh date, is traced on the next sweep. Because that removes first, it happens only when the
     * computer answers in the same pass, and at most STALE_REFETCH_PER_PASS at a time.
     */
    private static void sweepTrackFreshnessIfDue(Context context, String token) {
        long now = System.currentTimeMillis();
        if (now - lastTrackFreshnessSweepAt < TRACK_FRESHNESS_SWEEP_MIN_INTERVAL_MS) return;
        lastTrackFreshnessSweepAt = now;

        try {
            DownloadDao dao = AppDatabase.getInstance().downloadDao();
            forgetAdoptedFingerprintsOnce(context, dao);

            List<CassetteSyncApi.TrackFreshness> manifest = CassetteSyncApi.getTrackFreshnessManifest(token);
            if (manifest.isEmpty()) return;

            DownloaderManager manager = DownloadUtil.getDownloadTracker(context);
            int traced = 0, remeasured = 0;
            List<CassetteSyncApi.InventoryItem> staleRemoved = new ArrayList<>();
            // Fetching again here means removing first (the re-add follows through the reconciler), so a stale copy
            // is only let go when the computer answers in this very pass, and a few at a time: away from home, or
            // with the computer asleep, the old copy stays rather than leaving a gap.
            Boolean hubAnswers = null;

            for (CassetteSyncApi.TrackFreshness entry : manifest) {
                if (entry.audioVersion == null) continue; // UNKNOWN: no signal yet
                Download row = dao.getByCassetteLocalId(entry.cassetteLocalId);
                if (row == null) continue; // not actually on this device: nothing to check

                String madeFrom = row.getContentFingerprint();
                boolean stale;
                if (madeFrom == null) {
                    if (entry.fileCreatedAtMs == null || entry.copyMadeAtMs == null) continue; // wait for dates
                    stale = entry.fileCreatedAtMs > entry.copyMadeAtMs + COPY_DATE_SLACK_MS;
                    if (!stale) {
                        row.setContentFingerprint(entry.audioVersion);
                        dao.insert(row); // REPLACE, the upsert pattern used elsewhere in this file
                        traced++;
                    }
                } else if (madeFrom.equals(entry.audioVersion)) {
                    stale = false;
                } else if (!fingerprintKind(madeFrom).equals(fingerprintKind(entry.audioVersion))) {
                    row.setContentFingerprint(entry.audioVersion);
                    dao.insert(row);
                    remeasured++;
                    stale = false;
                } else {
                    stale = true;
                }
                if (stale) {
                    if (staleRemoved.size() >= STALE_REFETCH_PER_PASS) continue; // the rest wait for later passes
                    if (hubAnswers == null) hubAnswers = fetchSong(row.getId()) != null;
                    if (!hubAnswers) continue;
                    if (manager.isDownloaded(row.getId()) || dao.getOne(row.getId()) != null) {
                        manager.removeById(row.getId());
                    }
                    staleRemoved.add(new CassetteSyncApi.InventoryItem(entry.cassetteLocalId, row.getMbid()));
                }
            }

            if (!staleRemoved.isEmpty()) {
                CassetteSyncApi.reportInventoryDiff(token, new ArrayList<>(), staleRemoved);
            }
            if (traced > 0 || remeasured > 0 || !staleRemoved.isEmpty()) {
                Log.d(TAG, "track-freshness sweep: " + traced + " traced, " + remeasured + " re-measured, "
                        + staleRemoved.size() + " stale (queued for re-fetch)");
            }
        } catch (Throwable e) {
            // Best-effort pass, same posture as the cover sweep: a manifest hiccup
            // must never crash the sync thread. Swallow and retry next pass.
            Log.w(TAG, "track-freshness sweep skipped: " + e);
        }
    }

    /** At most this many stale copies are let go (to be fetched again) in one pass. */
    private static final int STALE_REFETCH_PER_PASS = 50;

    /** A copy reported within this long after its file came into being still counts as made from that file. */
    private static final long COPY_DATE_SLACK_MS = 60_000L;

    /** How a fingerprint was measured: the part before ':' ("a1", "f1"), or "" for one from before kinds. */
    private static String fingerprintKind(String fingerprint) {
        int colon = fingerprint.indexOf(':');
        return colon > 0 ? fingerprint.substring(0, colon) : "";
    }

    /** Every fingerprint stored before the satellite rule was adopted from the first manifest, never traced to the
     *  copy itself (BUG-897): forget them once, so each copy is traced again by its dates. */
    private static void forgetAdoptedFingerprintsOnce(Context context, DownloadDao dao) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("cassette_track_freshness", Context.MODE_PRIVATE);
        if (prefs.getBoolean("adopted_forgotten_v1", false)) return;
        dao.forgetAllContentFingerprints();
        prefs.edit().putBoolean("adopted_forgotten_v1", true).apply();
        Log.d(TAG, "track-freshness: forgot adopted fingerprints; every copy will be traced again");
    }

    // ------------------------------------------------------------------
    // Inventory

    /** cassette: after a Put back, say what is here now so the cloud queues the album again (iPhone reportInventoryNow). Off the main thread. */
    public static void reportInventoryNow(Context context) {
        String token = Preferences.getCassetteBearerToken();
        if (token == null) return;
        lastFullInventoryAt = 0;
        reportFullInventoryIfDue(token);
        kick(context);
    }

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
