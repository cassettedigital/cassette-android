package com.cappielloantonio.tempo.util;

import android.os.Handler;
import android.os.Looper;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Offline-first browse source. When Server Mode is off (the default), the
 * library shows only music that's actually on the phone — so browse reads from
 * the on-device Download store instead of the Subsonic hub, and works with no
 * network at all. This is the Android analogue of iOS's on-device-only library
 * filter: iOS predicates its catalog mirror by an owned-id set; Android has no
 * mirror, so it derives albums/artists directly from the downloaded tracks.
 *
 * Reads run on a worker thread and post back on the main thread.
 */
public final class CassetteOfflineLibrary {
    private CassetteOfflineLibrary() {
    }

    public interface AlbumsCallback {
        void onResult(List<AlbumID3> albums);
    }

    public interface ArtistsCallback {
        void onResult(List<ArtistID3> artists);
    }

    /** Downloaded tracks grouped into albums (by albumId), sorted by name. */
    public static void albums(AlbumsCallback callback) {
        new Thread(() -> {
            List<AlbumID3> result = buildAlbums();
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }).start();
    }

    /** Downloaded albums, newest added first (Home's Albums row, BUG-944). */
    public static void recentlyAddedAlbums(AlbumsCallback callback) {
        new Thread(() -> {
            List<AlbumID3> result = buildAlbums();
            result.sort((a, b) -> {
                if (a.getCreated() == null) return b.getCreated() == null ? 0 : 1;
                if (b.getCreated() == null) return -1;
                return b.getCreated().compareTo(a.getCreated());
            });
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }).start();
    }

    /** Owned albums ordered by when they were last played on this device. */
    public static void recentAlbums(int limit, AlbumsCallback callback) {
        new Thread(() -> {
            List<AlbumID3> result = recentlyPlayedAlbums(limit);
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }).start();
    }

    /** Downloaded tracks grouped into artists (by artistId), sorted by name. */
    public static void artists(ArtistsCallback callback) {
        new Thread(() -> {
            List<ArtistID3> result = buildArtists();
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }).start();
    }

    /**
     * cassette: the owned albums a person has actually played, most recent
     * first, derived from the local play log (chronology.album_id). Offline
     * first, the server's own "recent" album list is unreachable, and the Home
     * shelf was posting an empty list rather than answering the question from
     * data we already hold.
     *
     * Synchronous - call off the main thread.
     */
    public static List<AlbumID3> recentlyPlayedAlbums(int limit) {
        List<String> recentIds = AppDatabase.getInstance().chronologyDao()
                .getRecentlyPlayedAlbumIds(com.cappielloantonio.tempo.util.Preferences.getServerId(), limit);
        if (recentIds == null || recentIds.isEmpty()) return new ArrayList<>();

        java.util.Map<String, AlbumID3> owned = new java.util.HashMap<>();
        for (AlbumID3 album : buildAlbums()) owned.put(album.getId(), album);

        List<AlbumID3> result = new ArrayList<>();
        for (String albumId : recentIds) {
            AlbumID3 album = owned.get(albumId);
            // Skip plays of things no longer on the device: a shelf that opens
            // onto missing music is worse than a shorter shelf.
            if (album != null) result.add(album);
        }
        return result;
    }

    private static List<AlbumID3> buildAlbums() {
        return groupAlbums(AppDatabase.getInstance().downloadDao().getAllDownloadedSync());
    }

