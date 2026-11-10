package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.ChronologyRepository;
import com.cappielloantonio.tempo.repository.FavoriteRepository;
import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.repository.SharingRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.Share;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class HomeViewModel extends AndroidViewModel {
    private static final String TAG = "HomeViewModel";

    private final SongRepository songRepository;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final ChronologyRepository chronologyRepository;
    private final FavoriteRepository favoriteRepository;
    private final PlaylistRepository playlistRepository;
    private final SharingRepository sharingRepository;

    private final MutableLiveData<List<Child>> dicoverSongSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> newReleasedAlbum = new MutableLiveData<>(null);
    private final MutableLiveData<List<Child>> starredTracksSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<ArtistID3>> starredArtistsSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<ArtistID3>> bestOfArtists = new MutableLiveData<>(null);
    private final MutableLiveData<List<Child>> starredTracks = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> starredAlbums = new MutableLiveData<>(null);
    private final MutableLiveData<List<ArtistID3>> starredArtists = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> mostPlayedAlbumSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> recentlyPlayedAlbumSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<com.cappielloantonio.tempo.subsonic.models.ArtistID3>> recentlyPlayedArtistsSample = new MutableLiveData<>(null);
    private final MutableLiveData<List<Integer>> years = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> recentlyAddedAlbumSample = new MutableLiveData<>(null);

    private final MutableLiveData<List<Child>> mediaInstantMix = new MutableLiveData<>(null);
    private final MutableLiveData<List<Child>> artistInstantMix = new MutableLiveData<>(null);
    private final MutableLiveData<List<Child>> artistBestOf = new MutableLiveData<>(null);
    private final MutableLiveData<List<Playlist>> pinnedPlaylists = new MutableLiveData<>(null);
    private final MutableLiveData<List<Share>> shares = new MutableLiveData<>(null);

    // cassette play spine (Phase B): most-recent resolvable remote play → Resume card.
    private final MutableLiveData<RemoteResume> remoteResume = new MutableLiveData<>(null);
    private final java.util.concurrent.ExecutorService remoteResumeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private List<HomeSector> sectors;

    public HomeViewModel(@NonNull Application application) {
        super(application);

        setHomeSectorList();

        songRepository = new SongRepository();
        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        chronologyRepository = new ChronologyRepository();
        favoriteRepository = new FavoriteRepository();
        playlistRepository = new PlaylistRepository();
        sharingRepository = new SharingRepository();

        setOfflineFavorite();
    }

    public LiveData<List<Child>> getDiscoverSongSample(LifecycleOwner owner) {
        if (dicoverSongSample.getValue() == null) {
            songRepository.getRandomSample(10, null, null).observe(owner, dicoverSongSample::postValue);
        }

        return dicoverSongSample;
    }

    public LiveData<List<Child>> getRandomShuffleSample() {
        return songRepository.getRandomSample(100, null, null);
    }

    /** cassette: the single most recent play — feeds the home Resume card. */
    public LiveData<List<Chronology>> getLastPlayedSong() {
        return chronologyRepository.getLastPlayed(Preferences.getServerId(), 1);
    }

    /**
     * cassette play spine (Phase B): the most recent play from ANOTHER surface
     * (iOS / web / desktop), pulled into cassette_remote_play, resolved to a
     * locally-owned track so it can feed the cross-device Resume card. The
     * endpoint carries ids only, so we resolve via the download table and skip
     * remote plays of tracks this device doesn't own. Null when none resolve.
     */
    public LiveData<RemoteResume> getRemoteResume() {
        remoteResumeExecutor.execute(() -> {
            RemoteResume result = null;
            try {
                com.cappielloantonio.tempo.database.AppDatabase db =
                        com.cappielloantonio.tempo.database.AppDatabase.getInstance();
                List<com.cappielloantonio.tempo.model.RemotePlay> recent = db.remotePlayDao().getRecent(25);
                if (recent != null) {
                    for (com.cappielloantonio.tempo.model.RemotePlay rp : recent) {
                        // mbid first (the stable cross-device key), fingerprint fallback.
                        com.cappielloantonio.tempo.model.Download owned =
                                rp.getMbid() != null ? db.downloadDao().getByMbid(rp.getMbid()) : null;
                        if (owned == null)
                            owned = db.downloadDao().getByCassetteLocalId(rp.getCassetteLocalId());
                        if (owned != null) {
                            result = new RemoteResume(owned, rp.getPlayedAt());
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // best-effort — the local Resume card still works without it
            }
            remoteResume.postValue(result);
        });
        return remoteResume;
    }

    /** A remote play resolved to a locally-owned, displayable track. */
    public static class RemoteResume {
        public final Child track;
        public final long playedAt;

        public RemoteResume(Child track, long playedAt) {
            this.track = track;
            this.playedAt = playedAt;
        }
    }

    private final MutableLiveData<List<Child>> recentlyPlayedTracks = new MutableLiveData<>(null);

    /**
     * cassette play spine (Phase B): "recently played" tracks unioned across THIS
     * device (chronology) and other surfaces (cassette_remote_play, resolved to
     * owned tracks). Deduped by Subsonic track id keeping the newer play, sorted
     * newest-first, capped. Computed off the main thread. Tracks played elsewhere
     * that this device doesn't own can't be shown (endpoint carries ids only).
     */
    public LiveData<List<Child>> getRecentlyPlayedTracks() {
        remoteResumeExecutor.execute(() ->
                recentlyPlayedTracks.postValue(recentPlayedUnion(20)));
        return recentlyPlayedTracks;
    }

    /**
     * cassette: the real "recently played" spine, newest-first. Unions THIS
     * device's chronology with cloud plays from other surfaces (remote_play,
     * resolved to owned tracks) and dedupes by track id keeping the newer play.
     * This is the same source the Resume card reads, which is why Resume knows
     * the song you are on right now - and why the album shelf that ignored it
     * looked empty. Runs on a background thread; call from an executor.
     */
    private List<Child> recentPlayedUnion(int cap) {
        List<Child> result = new ArrayList<>();
        try {
            com.cappielloantonio.tempo.database.AppDatabase db =
                    com.cappielloantonio.tempo.database.AppDatabase.getInstance();
            String server = Preferences.getServerId();

            java.util.Map<String, Child> childById = new HashMap<>();
            java.util.Map<String, Long> tsById = new HashMap<>();

            // Pull deeper than the cap: album de-dup downstream needs many
            // plays to surface enough distinct albums.
            int scan = Math.max(cap, 200);

            List<Chronology> local = db.chronologyDao().getRecentSync(server, scan);
            if (local != null) {
                for (Chronology c : local) mergeRecent(childById, tsById, c.getId(), c, c.getTimestamp());
            }

            List<com.cappielloantonio.tempo.model.RemotePlay> remote = db.remotePlayDao().getRecent(scan);
            if (remote != null) {
                for (com.cappielloantonio.tempo.model.RemotePlay rp : remote) {
                    // mbid first (the stable cross-device key), fingerprint fallback.
                    com.cappielloantonio.tempo.model.Download owned =
                            rp.getMbid() != null ? db.downloadDao().getByMbid(rp.getMbid()) : null;
                    if (owned == null)
                        owned = db.downloadDao().getByCassetteLocalId(rp.getCassetteLocalId());
                    if (owned != null) mergeRecent(childById, tsById, owned.getId(), owned, rp.getPlayedAt());
                }
            }

            List<java.util.Map.Entry<String, Long>> entries = new ArrayList<>(tsById.entrySet());
            entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (java.util.Map.Entry<String, Long> e : entries) {
                Child c = childById.get(e.getKey());
                if (c != null) result.add(c);
                if (result.size() >= cap) break;
            }
        } catch (Exception ignored) {
            // best-effort — the shelf just stays hidden if it can't build
        }
        return result;
    }

    /**
     * cassette: recent ALBUMS derived from the play spine, not from the
     * downloaded-library scan. Walks the unioned recent plays newest-first and
     * keeps the first appearance of each album. This is "Recent" the way it is
     * meant to work: your actual recent listening, cloud plays included.
     */
    private List<AlbumID3> recentPlayedAlbums(int cap) {
        List<Child> plays = recentPlayedUnion(1000);
        java.util.LinkedHashMap<String, AlbumID3> byAlbum = new java.util.LinkedHashMap<>();
        for (Child c : plays) {
            String albumId = c.getAlbumId();
            if (albumId == null || byAlbum.containsKey(albumId)) continue;
            AlbumID3 album = new AlbumID3();
            album.setId(albumId);
            album.setName(c.getAlbum());
            album.setArtist(c.getArtist());
            album.setArtistId(c.getArtistId());
            album.setCoverArtId(c.getCoverArtId());
            if (c.getYear() != null) album.setYear(c.getYear());
            album.setGenre(c.getGenre());
            byAlbum.put(albumId, album);
            if (byAlbum.size() >= cap) break;
        }
        return new ArrayList<>(byAlbum.values());
    }

    /**
     * cassette: recent ARTISTS from the play spine, newest-first, distinct by
     * artist id. iOS's Artists shelf (the slot below Albums). The artist image
     * resolves from the artist id via CustomGlideRequest, so coverArtId is set
     * to the artist id.
     */
    private List<com.cappielloantonio.tempo.subsonic.models.ArtistID3> recentPlayedArtists(int cap) {
        List<Child> plays = recentPlayedUnion(1000);
        java.util.LinkedHashMap<String, com.cappielloantonio.tempo.subsonic.models.ArtistID3> byArtist =
                new java.util.LinkedHashMap<>();
        for (Child c : plays) {
            String artistId = c.getArtistId();
            if (artistId == null || byArtist.containsKey(artistId)) continue;
            com.cappielloantonio.tempo.subsonic.models.ArtistID3 artist =
                    new com.cappielloantonio.tempo.subsonic.models.ArtistID3();
            artist.setId(artistId);
            artist.setName(c.getArtist());
            artist.setCoverArtId(artistId);
            byArtist.put(artistId, artist);
            if (byArtist.size() >= cap) break;
        }
        return new ArrayList<>(byArtist.values());
    }

    public LiveData<List<com.cappielloantonio.tempo.subsonic.models.ArtistID3>> getRecentlyPlayedArtists() {
        remoteResumeExecutor.execute(() ->
                recentlyPlayedArtistsSample.postValue(recentPlayedArtists(20)));
        return recentlyPlayedArtistsSample;
    }

    private static void mergeRecent(java.util.Map<String, Child> childById,
                                    java.util.Map<String, Long> tsById,
                                    String id, Child child, long ts) {
        if (id == null) return;
        Long existing = tsById.get(id);
        if (existing == null || ts > existing) {
            tsById.put(id, ts);
            childById.put(id, child);
        }
    }

    public LiveData<List<AlbumID3>> getRecentlyReleasedAlbums(LifecycleOwner owner) {
        if (newReleasedAlbum.getValue() == null) {
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);

            albumRepository.getAlbums("byYear", 500, currentYear, currentYear).observe(owner, albums -> {
                if (albums != null) {
                    albums.sort(Comparator.comparing(AlbumID3::getCreated).reversed());
                    newReleasedAlbum.postValue(albums.subList(0, Math.min(20, albums.size())));
                }
            });
        }

        return newReleasedAlbum;
    }

    public LiveData<List<Child>> getStarredTracksSample(LifecycleOwner owner) {
        if (starredTracksSample.getValue() == null) {
            songRepository.getStarredSongs(true, 10).observe(owner, starredTracksSample::postValue);
        }

        return starredTracksSample;
    }

    public LiveData<List<ArtistID3>> getStarredArtistsSample(LifecycleOwner owner) {
        if (starredArtistsSample.getValue() == null) {
            artistRepository.getStarredArtists(true, 10).observe(owner, starredArtistsSample::postValue);
        }

        return starredArtistsSample;
    }

    public LiveData<List<ArtistID3>> getBestOfArtists(LifecycleOwner owner) {
        if (bestOfArtists.getValue() == null) {
            artistRepository.getStarredArtists(true, 20).observe(owner, bestOfArtists::postValue);
        }

        return bestOfArtists;
    }

    public LiveData<List<Child>> getStarredTracks(LifecycleOwner owner) {
        if (starredTracks.getValue() == null) {
            songRepository.getStarredSongs(true, 20).observe(owner, starredTracks::postValue);
        }

        return starredTracks;
    }

    public LiveData<List<AlbumID3>> getStarredAlbums(LifecycleOwner owner) {
        if (starredAlbums.getValue() == null) {
            albumRepository.getStarredAlbums(true, 20).observe(owner, starredAlbums::postValue);
        }

        return starredAlbums;
    }

    public LiveData<List<ArtistID3>> getStarredArtists(LifecycleOwner owner) {
        if (starredArtists.getValue() == null) {
            artistRepository.getStarredArtists(true, 20).observe(owner, starredArtists::postValue);
        }

        return starredArtists;
    }

    public LiveData<List<Integer>> getYearList(LifecycleOwner owner) {
        if (years.getValue() == null) {
            albumRepository.getDecades().observe(owner, years::postValue);
        }

        return years;
    }

    public LiveData<List<AlbumID3>> getMostPlayedAlbums(LifecycleOwner owner) {
        if (mostPlayedAlbumSample.getValue() == null) {
            albumRepository.getAlbums("frequent", 20, null, null).observe(owner, mostPlayedAlbumSample::postValue);
        }

        return mostPlayedAlbumSample;
    }

    public LiveData<List<AlbumID3>> getMostRecentlyAddedAlbums(LifecycleOwner owner) {
        if (recentlyAddedAlbumSample.getValue() == null) {
            // cassette: offline-first shows only synced music — album shelves
            // come from the on-device Download store, not the hub.
            if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
                com.cappielloantonio.tempo.util.CassetteOfflineLibrary.recentlyAddedAlbums(
                        albums -> recentlyAddedAlbumSample.postValue(cassetteCap(albums)));
            } else {
                albumRepository.getAlbums("newest", 20, null, null).observe(owner, recentlyAddedAlbumSample::postValue);
            }
        }

        return recentlyAddedAlbumSample;
    }

    public LiveData<List<AlbumID3>> getRecentlyPlayedAlbumList(LifecycleOwner owner) {
        if (recentlyPlayedAlbumSample.getValue() == null) {
            if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
                // cassette: Recent from the PLAY SPINE (chronology + cloud plays),
                // not the downloaded-library scan. The old path read local-only
                // recent album ids and then filtered to downloads, so it showed a
                // single album while Resume - reading the same spine - knew the
                // exact current song. Same source now.
                remoteResumeExecutor.execute(() ->
                        recentlyPlayedAlbumSample.postValue(recentPlayedAlbums(20)));
            } else {
                albumRepository.getAlbums("recent", 20, null, null).observe(owner, recentlyPlayedAlbumSample::postValue);
            }
        }

        return recentlyPlayedAlbumSample;
    }

    public LiveData<List<Child>> getMediaInstantMix(LifecycleOwner owner, Child media) {
        mediaInstantMix.setValue(Collections.emptyList());

        songRepository.getInstantMix(media.getId(), 20).observe(owner, mediaInstantMix::postValue);

        return mediaInstantMix;
    }

    public LiveData<List<Child>> getArtistInstantMix(LifecycleOwner owner, ArtistID3 artist) {
        artistInstantMix.setValue(Collections.emptyList());

        artistRepository.getTopSongs(artist.getName(), 10).observe(owner, artistInstantMix::postValue);

        return artistInstantMix;
    }

    public LiveData<List<Child>> getArtistBestOf(LifecycleOwner owner, ArtistID3 artist) {
        artistBestOf.setValue(Collections.emptyList());

        artistRepository.getTopSongs(artist.getName(), 10).observe(owner, artistBestOf::postValue);

        return artistBestOf;
    }

    public LiveData<List<Playlist>> getPinnedPlaylists(LifecycleOwner owner) {
        pinnedPlaylists.setValue(Collections.emptyList());

        // cassette: Home Playlists shelf shows the cloud playlist list (same
        // store as web). Pin intersection with the local Room table is no
        // longer required for a playlist to appear.
        playlistRepository.getPlaylists(false, -1).observe(owner, remotes -> {
            if (remotes != null) {
                pinnedPlaylists.setValue(remotes);
            }
        });

        return pinnedPlaylists;
    }

    public LiveData<List<Share>> getShares(LifecycleOwner owner) {
        if (shares.getValue() == null) {
            sharingRepository.getShares().observe(owner, shares::postValue);
        }

        return shares;
    }

    public LiveData<List<Child>> getAllStarredTracks() {
        return songRepository.getStarredSongs(false, -1);
    }

    public void refreshDiscoverySongSample(LifecycleOwner owner) {
        songRepository.getRandomSample(10, null, null).observe(owner, dicoverSongSample::postValue);
    }

    public void refreshSimilarSongSample(LifecycleOwner owner) {
        songRepository.getStarredSongs(true, 10).observe(owner, starredTracksSample::postValue);
    }

    public void refreshRadioArtistSample(LifecycleOwner owner) {
        artistRepository.getStarredArtists(true, 10).observe(owner, starredArtistsSample::postValue);
    }

    public void refreshBestOfArtist(LifecycleOwner owner) {
        artistRepository.getStarredArtists(true, 20).observe(owner, bestOfArtists::postValue);
    }

    public void refreshStarredTracks(LifecycleOwner owner) {
        songRepository.getStarredSongs(true, 20).observe(owner, starredTracks::postValue);
    }

    public void refreshStarredAlbums(LifecycleOwner owner) {
        albumRepository.getStarredAlbums(true, 20).observe(owner, starredAlbums::postValue);
    }

    public void refreshStarredArtists(LifecycleOwner owner) {
        artistRepository.getStarredArtists(true, 20).observe(owner, starredArtists::postValue);
    }

    public void refreshMostPlayedAlbums(LifecycleOwner owner) {
        albumRepository.getAlbums("frequent", 20, null, null).observe(owner, mostPlayedAlbumSample::postValue);
    }

    public void refreshMostRecentlyAddedAlbums(LifecycleOwner owner) {
        // cassette: offline-first shows only synced music — the album shelves
        // come from the on-device Download store, not the hub.
        if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
            com.cappielloantonio.tempo.util.CassetteOfflineLibrary.albums(
                    albums -> recentlyAddedAlbumSample.postValue(cassetteCap(albums)));
            return;
        }
        albumRepository.getAlbums("newest", 20, null, null).observe(owner, recentlyAddedAlbumSample::postValue);
    }

    public void refreshRecentlyPlayedAlbumList(LifecycleOwner owner) {
        if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
            // cassette: re-derive from the play spine (chronology + cloud plays),
            // same as the getter.
            remoteResumeExecutor.execute(() ->
                    recentlyPlayedAlbumSample.postValue(recentPlayedAlbums(20)));
            return;
        }
        albumRepository.getAlbums("recent", 20, null, null).observe(owner, recentlyPlayedAlbumSample::postValue);
    }

    private static java.util.List<com.cappielloantonio.tempo.subsonic.models.AlbumID3> cassetteCap(
            java.util.List<com.cappielloantonio.tempo.subsonic.models.AlbumID3> albums) {
        return albums.subList(0, Math.min(20, albums.size()));
    }

    public void refreshShares(LifecycleOwner owner) {
        sharingRepository.getShares().observe(owner, this.shares::postValue);
    }

    private void setHomeSectorList() {
        if (Preferences.getHomeSectorList() != null && !Preferences.getHomeSectorList().equals("null")) {
            sectors = new Gson().fromJson(
                    Preferences.getHomeSectorList(),
                    new TypeToken<List<HomeSector>>() {
                    }.getType()
            );
        }
    }

    public List<HomeSector> getHomeSectorList() {
        return sectors;
    }

    public boolean checkHomeSectorVisibility(String sectorId) {
        return sectors != null && sectors.stream().filter(sector -> sector.getId().equals(sectorId))
                .findAny()
                .orElse(null) == null;
    }

    public void setOfflineFavorite() {
        ArrayList<Favorite> favorites = getFavorites();
        ArrayList<Favorite> favoritesToSave = getFavoritesToSave(favorites);
        ArrayList<Favorite> favoritesToDelete = getFavoritesToDelete(favorites, favoritesToSave);

        manageFavoriteToSave(favoritesToSave);
        manageFavoriteToDelete(favoritesToDelete);
    }

    private ArrayList<Favorite> getFavorites() {
        return new ArrayList<>(favoriteRepository.getFavorites());
    }

    private ArrayList<Favorite> getFavoritesToSave(ArrayList<Favorite> favorites) {
        HashMap<String, Favorite> filteredMap = new HashMap<>();

        for (Favorite favorite : favorites) {
            String key = favorite.toString();

            if (!filteredMap.containsKey(key) || favorite.getTimestamp() > filteredMap.get(key).getTimestamp()) {
                filteredMap.put(key, favorite);
            }
        }

        return new ArrayList<>(filteredMap.values());
    }

    private ArrayList<Favorite> getFavoritesToDelete(ArrayList<Favorite> favorites, ArrayList<Favorite> favoritesToSave) {
        ArrayList<Favorite> favoritesToDelete = new ArrayList<>();

        for (Favorite favorite : favorites) {
            if (!favoritesToSave.contains(favorite)) {
                favoritesToDelete.add(favorite);
            }
        }

        return favoritesToDelete;
    }

    private void manageFavoriteToSave(ArrayList<Favorite> favoritesToSave) {
        for (Favorite favorite : favoritesToSave) {
            if (favorite.getToStar()) {
                favoriteToStar(favorite);
            } else {
                favoriteToUnstar(favorite);
            }
        }
    }

    private void manageFavoriteToDelete(ArrayList<Favorite> favoritesToDelete) {
        for (Favorite favorite : favoritesToDelete) {
            favoriteRepository.delete(favorite);
        }
    }

    private void favoriteToStar(Favorite favorite) {
        if (favorite.getSongId() != null) {
            favoriteRepository.star(favorite.getSongId(), null, null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getAlbumId() != null) {
            favoriteRepository.star(null, favorite.getAlbumId(), null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getArtistId() != null) {
            favoriteRepository.star(null, null, favorite.getArtistId(), new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        }
    }

    private void favoriteToUnstar(Favorite favorite) {
        if (favorite.getSongId() != null) {
            favoriteRepository.unstar(favorite.getSongId(), null, null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getAlbumId() != null) {
            favoriteRepository.unstar(null, favorite.getAlbumId(), null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getArtistId() != null) {
            favoriteRepository.unstar(null, null, favorite.getArtistId(), new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        }
    }
}
