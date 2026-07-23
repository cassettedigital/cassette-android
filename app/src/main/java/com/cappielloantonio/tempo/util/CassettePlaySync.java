package com.cappielloantonio.tempo.util;

import android.util.Log;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.model.Chronology;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cassette play spine (A3): batch-report completed plays to cassette.digital's
 * /api/sync/plays so listening history travels across surfaces. (Web/desktop
 * already write via the Mac sidecar; iOS via ScrobbleSyncer.)
 * <p>
 * Source = the local {@code chronology} table (one row per track, latest play,
 * written on every completed/auto-advanced play). A SharedPreferences
 * high-water mark over {@code timestamp} tracks what's already been sent — no
 * Room migration needed, and because play timestamps are monotonic
 * ({@code System.currentTimeMillis()} at play time) {@code timestamp > highwater}
 * never misses a play.
 * <p>
 * Cadence: fired from the player's completed-play callback (event-driven, no
 * polling) and run off that thread. Durable chronology rows mean a failed flush
 * (offline / not paired) is simply retried on the next completed play.
 */
public final class CassettePlaySync {
    private static final String TAG = "CassettePlaySync";
    private static final int BATCH_LIMIT = 500; // the endpoint's per-request cap
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CassettePlaySync() {
    }

    /** Fire-and-forget flush on a background thread. Safe to call from the
     *  player callback. */
    public static void flushAsync() {
        EXECUTOR.execute(CassettePlaySync::flush);
    }

    private static void flush() {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null) return; // not paired to a cassette account
        String server = Preferences.getServerId();
        if (server == null) return;

        long highwater = Preferences.getCassetteCloudPlayHighwater();
        List<Chronology> rows;
        try {
            rows = AppDatabase.getInstance().chronologyDao()
                    .getPlayedSince(server, highwater, BATCH_LIMIT);
        } catch (Exception e) {
            Log.w(TAG, "cloud play sync: query failed: " + e.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) return;

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        List<CassetteSyncApi.PlayItem> plays = new ArrayList<>(rows.size());
        long maxTimestamp = highwater;
        for (Chronology c : rows) {
            String artist = c.getArtist() != null ? c.getArtist() : "";
            String title = c.getTitle() != null ? c.getTitle() : "";
            int duration = c.getDuration() != null ? c.getDuration() : 0;
            // Same-input, same-id as the Go sidecar / Swift port / web library index.
            String localId = CassetteLocalId.compute(artist, title, duration);
            // Send the stable MusicBrainz id alongside the fingerprint so other
            // devices can resolve this play even when their computed fingerprint
            // differs (the phones feed a slightly different duration than the hub).
            plays.add(new CassetteSyncApi.PlayItem(localId, c.getMbid(), iso.format(new Date(c.getTimestamp()))));
            if (c.getTimestamp() > maxTimestamp) maxTimestamp = c.getTimestamp();
        }

        try {
            CassetteSyncApi.recordPlays(bearer, plays);
            Preferences.setCassetteCloudPlayHighwater(maxTimestamp);
            Log.d(TAG, "cloud play sync: reported " + plays.size() + " play(s)");
        } catch (IOException e) {
            // Leave the high-water mark; retried on the next completed play.
            Log.w(TAG, "cloud play sync failed (will retry): " + e.getMessage());
        }
    }
}
