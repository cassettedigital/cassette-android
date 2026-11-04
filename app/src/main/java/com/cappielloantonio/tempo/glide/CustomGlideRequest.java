package com.cappielloantonio.tempo.glide;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.util.CassetteImageLadder;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.Util;
import com.google.android.material.elevation.SurfaceColors;

import java.util.Map;

public class CustomGlideRequest {
    private static final String TAG = "CustomGlideRequest";

    /**
     * cassette: cover corner radius, in PIXELS, converted from a dp value.
     *
     * Glide's RoundedCorners takes pixels, and the preference was being handed
     * to it raw. A stored "12" meant 12 physical pixels, so the same album
     * cover was noticeably rounder on a low-density screen than on a Pixel and
     * matched no design value on any of them. iOS asks for 5pt on a row cover
     * (RoundedImage.swift), which is what the default here now resolves to.
     */
    public static final int CORNER_RADIUS = dpToPx(
            Preferences.isCornerRoundingEnabled() ? Preferences.getRoundedCornerSize() : 0);

    private static int dpToPx(int dp) {
        if (dp <= 0) return 0;
        return Math.round(dp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }

    public static final DiskCacheStrategy DEFAULT_DISK_CACHE_STRATEGY = DiskCacheStrategy.ALL;

    public enum ResourceType {
        Unknown,
        Album,
        Artist,
        Folder,
        Directory,
        Playlist,
        Podcast,
        Radio,
        Song,
    }

    public static RequestOptions createRequestOptions(Context context, Object signature, ResourceType type) {
        return new RequestOptions()
                .placeholder(new ColorDrawable(context.getColor(com.cappielloantonio.tempo.R.color.md_theme_dark_surfaceContainerHigh)))
                .fallback(getPlaceholder(context, type))
                .error(getPlaceholder(context, type))
                .diskCacheStrategy(DEFAULT_DISK_CACHE_STRATEGY)
                .signature(new ObjectKey(signature != null ? signature : 0))
                .transform(new CenterCrop(), new RoundedCorners(CustomGlideRequest.CORNER_RADIUS));
    }

    @Nullable
    private static Drawable getPlaceholder(Context context, ResourceType type) {
        switch (type) {
            case Album:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_album);
            case Artist:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_artist);
            case Folder:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_folder);
            case Directory:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_directory);
            case Playlist:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_playlist);
            case Podcast:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_podcast);
            case Radio:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_radio);
            case Song:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_song);
            default:
            case Unknown:
                return new ColorDrawable(context.getColor(com.cappielloantonio.tempo.R.color.md_theme_dark_surfaceContainerHigh));
        }
    }

    /**
     * cassette: cloud playlist covers (and any future absolute art) arrive as
     * https URLs, not Subsonic coverArt ids. Pass those through unchanged so
     * Glide / MediaItem artwork loaders don't wrap them in getCoverArt?id=.
     */
    public static boolean isRemoteCoverUrl(@Nullable String item) {
        if (item == null || item.isEmpty()) return false;
        return item.startsWith("http://") || item.startsWith("https://");
    }

    public static String createUrl(String item, int size) {
        if (isRemoteCoverUrl(item)) {
            return item;
        }

        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        StringBuilder uri = new StringBuilder();

        uri.append(App.getSubsonicClientInstance(false).getUrl());
        uri.append("getCoverArt");

        if (params.containsKey("u") && params.get("u") != null)
            uri.append("?u=").append(Util.encode(params.get("u")));
        if (params.containsKey("p") && params.get("p") != null)
            uri.append("&p=").append(params.get("p"));
        if (params.containsKey("s") && params.get("s") != null)
            uri.append("&s=").append(params.get("s"));
        if (params.containsKey("t") && params.get("t") != null)
            uri.append("&t=").append(params.get("t"));
        if (params.containsKey("v") && params.get("v") != null)
            uri.append("&v=").append(params.get("v"));
        if (params.containsKey("c") && params.get("c") != null)
            uri.append("&c=").append(params.get("c"));
        // cassette: always a ladder size (CassetteImageLadder): no size (-1, the default) is the top rung,
        // never the full original, which could be a 3000px file for a 48dp row.
        uri.append("&size=").append(CassetteImageLadder.ladderSize(size));

        uri.append("&id=").append(item);

        Log.d(TAG, "createUrl() " + uri);

        return uri.toString();
    }

    public static class Builder {
        private final RequestManager requestManager;
        private Object item;

        private Builder(Context context, String item, ResourceType type) {
            this.requestManager = Glide.with(context);

            if (item != null && !Preferences.isDataSavingMode()) {
                // Remote https covers (cloud playlists) load as-is; Subsonic
                // coverArt ids still go through getCoverArt.
                this.item = isRemoteCoverUrl(item)
                        ? item
                        : createUrl(item, Preferences.getImageSize());
            }

            // cassette: local-file art wins over the remote URL — instant,
            // offline, and (for covers) stable. Artist photos and album/song
            // covers are synced to the device (CassetteArtistArt /
            // CassetteAlbumArt); when a local file exists we load it directly.
            Object signature = item;
            java.io.File local = null;
            if (item != null) {
                if (type == ResourceType.Artist) {
                    local = com.cappielloantonio.tempo.util.CassetteArtistArt.localFile(context, item);
                } else if (type == ResourceType.Album || type == ResourceType.Song) {
                    local = com.cappielloantonio.tempo.util.CassetteAlbumArt.localFile(context, item);
                }
            }
            if (local != null) {
                this.item = local;
                // Bust Glide's own cache when the file is overwritten (a
                // re-pulled cover keeps the same path): fold the mtime into the
                // signature so a changed cover actually re-renders.
                signature = local.getPath() + ":" + local.lastModified();
            }

            // Curated playlist presets ship as SVG on the web; phones use a
            // bundled PNG twin (production doesn't serve the PNGs yet).
            String assetPath = bundledPlaylistCoverAsset(item);
            if (assetPath != null) {
                byte[] bytes = readAssetBytes(context, assetPath);
                if (bytes != null) {
                    this.item = bytes;
                    signature = assetPath;
                }
            }

            requestManager.applyDefaultRequestOptions(createRequestOptions(context, signature, type));
        }

        /** e.g. playlist-covers/preset-07.png for …/preset-07.svg|.png URLs */
        @Nullable
        private static String bundledPlaylistCoverAsset(@Nullable String coverUrl) {
            if (coverUrl == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("playlist-covers/(preset-\\d+)\\.(?:svg|png)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(coverUrl);
            if (!m.find()) return null;
            return "playlist-covers/" + m.group(1).toLowerCase(java.util.Locale.US) + ".png";
        }

        @Nullable
        private static byte[] readAssetBytes(Context context, String assetPath) {
            try (java.io.InputStream in = context.getAssets().open(assetPath);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            } catch (Exception e) {
                Log.w(TAG, "bundled playlist cover missing: " + assetPath, e);
                return null;
            }
        }

        public static Builder from(Context context, String item, ResourceType type) {
            return new Builder(context, item, type);
        }

        public RequestBuilder<Drawable> build() {
            return requestManager
                    .load(item)
                    .transition(DrawableTransitionOptions.withCrossFade());
        }
    }
}
