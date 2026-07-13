package com.cappielloantonio.tempo.util;

import android.content.Context;
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

    /**
     * Download the catalog image for an artist if we don't hold one yet.
     * Idempotent and best-effort; called from the sync engine's worker thread.
     */
    public static void materialize(Context context, String artistId, String imageUrl) {
        if (artistId == null || artistId.isEmpty() || imageUrl == null) return;

        File target = new File(dir(context), normalizeKey(artistId) + ".img");
        if (target.exists() && target.length() > 0) return;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        File tmp = new File(target.getPath() + ".tmp");
        try (Response response = client.newCall(new Request.Builder().url(imageUrl).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "artist image fetch failed: HTTP " + response.code());
                return;
            }
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "could not move artist image into place");
            } else {
                Log.d(TAG, "materialized artist image for " + artistId);
            }
        } catch (IOException e) {
            Log.w(TAG, "artist image fetch failed: " + e.getMessage());
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                Log.d(TAG, "stale tmp left behind for " + artistId);
            }
        }
    }
}
