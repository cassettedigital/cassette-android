package com.cappielloantonio.tempo.repository;

import android.util.Log;

import androidx.annotation.NonNull;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.database.dao.FavoriteDao;
import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.util.CassetteLocalId;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FavoriteRepository {
    private static final String TAG = "FavoriteRepository";
    private static final ExecutorService CLOUD_EXEC = Executors.newSingleThreadExecutor();

    private final FavoriteDao favoriteDao = AppDatabase.getInstance().favoriteDao();
    private final DownloadDao downloadDao = AppDatabase.getInstance().downloadDao();

    public void star(String id, String albumId, String artistId, StarCallback starCallback) {
        // cassette: also write the track into cloud Saved (user_favorites).
        if (id != null) mirrorCloudFavorite(id, true);
        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .star(id, albumId, artistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()) {
                            starCallback.onSuccess();
                        } else {
                            starCallback.onError();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        starCallback.onError();
                    }
                });
    }

    public void unstar(String id, String albumId, String artistId, StarCallback starCallback) {
        if (id != null) mirrorCloudFavorite(id, false);
        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .unstar(id, albumId, artistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()) {
                            starCallback.onSuccess();
                        } else {
                            starCallback.onError();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        starCallback.onError();
                    }
                });
    }

    /** Best-effort cloud Saved write for a track Subsonic id. */
    private void mirrorCloudFavorite(String subsonicTrackId, boolean save) {
        CLOUD_EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty()) return;
            try {
                Download dl = downloadDao.getOne(subsonicTrackId);
                String localId;
                String mbid = null;
                String title = null;
                String artist = null;
                String album = null;
                if (dl != null) {
                    localId = dl.getCassetteLocalId() != null
                            ? dl.getCassetteLocalId()
                            : CassetteLocalId.compute(
                                    dl.getArtist() != null ? dl.getArtist() : "",
                                    dl.getTitle() != null ? dl.getTitle() : "",
                                    dl.getDuration() != null ? dl.getDuration() : 0);
                    mbid = dl.getMbid();
                    title = dl.getTitle();
                    artist = dl.getArtist();
                    album = dl.getAlbum();
                } else {
                    return;
                }
                if (save) {
                    CassetteSyncApi.upsertFavorite(bearer, localId, mbid, title, artist, album);
                } else {
                    CassetteSyncApi.removeFavorite(bearer, localId);
                }
            } catch (Exception e) {
                Log.w(TAG, "cloud Saved mirror failed", e);
            }
        });
    }

    public List<Favorite> getFavorites() {
        List<Favorite> favorites = new ArrayList<>();

        GetAllThreadSafe getAllThreadSafe = new GetAllThreadSafe(favoriteDao);
        Thread thread = new Thread(getAllThreadSafe);
        thread.start();

        try {
            thread.join();
            favorites = getAllThreadSafe.getFavorites();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return favorites;
    }

    private static class GetAllThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private List<Favorite> favorites = new ArrayList<>();

        public GetAllThreadSafe(FavoriteDao favoriteDao) {
            this.favoriteDao = favoriteDao;
        }

        @Override
        public void run() {
            favorites = favoriteDao.getAll();
        }

        public List<Favorite> getFavorites() {
            return favorites;
        }
    }

    public void starLater(String id, String albumId, String artistId, boolean toStar) {
        InsertThreadSafe insert = new InsertThreadSafe(favoriteDao, new Favorite(System.currentTimeMillis(), id, albumId, artistId, toStar));
        Thread thread = new Thread(insert);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private final Favorite favorite;

        public InsertThreadSafe(FavoriteDao favoriteDao, Favorite favorite) {
            this.favoriteDao = favoriteDao;
            this.favorite = favorite;
        }

        @Override
        public void run() {
            favoriteDao.insert(favorite);
        }
    }

    public void delete(Favorite favorite) {
        DeleteThreadSafe delete = new DeleteThreadSafe(favoriteDao, favorite);
        Thread thread = new Thread(delete);
        thread.start();
    }

    private static class DeleteThreadSafe implements Runnable {
        private final FavoriteDao favoriteDao;
        private final Favorite favorite;

        public DeleteThreadSafe(FavoriteDao favoriteDao, Favorite favorite) {
            this.favoriteDao = favoriteDao;
            this.favorite = favorite;
        }

        @Override
        public void run() {
            favoriteDao.delete(favorite);
        }
    }
}
