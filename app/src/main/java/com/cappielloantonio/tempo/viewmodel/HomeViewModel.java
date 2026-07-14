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
    private final MutableLiveData<List<Integer>> years = new MutableLiveData<>(null);
    private final MutableLiveData<List<AlbumID3>> recentlyAddedAlbumSample = new MutableLiveData<>(null);

    private final MutableLiveData<List<Chronology>> thisGridTopSong = new MutableLiveData<>(null);
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
                        com.cappielloantonio.tempo.model.Download owned =
                                db.downloadDao().getByCassetteLocalId(rp.getCassetteLocalId());
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
        remoteResumeExecutor.execute(() -> {
            List<Child> result = new ArrayList<>();
            try {
                com.cappielloantonio.tempo.database.AppDatabase db =
                        com.cappielloantonio.tempo.database.AppDatabase.getInstance();
                String server = Preferences.getServerId();

                java.util.Map<String, Child> childById = new HashMap<>();
                java.util.Map<String, Long> tsById = new HashMap<>();

                List<Chronology> local = db.chronologyDao().getRecentSync(server, 40);
                if (local != null) {
                    for (Chronology c : local) mergeRecent(childById, tsById, c.getId(), c, c.getTimestamp());
                }

                List<com.cappielloantonio.tempo.model.RemotePlay> remote = db.remotePlayDao().getRecent(40);
                if (remote != null) {
                    for (com.cappielloantonio.tempo.model.RemotePlay rp : remote) {
                        com.cappielloantonio.tempo.model.Download owned =
                                db.downloadDao().getByCassetteLocalId(rp.getCassetteLocalId());
                        if (owned != null) mergeRecent(childById, tsById, owned.getId(), owned, rp.getPlayedAt());
                    }
                }

                List<java.util.Map.Entry<String, Long>> entries = new ArrayList<>(tsById.entrySet());
                entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                for (java.util.Map.Entry<String, Long> e : entries) {
                    Child c = childById.get(e.getKey());
                    if (c != null) result.add(c);
                    if (result.size() >= 20) break;
                }
            } catch (Exception ignored) {
                // best-effort — the shelf just stays hidden if it can't build
            }
            recentlyPlayedTracks.postValue(result);
        });
        return recentlyPlayedTracks;
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

    public LiveData<List<Chronology>> getChronologySample(LifecycleOwner owner) {
        Calendar cal = Calendar.getInstance();
        String server = Preferences.getServerId();

        int currentWeek = cal.get(Calendar.WEEK_OF_YEAR);
        long start = cal.getTimeInMillis();

        cal.set(Calendar.WEEK_OF_YEAR, currentWeek - 1);
        long end = cal.getTimeInMillis();

        chronologyRepository.getChronology(server, start, end).observe(owner, thisGridTopSong::postValue);
        return thisGridTopSong;
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
                com.cappielloantonio.tempo.util.CassetteOfflineLibrary.albums(
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
                // cassette: offline-first Home is Resume + one "Albums" shelf.
                // Hide the recently-played shelf (empty) so it isn't a duplicate
                // of the downloaded-albums shelf below it.
                recentlyPlayedAlbumSample.postValue(new java.util.ArrayList<>());
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

        playlistRepository.getPlaylists(false, -1).observe(owner, remotes -> {
            playlistRepository.getPinnedPlaylists().observe(owner, locals -> {
                if (remotes != null && locals != null) {
                    List<Playlist> toReturn = remotes.stream()
                            .filter(remote -> locals.stream().anyMatch(local -> local.getId().equals(remote.getId())))
                            .collect(Collectors.toList());

                    pinnedPlaylists.setValue(toReturn);
                }
            });
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

    public void changeChronologyPeriod(LifecycleOwner owner, int period) {
        Calendar cal = Calendar.getInstance();
        String server = Preferences.getServerId();
        int currentWeek = cal.get(Calendar.WEEK_OF_YEAR);

        long start = 0;
        long end = 0;

        if (period == 0) {
            start = cal.getTimeInMillis();
            cal.set(Calendar.WEEK_OF_YEAR, currentWeek - 1);
            end = cal.getTimeInMillis();
        } else if (period == 1) {
            start = cal.getTimeInMillis();
            cal.set(Calendar.WEEK_OF_YEAR, currentWeek - 4);
            end = cal.getTimeInMillis();
        } else if (period == 2) {
            start = cal.getTimeInMillis();
            cal.set(Calendar.WEEK_OF_YEAR, currentWeek - 52);
            end = cal.getTimeInMillis();
        }

        chronologyRepository.getChronology(server, start, end).observe(owner, thisGridTopSong::postValue);
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
            // cassette: keep the recently-played shelf hidden offline (Resume +
            // the Albums shelf cover it) — consistent with the getter.
            recentlyPlayedAlbumSample.postValue(new java.util.ArrayList<>());
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
