package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * On-device store for ALBUM/song cover art, so covers behave like an iPod's:
 * synced down to a real file, shown instantly and offline, and stable until the
 * source actually changes.
 *
 * The twin of {@link CassetteArtistArt}, with two differences the covers need:
 *   - it OVERWRITES on demand (artist art is fetch-once), because a cover must be
 *     re-pullable when the user swaps cover.jpg on their Mac; and
 *   - every write is gated behind a decode probe, so a Subsonic error body (which
 *     the hub can return with a 200) never lands as a "cover".
 *
 * Freshness is driven by the folder-cover fingerprint the cloud carries
 * (local_cover_version, migration 0082), compared per album by the sync engine;
 * this class just stores the last-applied value and enforces a retry back-off so
 * genuinely-absent art never loops. Rendering hooks in at CustomGlideRequest:
 * when a local file exists it wins over the getCoverArt URL.
 */
public final class CassetteAlbumArt {
    private static final String TAG = "CassetteAlbumArt";
    private static final String DIR_NAME = "cassette_album_art";
    private static final String PREFS_NAME = "cassette_cover_state";
    private static final long RETRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(10);
    // Precompiled: keyFor runs on every image bind (the render hot path).
    private static final java.util.regex.Pattern UNSAFE = java.util.regex.Pattern.compile("[^a-zA-Z0-9-]");

    /** In-memory retry back-off, keyed by cover key. In-memory on purpose: a
     *  relaunch gives every cover one fresh attempt (mirrors iOS). */
    private static final ConcurrentHashMap<String, Long> retryAfter = new ConcurrentHashMap<>();

    private CassetteAlbumArt() {
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
        }
        return dir;
    }

    /**
     * The stable file key for a cover id. Subsonic hands the same album's cover
     * under more than one shape — "al-&lt;albumId&gt;_&lt;ver&gt;" (album),
     * "dc-&lt;albumId&gt;:&lt;disc&gt;_&lt;ver&gt;" (Cassette disc-pool),
     * "mf-&lt;fileId&gt;_&lt;ver&gt;" (per-file) — and the render call and the
     * sync materialize must land on the SAME file. Strip the 3-char type prefix,
     * the ":disc" segment, and the "_version" suffix so every form of one album
     * collapses to one key.
     */
    public static String keyFor(String coverIdOrArtId) {
        String key = coverIdOrArtId;
        for (String p : new String[]{"al-", "dc-", "mf-", "pl-", "ar-"}) {
            if (key.startsWith(p)) {
                key = key.substring(3);
                break;
            }
        }
        int colon = key.indexOf(':');
        if (colon > 0) key = key.substring(0, colon);
        int underscore = key.indexOf('_');
        if (underscore > 0) key = key.substring(0, underscore);
        return UNSAFE.matcher(key).replaceAll("-");
    }

    /** The stored cover for this id, or null when none was synced. */
    @Nullable
    public static File localFile(Context context, String coverIdOrArtId) {
        if (coverIdOrArtId == null || coverIdOrArtId.isEmpty()) return null;
        File file = new File(dir(context), keyFor(coverIdOrArtId) + ".img");
        return file.exists() && file.length() > 0 ? file : null;
    }

    // --- freshness bookkeeping (per cover key) ---

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The folder-cover fingerprint last successfully materialized for this key. */
    @Nullable
    public static String getAppliedFingerprint(Context context, String coverKey) {
        return prefs(context).getString("fp:" + coverKey, null);
    }

    public static void setAppliedFingerprint(Context context, String coverKey, String fingerprint) {
        prefs(context).edit().putString("fp:" + coverKey, fingerprint).apply();
    }

    /** False while a recent failure's cooldown is still running. */
    public static boolean mayAttempt(String coverKey) {
        Long until = retryAfter.get(coverKey);
        return until == null || System.currentTimeMillis() >= until;
    }

    public static void noteFailure(String coverKey) {
        retryAfter.put(coverKey, System.currentTimeMillis() + RETRY_COOLDOWN_MS);
    }

    /**
     * Fetch the cover at {@code url} and store it for {@code coverArtId},
     * OVERWRITING any existing file. Returns true only when a real image landed.
     * Best-effort; call from a worker thread (does network + disk IO).
     */
    public static boolean materialize(Context context, String coverArtId, String url) {
        if (coverArtId == null || coverArtId.isEmpty() || url == null) return false;

        File target = new File(dir(context), keyFor(coverArtId) + ".img");
        File tmp = new File(target.getPath() + ".tmp");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        try (Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "cover fetch failed: HTTP " + response.code() + " for " + coverArtId);
                return false;
            }
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            // Decode probe: the hub can answer getCoverArt with a Subsonic XML
            // error and a 200, and a non-image body must never become a "cover".
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w(TAG, "cover fetch decoded to nothing for " + coverArtId);
                return false;
            }

            if (!tmp.renameTo(target)) {
                Log.w(TAG, "could not move cover into place for " + coverArtId);
                return false;
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cover fetch failed: " + e.getMessage());
            return false;
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                Log.d(TAG, "stale tmp left behind for " + coverArtId);
            }
        }
    }
}
