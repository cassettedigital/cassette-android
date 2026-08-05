package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceBitmapLoader;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * cassette: LOCAL-FIRST artwork for the OS now-playing surfaces (the
 * notification, the lock screen, and Android Auto).
 *
 * media3 resolves every MediaItem's {@code artworkUri} through the session's
 * BitmapLoader. The stock loader ({@link DataSourceBitmapLoader}) always fetches
 * the remote getCoverArt URL, so lock-screen / Auto art needs a live connection
 * to the hub and shows blank on a cold, offline launch — even though the cover
 * is already on disk. The in-app UI already prefers the local file
 * (CustomGlideRequest); this closes the same gap for the OS surfaces so Android
 * matches iOS (OS now-playing art that is local-first and works offline).
 *
 * How it hooks in: media3 funnels artwork resolution to {@link #loadBitmap(Uri)}
 * (the notification's loadBitmapFromMetadata default, the legacy/Auto path, and
 * the lock-screen metadata all converge here). We read the Subsonic coverArt id
 * out of the getCoverArt URL and, when {@link CassetteAlbumArt} has that cover
 * on disk, decode the local file. Everything else — an absolute https playlist
 * cover, a file:// uri, or a cover not yet synced — falls through to the stock
 * remote loader.
 *
 * Same-album stability is preserved by wrapping this in media3's
 * {@code CacheBitmapLoader} at the call site (see MediaService): the artworkUri
 * stays the stable per-album getCoverArt URL, so consecutive tracks that share a
 * cover reuse the cached bitmap and the lock-screen art never blinks.
 */
@UnstableApi
public final class CassetteBitmapLoader implements BitmapLoader {
    // Notification / lock-screen / Auto art never needs a large bitmap; cap the
    // decode so a multi-thousand-pixel folder cover can't OOM the loader thread.
    private static final int MAX_DIMENSION_PX = 1024;

    private final Context context;
    private final DataSourceBitmapLoader remote;
    private final ListeningExecutorService decodeExecutor;

    public CassetteBitmapLoader(Context context) {
        this.context = context.getApplicationContext();
        this.remote = new DataSourceBitmapLoader(this.context);
        this.decodeExecutor =
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    }

    @Override
    public boolean supportsMimeType(String mimeType) {
        return remote.supportsMimeType(mimeType);
    }

    @Override
    public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
        return remote.decodeBitmap(data);
    }

    @Override
    public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
        File local = localCoverFor(uri);
        if (local == null) {
            return remote.loadBitmap(uri);
        }
        // Decode the on-disk cover off-thread. If it ever fails (it shouldn't —
        // materialize() decode-probes before storing), fall back to the remote
        // loader so "local-first, remote fallback" holds even on a bad file.
        ListenableFuture<Bitmap> localFuture =
                decodeExecutor.submit(() -> decodeBounded(local));
        return Futures.catchingAsync(
                localFuture,
                Throwable.class,
                t -> remote.loadBitmap(uri),
                MoreExecutors.directExecutor());
    }

    /** The on-disk cover for a getCoverArt url, or null to fall back to remote. */
    @Nullable
    private File localCoverFor(@Nullable Uri uri) {
        if (uri == null) return null;
        // Only our getCoverArt urls carry a resolvable Subsonic id. Absolute
        // https covers (cloud playlists) and file:// uris go to the stock loader.
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.startsWith("http")) return null;
        String coverArtId;
        try {
            coverArtId = uri.getQueryParameter("id");
        } catch (UnsupportedOperationException e) {
            return null; // opaque uri, no query to read
        }
        if (coverArtId == null || coverArtId.isEmpty()) return null;
        return CassetteAlbumArt.localFile(context, coverArtId);
    }

    private static Bitmap decodeBounded(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest > 0 && longest / sample > MAX_DIMENSION_PX) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
        if (bitmap == null) {
            // Trips the catchingAsync fallback to the remote loader.
            throw new IllegalStateException("failed to decode local cover " + file.getPath());
        }
        return bitmap;
    }
}
