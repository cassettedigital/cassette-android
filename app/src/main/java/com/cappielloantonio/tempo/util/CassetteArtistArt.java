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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * On-device store for artist photos from Cassette's attributed catalog
 * pipeline (Commons/CAA images hosted on the cloud, licensed and credited —
 * the hub itself serves no artist art since its agents were emptied).
 *
 * Images arrive bundled with sync intents and are keyed by the hub's artist
 * id. Rendering hooks in at CustomGlideRequest: when a local file exists for
 * the artist being displayed, it wins over the (404-ing) getCoverArt URL —
 * so artist art works fully offline, like covers and music.
 */
public final class CassetteArtistArt {
    private static final String TAG = "CassetteArtistArt";
    private static final String DIR_NAME = "cassette_artist_art";
    private static final String PREFS_NAME = "cassette_artist_art_prefs";

    private CassetteArtistArt() {
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
        }
        return dir;
    }

    /**
     * Normalise the id the UI hands us to the plain artist id the store keys
     * on. Artist objects carry a Subsonic cover id ("ar-<artistId>_<hash>" —
     * Navidrome appends an updated-at cache-buster after an underscore),
     * songs a bare artistId; every variant must land on the same file.
     */
    private static String normalizeKey(String artistIdOrCoverId) {
        String key = artistIdOrCoverId.startsWith("ar-")
                ? artistIdOrCoverId.substring(3)
                : artistIdOrCoverId;
        int suffix = key.indexOf('_');
        if (suffix > 0) key = key.substring(0, suffix);
        return key.replaceAll("[^a-zA-Z0-9-]", "-");
    }

    /** The stored image for this artist, or null when none was synced. */
    @Nullable
    public static File localFile(Context context, String artistIdOrCoverId) {
        if (artistIdOrCoverId == null || artistIdOrCoverId.isEmpty()) return null;
        File file = new File(dir(context), normalizeKey(artistIdOrCoverId) + ".img");
        return file.exists() && file.length() > 0 ? file : null;
    }

    // --- freshness bookkeeping (the artist analogue of CassetteAlbumArt) ---

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The artist_version last successfully materialized for this artist key. */
    @Nullable
    public static String getAppliedVersion(Context context, String artistKey) {
        return prefs(context).getString("av:" + artistKey, null);
    }

    public static void setAppliedVersion(Context context, String artistKey, String version) {
        prefs(context).edit().putString("av:" + artistKey, version).apply();
    }

    /**
     * Download the catalog image for an artist if we don't hold one yet.
     * Idempotent and best-effort; called from the sync engine's worker thread on
     * album-add (the first-sync path — no version to compare against yet).
     */
    public static void materialize(Context context, String artistId, String imageUrl,
                                   @Nullable String fallbackUrl) {
        if (artistId == null || artistId.isEmpty() || imageUrl == null) return;
        File target = new File(dir(context), normalizeKey(artistId) + ".img");
        if (target.exists() && target.length() > 0) return; // fetch-once for first sync
        fetchEither(context, imageUrl, fallbackUrl, target, normalizeKey(artistId));
    }

    /**
     * Re-pull the artist image when the catalog's artist_version has moved since we
     * last applied one — the freshness half, mirroring the album-cover sweep. Without
     * this the store is fetch-once, so a CORRECTED or user-picked artist face (which
     * changes the image URL, and thus artist_version) never reaches a phone that
     * already synced an album by that artist. Overwrites in place and stamps the new
     * version. A same version + a real file on disk is a no-op.
     */
    public static boolean materializeVersioned(Context context, String artistId, String imageUrl,
                                               @Nullable String fallbackUrl, @Nullable String version) {
        if (artistId == null || artistId.isEmpty() || imageUrl == null) return false;
        String key = normalizeKey(artistId);
        File target = new File(dir(context), key + ".img");
        boolean current = version != null
                && version.equals(getAppliedVersion(context, key))
                && target.exists() && target.length() > 0;
        if (current) return false;
        boolean ok = fetchEither(context, imageUrl, fallbackUrl, target, key);
        if (ok && version != null) {
            setAppliedVersion(context, key, version);
        }
        return ok;
    }

    /**
     * Fetch {@code imageUrl} into {@code target}, OVERWRITING any existing file.
     * Decode-probes the body first so a non-image 200 (a CDN/interstitial error page)
     * can never stick as a permanent broken placeholder. Returns true only when a real
     * image landed. Best-effort; call from a worker thread.
     */
    /** The image ladder's size first (a 1200px photo, not the original); the original if that fails. */
    private static boolean fetchEither(Context context, String imageUrl, @Nullable String fallbackUrl,
                                       File target, String key) {
        if (fetchInto(context, imageUrl, target, key)) return true;
        return fallbackUrl != null && !fallbackUrl.equals(imageUrl) && fetchInto(context, fallbackUrl, target, key);
    }

    private static boolean fetchInto(Context context, String imageUrl, File target, String key) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        File tmp = new File(target.getPath() + ".tmp");
        try (Response response = client.newCall(new Request.Builder().url(imageUrl).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "artist image fetch failed: HTTP " + response.code());
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

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.w(TAG, "artist image decoded to nothing for " + key);
                return false;
            }

            if (!tmp.renameTo(target)) {
                Log.w(TAG, "could not move artist image into place");
                return false;
            }
            Log.d(TAG, "materialized artist image for " + key);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "artist image fetch failed: " + e.getMessage());
            return false;
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                Log.d(TAG, "stale tmp left behind for " + key);
            }
        }
    }
}
