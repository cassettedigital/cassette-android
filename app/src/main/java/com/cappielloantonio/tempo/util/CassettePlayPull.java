package com.cappielloantonio.tempo.util;

import android.util.Log;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.model.RemotePlay;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cassette play spine — Phase B (read): pull plays made on OTHER surfaces
 * (iOS / web / desktop) from /api/sync/plays into the local
 * {@code cassette_remote_play} table, so the phone's "recently played" can
 * reflect the whole account. Own plays (source_device=android) are skipped —
 * this device already logs those locally and is their cloud writer, so they are
 * never round-tripped (echo-proof).
 * <p>
 * A SharedPreferences high-water mark over {@code played_at} (epoch ms) is the
 * cursor. Runs off the UI thread; failures are retried on the next foreground.
 */
public final class CassettePlayPull {
    private static final String TAG = "CassettePlayPull";
    private static final int PULL_LIMIT = 1000; // the endpoint's max
    private static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000; // 90 days
    private static final String THIS_DEVICE = "android";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CassettePlayPull() {
    }

    /** Fire-and-forget pull on a background thread (call on app foreground). */
    public static void pullAsync() {
        EXECUTOR.execute(CassettePlayPull::pull);
    }

    private static void pull() {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null) return; // not paired to a cassette account

        long readHighwater = Preferences.getCassetteCloudPlayReadHighwater();

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        List<CassetteSyncApi.RemotePlayData> rows;
        try {
            String since = readHighwater > 0 ? iso.format(new Date(readHighwater)) : null;
            rows = CassetteSyncApi.listPlaysSince(bearer, since, PULL_LIMIT);
        } catch (IOException e) {
            Log.w(TAG, "cloud play pull failed (will retry): " + e.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) return;

        List<RemotePlay> remote = new ArrayList<>();
        long maxTimestamp = readHighwater;
        for (CassetteSyncApi.RemotePlayData row : rows) {
            long ts = parseIsoToEpoch(iso, row.playedAtIso);
            if (ts < 0) continue;
            // Advance the cursor over EVERY row (incl. own-device) so the next
            // pull's `since` skips them.
            if (ts > maxTimestamp) maxTimestamp = ts;
            // Skip this device's own plays — already logged + cloud-written here.
            if (THIS_DEVICE.equals(row.sourceDevice)) continue;
            if (row.cassetteLocalId == null) continue;
            remote.add(new RemotePlay(row.cassetteLocalId, ts, row.sourceDevice, row.mbid));
        }

        AppDatabase db = AppDatabase.getInstance();
        try {
            if (!remote.isEmpty()) db.remotePlayDao().upsertAll(remote);
            db.remotePlayDao().pruneOlderThan(System.currentTimeMillis() - RETENTION_MS);
        } catch (Exception e) {
            // Don't advance the cursor if we couldn't persist — retry next time.
            Log.w(TAG, "cloud play pull: db write failed: " + e.getMessage());
            return;
        }

        Preferences.setCassetteCloudPlayReadHighwater(maxTimestamp);
        Log.d(TAG, "cloud play pull: stored " + remote.size() + " remote play(s)");
    }

    private static long parseIsoToEpoch(SimpleDateFormat primary, String value) {
        if (value == null) return -1;
        try {
            return primary.parse(value).getTime();
        } catch (ParseException ignored) {
            // Fallback for a timestamp serialized without milliseconds.
            SimpleDateFormat noMs = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            noMs.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return noMs.parse(value).getTime();
            } catch (ParseException e) {
                return -1;
            }
        }
    }
}