    /**
     * Downloaded songs grouped into albums, each named for the album's own artist (BUG-581).
     * The phone keeps no album-artist tag, so the album artist is the one Home, the car and
     * This phone already use: the artist most of its songs are by, else "Various Artists"
     * (CassetteAlbumGroupKey.effectiveAlbumArtist, the web's rule). It used to be whoever
     * sang the first downloaded song, so a soundtrack wore one of its performers.
     * A "Various Artists" record has no artist id: it is not someone to go to.
     */
    static List<AlbumID3> groupAlbums(List<Download> downloads) {
        LinkedHashMap<String, AlbumID3> byAlbum = new LinkedHashMap<>();
        LinkedHashMap<String, List<Download>> songsByAlbum = new LinkedHashMap<>();

        for (Download track : downloads) {
            String albumId = track.getAlbumId();
            if (albumId == null) continue;

            AlbumID3 album = byAlbum.get(albumId);
            if (album == null) {
                album = new AlbumID3();
                album.setId(albumId);
                album.setName(track.getAlbum());
                album.setCoverArtId(track.getCoverArtId());
                if (track.getYear() != null) album.setYear(track.getYear());
                album.setGenre(track.getGenre());
                album.setSongCount(0);
                byAlbum.put(albumId, album);
                songsByAlbum.put(albumId, new ArrayList<>());
            }
            songsByAlbum.get(albumId).add(track);
            album.setSongCount(album.getSongCount() + 1);
            // cassette: an album was added when its newest song was (BUG-944).
            if (track.getCreated() != null
                    && (album.getCreated() == null || track.getCreated().after(album.getCreated()))) {
                album.setCreated(track.getCreated());
            }
        }

        for (AlbumID3 album : byAlbum.values()) {
            List<Download> songs = songsByAlbum.get(album.getId());
            List<String> names = new ArrayList<>();
            for (Download song : songs) {
                if (song.getArtist() != null) names.add(CassetteAlbumGroupKey.stripFeatured(song.getArtist()));
            }
            String artist = CassetteAlbumGroupKey.effectiveAlbumArtist(names);
            if (artist.isEmpty()) {
                // no names at all: keep what the first song says, as before
                album.setArtist(songs.get(0).getArtist());
                album.setArtistId(songs.get(0).getArtistId());
                continue;
            }
            album.setArtist(artist);
            album.setArtistId(VARIOUS_ARTISTS.equalsIgnoreCase(artist) ? null : artistIdFor(artist, songs));
        }

        List<AlbumID3> result = new ArrayList<>(byAlbum.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        // cassette: filed the Library's way ("The Bends" with the B's, "#" first)
        result.sort((a, b) -> {
            String ka = CassetteLetters.sortKey(a.getName());
            String kb = CassetteLetters.sortKey(b.getName());
            boolean ha = "#".equals(CassetteLetters.letterForKey(ka));
            boolean hb = "#".equals(CassetteLetters.letterForKey(kb));
            if (ha != hb) return ha ? -1 : 1;
            return collator.compare(ka, kb);
        });
        return result;
    }

    private static final String VARIOUS_ARTISTS = "Various Artists";

    /** The artist id the album's own artist wears most often on its songs. */
    private static String artistIdFor(String albumArtist, List<Download> songs) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Download song : songs) {
            if (song.getArtist() == null || song.getArtistId() == null) continue;
            if (!albumArtist.equals(CassetteAlbumGroupKey.stripFeatured(song.getArtist()))) continue;
            Integer n = counts.get(song.getArtistId());
            counts.put(song.getArtistId(), n == null ? 1 : n + 1);
        }
        String best = null;
        int most = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    private static List<ArtistID3> buildArtists() {
        return groupArtists(buildAlbums());
    }

    /**
     * The Artists list: the artists whose records are on the phone, never everyone who sings
     * a song on a compilation (BUG-581, the web's rule). A performer who is only on a
     * soundtrack is still reachable from that record and its songs.
     */
    static List<ArtistID3> groupArtists(List<AlbumID3> albums) {
        LinkedHashMap<String, ArtistID3> byArtist = new LinkedHashMap<>();
        LinkedHashMap<String, HashSet<String>> albumsPerArtist = new LinkedHashMap<>();

        for (AlbumID3 album : albums) {
            String artistId = album.getArtistId();
            if (artistId == null) continue;

            ArtistID3 artist = byArtist.get(artistId);
            if (artist == null) {
                artist = new ArtistID3();
                artist.setId(artistId);
                artist.setName(album.getArtist());
                // cassette: use the ARTIST cover id ("ar-<id>"), not the track's
                // album cover — CustomGlideRequest resolves this against the
                // synced attributed artist photos (Wikidata/Commons via
                // CassetteArtistArt, keyed by artist id), matching the web + iOS.
                // A track's coverArtId would show album art instead.
                artist.setCoverArtId("ar-" + artistId);
                artist.setAlbumCount(0);
                byArtist.put(artistId, artist);
                albumsPerArtist.put(artistId, new HashSet<>());
            }
            albumsPerArtist.get(artistId).add(album.getId());
        }

        for (String artistId : byArtist.keySet()) {
            byArtist.get(artistId).setAlbumCount(albumsPerArtist.get(artistId).size());
        }

        List<ArtistID3> result = new ArrayList<>(byArtist.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort((a, b) -> collator.compare(nullToEmpty(a.getName()), nullToEmpty(b.getName())));
        return result;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
