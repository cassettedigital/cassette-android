package com.cappielloantonio.tempo.util;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.InternetRadioStation;
import com.cappielloantonio.tempo.subsonic.models.PodcastEpisode;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class MappingUtil {
    public static List<MediaItem> mapMediaItems(List<Child> items) {
        ArrayList<MediaItem> mediaItems = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            Child media = items.get(i);
            // cassette: offline-first (Server Mode off) never streams. A track
            // that isn't fully on disk is dropped from the queue rather than
            // streamed — the Android analogue of iOS's silent skip-non-owned
            // gate (BackendAudioPlayer). In Server Mode this is a no-op.
            if (Preferences.isOfflineFirst()
                    && !DownloadUtil.getDownloadTracker(App.getContext()).isCompleted(media.getId())) {
                continue;
            }
            mediaItems.add(mapMediaItem(media));
        }

        return mediaItems;
    }

    public static MediaItem mapMediaItem(Child media) {
        Uri uri = getUri(media);
        // cassette: collapse an album-scoped cover id (al-/dc-, which Navidrome
        // version- and disc-suffixes: al-<id>_<ver>, dc-<id>:<disc>_<ver>) to ONE
        // stable per-album artwork URI, so media3's CacheBitmapLoader hits across the
        // album's tracks and the lock-screen / Android Auto art stops re-decoding and
        // blinking on a same-album next (most visible at a multi-disc boundary). Local
        // resolution is unchanged: CassetteAlbumArt.keyFor("al-"+albumId) == the same
        // album key the al-/dc- covers already materialized under, so it still reads
        // the local file first (offline-safe). Per-file (mf-) ids are deliberately left
        // ALONE — their cover is stored per file, so an album-key URI would miss it and
        // blank the art offline. The raw per-song cover id stays in the bundle below
        // for the in-app fragments.
        String coverArtId = media.getCoverArtId();
        String albumId = media.getAlbumId();
        String artworkCoverId = coverArtId;
        if (albumId != null && coverArtId != null
                && (coverArtId.startsWith("al-") || coverArtId.startsWith("dc-"))) {
            artworkCoverId = "al-" + albumId;
        }
        Uri artworkUri = Uri.parse(CustomGlideRequest.createUrl(artworkCoverId, Preferences.getImageSize()));

        Bundle bundle = new Bundle();
        bundle.putString("id", media.getId());
        bundle.putString("parentId", media.getParentId());
        bundle.putBoolean("isDir", media.isDir());
        bundle.putString("title", media.getTitle());
        bundle.putString("album", media.getAlbum());
        bundle.putString("artist", media.getArtist());
        bundle.putInt("track", media.getTrack() != null ? media.getTrack() : 0);
        bundle.putInt("year", media.getYear() != null ? media.getYear() : 0);
        bundle.putString("genre", media.getGenre());
        bundle.putString("coverArtId", media.getCoverArtId());
        bundle.putLong("size", media.getSize() != null ? media.getSize() : 0);
        bundle.putString("contentType", media.getContentType());
        bundle.putString("suffix", media.getSuffix());
        bundle.putString("transcodedContentType", media.getTranscodedContentType());
        bundle.putString("transcodedSuffix", media.getTranscodedSuffix());
        bundle.putInt("duration", media.getDuration() != null ? media.getDuration() : 0);
        bundle.putInt("bitrate", media.getBitrate() != null ? media.getBitrate() : 0);
        bundle.putString("path", media.getPath());
        bundle.putBoolean("isVideo", media.isVideo());
        bundle.putInt("userRating", media.getUserRating() != null ? media.getUserRating() : 0);
        bundle.putDouble("averageRating", media.getAverageRating() != null ? media.getAverageRating() : 0);
        bundle.putLong("playCount", media.getPlayCount() != null ? media.getPlayCount() : 0);
        bundle.putInt("discNumber", media.getDiscNumber() != null ? media.getDiscNumber() : 0);
        bundle.putLong("created", media.getCreated() != null ? media.getCreated().getTime() : 0);
        bundle.putLong("starred", media.getStarred() != null ? media.getStarred().getTime() : 0);
        bundle.putString("albumId", media.getAlbumId());
        bundle.putString("artistId", media.getArtistId());
        bundle.putString("type", Constants.MEDIA_TYPE_MUSIC);
        bundle.putLong("bookmarkPosition", media.getBookmarkPosition() != null ? media.getBookmarkPosition() : 0);
        bundle.putInt("originalWidth", media.getOriginalWidth() != null ? media.getOriginalWidth() : 0);
        bundle.putInt("originalHeight", media.getOriginalHeight() != null ? media.getOriginalHeight() : 0);
        bundle.putString("uri", uri.toString());
        bundle.putString("musicBrainzId", media.getMusicBrainzId());

        return new MediaItem.Builder()
                .setMediaId(media.getId())
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(media.getTitle())
                                .setTrackNumber(media.getTrack() != null ? media.getTrack() : 0)
                                .setDiscNumber(media.getDiscNumber() != null ? media.getDiscNumber() : 0)
                                .setReleaseYear(media.getYear() != null ? media.getYear() : 0)
                                .setAlbumTitle(media.getAlbum())
                                .setArtist(media.getArtist())
                                .setArtworkUri(artworkUri)
                                .setExtras(bundle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                )
                .setRequestMetadata(
                        new MediaItem.RequestMetadata.Builder()
                                .setMediaUri(uri)
                                .setExtras(bundle)
                                .build()
                )
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .setUri(uri)
                .build();
    }

    public static List<MediaItem> mapDownloads(List<Child> items) {
        ArrayList<MediaItem> downloads = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            downloads.add(mapDownload(items.get(i)));
        }

        return downloads;
    }

    public static MediaItem mapDownload(Child media) {
        Uri downloadUri = downloadUriFor(media.getId());

        return new MediaItem.Builder()
                .setMediaId(media.getId())
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(media.getTitle())
                                .setTrackNumber(media.getTrack() != null ? media.getTrack() : 0)
                                .setDiscNumber(media.getDiscNumber() != null ? media.getDiscNumber() : 0)
                                .setReleaseYear(media.getYear() != null ? media.getYear() : 0)
                                .setAlbumTitle(media.getAlbum())
                                .setArtist(media.getArtist())
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                )
                .setRequestMetadata(
                        new MediaItem.RequestMetadata.Builder()
                                .setMediaUri(downloadUri)
                                .build()
                )
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .setUri(downloadUri)
                .build();
    }

    /**
     * cassette: paired phones follow the ACCOUNT download tier (set on the web
     * Devices page, read from /api/sync/account) — lossless pulls the original
     * bytes, anything else the hub's mp3 transcode, exactly like iOS. The
     * manual-server dev door keeps Tempo's stock download preferences.
     */
    private static Uri downloadUriFor(String id) {
        if (Preferences.getCassetteBearerToken() != null) {
            return "lossless".equals(Preferences.getCassetteDownloadQuality())
                    ? MusicUtil.getDownloadUri(id)
                    : MusicUtil.getCassetteTranscodedDownloadUri(id);
        }

        return Preferences.preferTranscodedDownload()
                ? MusicUtil.getTranscodedDownloadUri(id)
                : MusicUtil.getDownloadUri(id);
    }

    public static MediaItem mapInternetRadioStation(InternetRadioStation internetRadioStation) {
        Uri uri = Uri.parse(internetRadioStation.getStreamUrl());

        // cassette: a station is the station, never its stream address (the old
        // artist line). The title is left empty so the stream's own title (the
        // song or show it sends) fills it; the station's name is the second line
        // and its picture the artwork (iPhone 8dcd3464, f3a3c506).
        Bundle bundle = new Bundle();
        bundle.putString("id", internetRadioStation.getId());
        bundle.putString("title", internetRadioStation.getName());
        bundle.putString("artist", internetRadioStation.getLabel() != null ? internetRadioStation.getLabel() : "");
        bundle.putString("uri", uri.toString());
        bundle.putString("type", Constants.MEDIA_TYPE_RADIO);
        if (internetRadioStation.getImage() != null) bundle.putString("coverArtId", internetRadioStation.getImage());

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setArtist(internetRadioStation.getName())
                .setStation(internetRadioStation.getName())
                .setExtras(bundle)
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (internetRadioStation.getImage() != null) metadata.setArtworkUri(Uri.parse(internetRadioStation.getImage()));

        return new MediaItem.Builder()
                .setMediaId(internetRadioStation.getId())
                .setMediaMetadata(metadata.build())
                .setRequestMetadata(
                        new MediaItem.RequestMetadata.Builder()
                                .setMediaUri(uri)
                                .setExtras(bundle)
                                .build()
                )
                .setUri(uri)
                .build();
    }


    public static MediaItem mapMediaItem(PodcastEpisode podcastEpisode) {
        Uri uri = getUri(podcastEpisode);
        Uri artworkUri = Uri.parse(CustomGlideRequest.createUrl(podcastEpisode.getCoverArtId(), Preferences.getImageSize()));

        Bundle bundle = new Bundle();
        bundle.putString("id", podcastEpisode.getId());
        bundle.putString("parentId", podcastEpisode.getParentId());
        bundle.putBoolean("isDir", podcastEpisode.isDir());
        bundle.putString("title", podcastEpisode.getTitle());
        bundle.putString("album", podcastEpisode.getAlbum());
        bundle.putString("artist", podcastEpisode.getArtist());
        bundle.putInt("year", podcastEpisode.getYear() != null ? podcastEpisode.getYear() : 0);
        bundle.putString("coverArtId", podcastEpisode.getCoverArtId());
        bundle.putLong("size", podcastEpisode.getSize() != null ? podcastEpisode.getSize() : 0);
        bundle.putString("contentType", podcastEpisode.getContentType());
        bundle.putString("suffix", podcastEpisode.getSuffix());
        bundle.putInt("duration", podcastEpisode.getDuration() != null ? podcastEpisode.getDuration() : 0);
        bundle.putInt("bitrate", podcastEpisode.getBitrate() != null ? podcastEpisode.getBitrate() : 0);
        bundle.putBoolean("isVideo", podcastEpisode.isVideo());
        bundle.putLong("created", podcastEpisode.getCreated() != null ? podcastEpisode.getCreated().getTime() : 0);
        bundle.putString("artistId", podcastEpisode.getArtistId());
        bundle.putString("description", podcastEpisode.getDescription());
        bundle.putString("type", Constants.MEDIA_TYPE_PODCAST);
        bundle.putString("uri", uri.toString());

        MediaItem item = new MediaItem.Builder()
                .setMediaId(podcastEpisode.getId())
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(podcastEpisode.getTitle())
                                .setReleaseYear(podcastEpisode.getYear() != null ? podcastEpisode.getYear() : 0)
                                .setAlbumTitle(podcastEpisode.getAlbum())
                                .setArtist(podcastEpisode.getArtist())
                                .setArtworkUri(artworkUri)
                                .setExtras(bundle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                )
                .setRequestMetadata(
                        new MediaItem.RequestMetadata.Builder()
                                .setMediaUri(uri)
                                .setExtras(bundle)
                                .build()
                )
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .setUri(uri)
                .build();

        return item;
    }

    private static Uri getUri(Child media) {
        return DownloadUtil.getDownloadTracker(App.getContext()).isDownloaded(media.getId())
                ? getDownloadUri(media.getId())
                : MusicUtil.getStreamUri(media.getId());
    }

    private static Uri getUri(PodcastEpisode podcastEpisode) {
        return DownloadUtil.getDownloadTracker(App.getContext()).isDownloaded(podcastEpisode.getStreamId())
                ? getDownloadUri(podcastEpisode.getStreamId())
                : MusicUtil.getStreamUri(podcastEpisode.getStreamId());
    }

    public static Uri getDownloadUri(String id) {
        Download download = new DownloadRepository().getDownload(id);
        return download != null && !download.getDownloadUri().isEmpty() ? Uri.parse(download.getDownloadUri()) : MusicUtil.getDownloadUri(id);
    }
}
