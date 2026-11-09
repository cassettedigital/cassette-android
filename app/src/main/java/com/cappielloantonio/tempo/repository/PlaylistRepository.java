package com.cappielloantonio.tempo.repository;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.database.dao.PlaylistDao;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.util.CassetteLocalId;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cassette cloud playlists (user_playlists via /api/sync/playlists).
 *
 * When a bearer token is present this repository talks to the cloud store —
 * the same one the web dashboard and Mac menu-bar card use. Subsonic hub
 * playlists are only read once for a best-effort import, then ignored.
 */
public class PlaylistRepository {
    private static final String TAG = "PlaylistRepository";
    private static final String HUB_IMPORT_DONE = "cassette_hub_playlist_import_done";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private final PlaylistDao playlistDao = AppDatabase.getInstance().playlistDao();
    private final DownloadDao downloadDao = AppDatabase.getInstance().downloadDao();

    public MutableLiveData<List<Playlist>> getPlaylists(boolean random, int size) {
        MutableLiveData<List<Playlist>> live = new MutableLiveData<>();
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty()) {
                live.postValue(new ArrayList<>());
                return;
            }
            try {
                importHubPlaylistsIfNeeded(bearer);
                List<CassetteSyncApi.CloudPlaylist> cloud =
                        CassetteSyncApi.listPlaylists(bearer, true);
                List<Playlist> out = new ArrayList<>();
                for (CassetteSyncApi.CloudPlaylist cp : cloud) {
                    Playlist ui = toUiPlaylist(cp);
                    out.add(ui);
                    playlistDao.insert(ui); // offline Home / pin cache
                }
                if (random) {
                    Collections.shuffle(out);
                    out = new ArrayList<>(out.subList(0, Math.min(out.size(), size)));
                }
                live.postValue(out);
            } catch (Exception e) {
                // cassette (BUG-948): offline or a failed fetch shows the playlists
                // kept from the last good one, not an empty list until restart.
                Log.w(TAG, "listPlaylists failed", e);
                live.postValue(new ArrayList<>(playlistDao.getAllSync()));
            }
        });
        return live;
    }

    public MutableLiveData<List<Child>> getPlaylistSongs(String id) {
        MutableLiveData<List<Child>> live = new MutableLiveData<>();
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty()) {
                live.postValue(new ArrayList<>());
                return;
            }
            try {
                CassetteSyncApi.CloudPlaylist cp = CassetteSyncApi.getPlaylist(bearer, id);
                List<Child> songs = new ArrayList<>();
                for (CassetteSyncApi.CloudPlaylistItem item : cp.items) {
                    Child resolved = resolveChild(item);
                    if (resolved != null) songs.add(resolved);
                }
                live.postValue(songs);
            } catch (Exception e) {
                Log.w(TAG, "getPlaylistSongs failed", e);
                live.postValue(new ArrayList<>());
            }
        });
        return live;
    }

    public void addSongToPlaylist(String playlistId, ArrayList<String> songsId) {
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty() || songsId == null || songsId.isEmpty()) return;
            try {
                JsonArray ops = new JsonArray();
                double base = System.currentTimeMillis();
                int i = 0;
                for (String subsonicId : songsId) {
                    Download dl = downloadDao.getOne(subsonicId);
                    String localId;
                    String mbid = null;
                    if (dl != null && dl.getCassetteLocalId() != null) {
                        localId = dl.getCassetteLocalId();
                        mbid = dl.getMbid();
                    } else if (dl != null) {
                        localId = CassetteLocalId.compute(
                                dl.getArtist() != null ? dl.getArtist() : "",
                                dl.getTitle() != null ? dl.getTitle() : "",
                                dl.getDuration() != null ? dl.getDuration() : 0);
                        mbid = dl.getMbid();
                    } else {
                        continue;
                    }
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "add");
                    op.addProperty("item_id", UUID.randomUUID().toString());
                    op.addProperty("cassette_local_id", localId);
                    if (mbid != null) op.addProperty("mbid", mbid);
                    op.addProperty("position", base + i);
                    op.addProperty("added_at", java.time.Instant.now().toString());
                    op.addProperty("added_by_device", "android");
                    ops.add(op);
                    i++;
                }
                if (ops.size() > 0) {
                    CassetteSyncApi.applyPlaylistOps(bearer, playlistId, ops);
                }
            } catch (Exception e) {
                Log.w(TAG, "addSongToPlaylist failed", e);
            }
        });
    }

    public void createPlaylist(String playlistId, String name, ArrayList<String> songsId) {
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty() || name == null || name.isEmpty()) return;
            try {
                String id = (playlistId != null && !playlistId.isEmpty())
                        ? playlistId
                        : UUID.randomUUID().toString();
                CassetteSyncApi.createPlaylist(bearer, id, name);
                if (songsId != null && !songsId.isEmpty()) {
                    addSongToPlaylistBlocking(bearer, id, songsId);
                }
            } catch (Exception e) {
                Log.w(TAG, "createPlaylist failed", e);
            }
        });
    }

    /** One row of the playlist editor: the song on this phone and the cloud item it is. */
    public static final class EditorRow {
        public final String itemId;
        public final Child child;

        EditorRow(String itemId, Child child) {
            this.itemId = itemId;
            this.child = child;
        }
    }

    /**
     * The editor's rows: the playlist's songs that are on this phone, each tied
     * to its cloud item, so a save can touch exactly the items the person
     * removed or moved. Songs not on this phone are never shown and never
     * touched (BUG-936).
     */
    public MutableLiveData<List<EditorRow>> getPlaylistEditorRows(String id) {
        MutableLiveData<List<EditorRow>> live = new MutableLiveData<>();
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            List<EditorRow> rows = new ArrayList<>();
            if (bearer == null || bearer.isEmpty()) {
                live.postValue(rows);
                return;
            }
            try {
                CassetteSyncApi.CloudPlaylist cp = CassetteSyncApi.getPlaylist(bearer, id);
                for (CassetteSyncApi.CloudPlaylistItem item : cp.items) {
                    Child resolved = resolveChild(item);
                    if (resolved != null) rows.add(new EditorRow(item.id, resolved));
                }
            } catch (Exception e) {
                Log.w(TAG, "getPlaylistEditorRows failed", e);
            }
            live.postValue(rows);
        });
        return live;
    }

    /**
     * Saves only what the person changed in the editor (BUG-936): a new name,
     * the items they removed, and new places for the items they moved. A moved
     * song takes one of the places the songs on this phone already held, so
     * songs that live only on the computer or another phone keep their place.
     */
    public void savePlaylistEdits(String playlistId, String newName,
                                  List<String> keptItemIdsInOrder, List<String> removedItemIds) {
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty() || playlistId == null) return;
            try {
                if (newName != null) CassetteSyncApi.updatePlaylist(bearer, playlistId, newName);
                if (keptItemIdsInOrder.isEmpty() && removedItemIds.isEmpty()) return;

                CassetteSyncApi.CloudPlaylist current = CassetteSyncApi.getPlaylist(bearer, playlistId);
                java.util.Map<String, Double> placeOf = new java.util.HashMap<>();
                for (CassetteSyncApi.CloudPlaylistItem item : current.items) {
                    placeOf.put(item.id, item.position);
                }

                JsonArray ops = new JsonArray();
                for (String itemId : removedItemIds) {
                    if (!placeOf.containsKey(itemId)) continue;
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "remove");
                    op.addProperty("item_id", itemId);
                    ops.add(op);
                }

                List<String> kept = new ArrayList<>();
                for (String itemId : keptItemIdsInOrder) {
                    if (placeOf.containsKey(itemId)) kept.add(itemId);
                }
                List<Double> places = new ArrayList<>();
                for (String itemId : kept) places.add(placeOf.get(itemId));
                Collections.sort(places);
                for (int i = 0; i < kept.size(); i++) {
                    String itemId = kept.get(i);
                    double place = places.get(i);
                    if (Double.compare(place, placeOf.get(itemId)) == 0) continue;
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "reorder");
                    op.addProperty("item_id", itemId);
                    op.addProperty("position", place);
                    ops.add(op);
                }

                if (ops.size() > 0) CassetteSyncApi.applyPlaylistOps(bearer, playlistId, ops);
            } catch (Exception e) {
                Log.w(TAG, "savePlaylistEdits failed", e);
            }
        });
    }

    /**
     * cassette (One Home): tell the cloud a playlist started playing, so every
     * device's Playlists row leads with it and Resume can name it. Best effort.
     */
    public static void notePlayed(String playlistId) {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null || bearer.isEmpty() || playlistId == null) return;
        EXEC.execute(() -> {
            try {
                CassetteSyncApi.playlistPlayed(bearer, playlistId);
            } catch (Exception e) {
                Log.w(TAG, "playlist played note failed", e);
            }
        });
    }

    public void deletePlaylist(String playlistId) {
        EXEC.execute(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer == null || bearer.isEmpty() || playlistId == null) return;
            try {
                CassetteSyncApi.deletePlaylist(bearer, playlistId);
            } catch (Exception e) {
                Log.w(TAG, "deletePlaylist failed", e);
            }
        });
    }

    /** Home shelf: all cloud playlists (pin table kept for optional user pins). */
    public LiveData<List<Playlist>> getPinnedPlaylists() {
        return playlistDao.getAll();
    }

    public void insert(Playlist playlist) {
        EXEC.execute(() -> playlistDao.insert(playlist));
    }

    public void delete(Playlist playlist) {
        EXEC.execute(() -> playlistDao.delete(playlist));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void addSongToPlaylistBlocking(String bearer, String playlistId, List<String> songsId)
            throws Exception {
        JsonArray ops = new JsonArray();
        double base = System.currentTimeMillis();
        int i = 0;
        for (String subsonicId : songsId) {
            Download dl = downloadDao.getOne(subsonicId);
            if (dl == null) continue;
            String localId = dl.getCassetteLocalId() != null
                    ? dl.getCassetteLocalId()
                    : CassetteLocalId.compute(
                            dl.getArtist() != null ? dl.getArtist() : "",
                            dl.getTitle() != null ? dl.getTitle() : "",
                            dl.getDuration() != null ? dl.getDuration() : 0);
            JsonObject op = new JsonObject();
            op.addProperty("op", "add");
            op.addProperty("item_id", UUID.randomUUID().toString());
            op.addProperty("cassette_local_id", localId);
            if (dl.getMbid() != null) op.addProperty("mbid", dl.getMbid());
            op.addProperty("position", base + i);
            op.addProperty("added_at", java.time.Instant.now().toString());
            op.addProperty("added_by_device", "android");
            ops.add(op);
            i++;
        }
        if (ops.size() > 0) CassetteSyncApi.applyPlaylistOps(bearer, playlistId, ops);
    }

    private Playlist toUiPlaylist(CassetteSyncApi.CloudPlaylist cp) {
        Playlist p = new Playlist(cp.id);
        p.setName(cp.name);
        p.setComment(cp.description);
        p.setSongCount(cp.items != null ? cp.items.size() : 0);
        // Store an absolute loadable URL (CustomGlideRequest treats http(s) as
        // remote art, not a Subsonic coverArt id). The user's pick wins; else the
        // server-derived preset so a coverless playlist shows its own look, not a note.
        p.setCoverArtId(normalizeCoverUrl(cp.coverImageUrl != null ? cp.coverImageUrl : cp.derivedCoverUrl));
        return p;
    }

    /** Match sync-shapes playlistCoverUrlForSync: absolute URL + preset PNG twin. */
    private static String normalizeCoverUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String out = url;
        if (out.startsWith("/")) {
            out = "https://cassette.digital" + out;
        }
        if (out.contains("/playlist-covers/preset-") && out.endsWith(".svg")) {
            out = out.substring(0, out.length() - 4) + ".png";
        }
        return out;
    }

    private Child resolveChild(CassetteSyncApi.CloudPlaylistItem item) {
        Download dl = null;
        if (item.mbid != null) dl = downloadDao.getByMbid(item.mbid);
        if (dl == null) dl = downloadDao.getByCassetteLocalId(item.cassetteLocalId);
        return dl; // Download extends Child
    }

    private void importHubPlaylistsIfNeeded(String bearer) {
        if (App.getInstance().getPreferences().getBoolean(HUB_IMPORT_DONE, false)) return;
        try {
            // Best-effort Subsonic list; founder has no hub lists.
            retrofit2.Response<com.cappielloantonio.tempo.subsonic.base.ApiResponse> response =
                    App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylists().execute();
            if (response.isSuccessful() && response.body() != null
                    && response.body().getSubsonicResponse().getPlaylists() != null
                    && response.body().getSubsonicResponse().getPlaylists().getPlaylists() != null) {
                List<Playlist> hub =
                        response.body().getSubsonicResponse().getPlaylists().getPlaylists();
                List<CassetteSyncApi.CloudPlaylist> existing =
                        CassetteSyncApi.listPlaylists(bearer, false);
                java.util.HashSet<String> names = new java.util.HashSet<>();
                for (CassetteSyncApi.CloudPlaylist cp : existing) names.add(cp.name);
                for (Playlist hubPl : hub) {
                    if (hubPl.getName() == null || names.contains(hubPl.getName())) continue;
                    String cloudId = UUID.randomUUID().toString();
                    CassetteSyncApi.createPlaylist(bearer, cloudId, hubPl.getName());
                    // Song import: pull hub playlist entries and map via downloads.
                    retrofit2.Response<com.cappielloantonio.tempo.subsonic.base.ApiResponse> songsResp =
                            App.getSubsonicClientInstance(false).getPlaylistClient()
                                    .getPlaylist(hubPl.getId()).execute();
                    if (songsResp.isSuccessful() && songsResp.body() != null
                            && songsResp.body().getSubsonicResponse().getPlaylist() != null
                            && songsResp.body().getSubsonicResponse().getPlaylist().getEntries() != null) {
                        ArrayList<String> ids = new ArrayList<>();
                        for (Child c : songsResp.body().getSubsonicResponse().getPlaylist().getEntries()) {
                            if (c.getId() != null) ids.add(c.getId());
                        }
                        if (!ids.isEmpty()) addSongToPlaylistBlocking(bearer, cloudId, ids);
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "hub playlist import skipped: " + e.getMessage());
        } finally {
            App.getInstance().getPreferences().edit().putBoolean(HUB_IMPORT_DONE, true).apply();
        }
    }
}
