package com.cappielloantonio.tempo.util;

import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.BuildConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloud sync rails (the Android twin of iOS CassetteSyncAPI). The phone is a
 * client of the account's sync control plane: it registers itself in the
 * device registry, polls per-album add/remove intents, reports the set of
 * tracks it actually holds, and reads account-level settings (download
 * quality). All requests carry the pairing bearer token plus the device
 * identity headers the cloud uses for its token-to-device keep-alive.
 *
 * Wire identity is cassette_local_id (32-char hex, server-computed) — never
 * the Subsonic id, which is local to one hub library.
 */
public final class CassetteSyncApi {
    private static final String TAG = "CassetteSyncApi";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private CassetteSyncApi() {
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public static String deviceLabel() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "Android";
        String label = model.toLowerCase().startsWith(manufacturer.toLowerCase())
                ? model
                : manufacturer + " " + model;
        return label.trim().isEmpty() ? "Android phone" : label.trim();
    }

    private static Request.Builder request(String path, String bearerToken) {
        return new Request.Builder()
                .url(Cassette.BASE_URL + path)
                .header("Authorization", "Bearer " + bearerToken)
                .header("X-Cassette-Device-Id", Preferences.getCassetteDeviceId())
                .header("X-Cassette-Device-Label", deviceLabel());
    }

    private static JsonObject execute(Request request) throws IOException {
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Cassette sync: HTTP " + response.code() + " for " + request.url().encodedPath());
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }

    @Nullable
    private static String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------
    // Account

    public static final class Account {
        @Nullable public final String downloadQuality;
        @Nullable public final String themeId;
        public final boolean serverMode;
        /** OPT-OUT gate for auto crash/hang reports. Default true when absent. */
        public final boolean diagnosticsConsent;

        Account(@Nullable String downloadQuality, @Nullable String themeId, boolean serverMode,
                boolean diagnosticsConsent) {
            this.downloadQuality = downloadQuality;
            this.themeId = themeId;
            this.serverMode = serverMode;
            this.diagnosticsConsent = diagnosticsConsent;
        }
    }

    /** Read a wire boolean, defaulting when the key is absent or JSON null. */
    private static boolean optBool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsBoolean()
                : fallback;
    }

    /** GET /api/sync/account — account-level settings (download tier, skin,
     *  diagnostics opt-out). diagnosticsConsent is camelCase on the wire and
     *  defaults true (opt-out) whenever the field is missing. */
    public static Account getAccount(String bearerToken) throws IOException {
        JsonObject json = execute(request("/api/sync/account", bearerToken).get().build());
        boolean serverMode = optBool(json, "serverMode", false);
        boolean diagnosticsConsent = optBool(json, "diagnosticsConsent", true);
        return new Account(optString(json, "downloadQuality"), optString(json, "themeId"),
                serverMode, diagnosticsConsent);
    }

    // ------------------------------------------------------------------
    // Device registry

    /** POST /api/sync/devices — upsert this phone in the account's registry. */
    public static void registerDevice(String bearerToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("device_id", Preferences.getCassetteDeviceId());
        body.addProperty("device_label", deviceLabel());
        body.addProperty("platform", "android");
        body.addProperty("model", Build.MODEL != null ? Build.MODEL : "unknown");
        body.addProperty("app_version", BuildConfig.VERSION_NAME);
        execute(request("/api/sync/devices", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
    }

    // ------------------------------------------------------------------
    // Intents

    public static final class Intent {
        public final String id;
        public final String scope;
        public final String targetId;
        public final String intentKind;
        public final String state;

        Intent(String id, String scope, String targetId, String intentKind, String state) {
            this.id = id;
            this.scope = scope;
            this.targetId = targetId;
            this.intentKind = intentKind;
            this.state = state;
        }
    }

    /**
     * GET /api/sync/intents, filtered to what THIS device should act on:
     * album-scope, non-terminal, and targeted at this device id. The strict
     * device filter matters — intents for the user's other devices (iPhone)
     * come back in the same list, and acting on them would race that
     * device's own executor.
     */
    public static List<Intent> getActionableIntents(String bearerToken) throws IOException {
        JsonObject json = execute(request("/api/sync/intents", bearerToken).get().build());
        List<Intent> actionable = new ArrayList<>();
        if (!json.has("intents") || !json.get("intents").isJsonArray()) return actionable;

        String myDeviceId = Preferences.getCassetteDeviceId();
        for (JsonElement element : json.getAsJsonArray("intents")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String state = optString(row, "state");
            String scope = optString(row, "scope");
            String targetDeviceId = optString(row, "target_device_id");
            boolean nonTerminal = "pending".equals(state) || "syncing".equals(state) || "waiting".equals(state);
            if (!nonTerminal || !"album".equals(scope) || !myDeviceId.equals(targetDeviceId)) continue;

            String id = optString(row, "id");
            String targetId = optString(row, "target_id");
            String intentKind = optString(row, "intent_kind");
            if (id == null || targetId == null || intentKind == null) continue;
            actionable.add(new Intent(id, scope, targetId, intentKind, state));
        }
        return actionable;
    }

    public static final class SyncTrack {
        public final String subsonicTrackId;
        public final String cassetteLocalId;
        @Nullable public final String mbid;

        SyncTrack(String subsonicTrackId, String cassetteLocalId, @Nullable String mbid) {
            this.subsonicTrackId = subsonicTrackId;
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
        }
    }

    public static final class IntentTracks {
        public final List<SyncTrack> tracks;
        @Nullable public final String artistImageUrl;

        IntentTracks(List<SyncTrack> tracks, @Nullable String artistImageUrl) {
            this.tracks = tracks;
            this.artistImageUrl = artistImageUrl;
        }
    }

    /** GET /api/sync/intents/:id/tracks — the concrete downloadable track list. */
    public static IntentTracks getIntentTracks(String bearerToken, String intentId) throws IOException {
        JsonObject json = execute(request("/api/sync/intents/" + intentId + "/tracks", bearerToken).get().build());
        List<SyncTrack> tracks = new ArrayList<>();
        if (json.has("tracks") && json.get("tracks").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("tracks")) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                String subsonicTrackId = optString(row, "subsonic_track_id");
                String cassetteLocalId = optString(row, "cassette_local_id");
                if (cassetteLocalId == null) continue;
                tracks.add(new SyncTrack(
                        subsonicTrackId != null ? subsonicTrackId : "",
                        cassetteLocalId,
                        optString(row, "mbid")));
            }
        }

        String artistImageUrl = null;
        if (json.has("artist") && json.get("artist").isJsonObject()) {
            artistImageUrl = optString(json.getAsJsonObject("artist"), "image_url");
        }
        return new IntentTracks(tracks, artistImageUrl);
    }

    /** PUT /api/sync/intents/:id — report a state transition. */
    public static void updateIntent(String bearerToken, String intentId, String state, @Nullable String errorMessage) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", state);
        if (errorMessage != null) body.addProperty("error_message", errorMessage);
        execute(request("/api/sync/intents/" + intentId, bearerToken)
                .put(RequestBody.create(body.toString(), JSON))
                .build());
    }

    // ------------------------------------------------------------------
    // Artwork manifest (per-album cover freshness)

    /** One owned album's artwork identity, from the artwork manifest. The phone
     *  matches it to a downloaded album by group key (artist_name + album_name,
     *  the only join the wire offers) and re-pulls a cover when
     *  localCoverVersion — the fingerprint of the folder cover's bytes on the
     *  user's Mac — moves. */
    public static final class ArtworkAlbum {
        @Nullable public final String artistName;
        @Nullable public final String albumName;
        @Nullable public final String localCoverVersion;
        @Nullable public final String contentVersion;

        ArtworkAlbum(@Nullable String artistName, @Nullable String albumName,
                     @Nullable String localCoverVersion, @Nullable String contentVersion) {
            this.artistName = artistName;
            this.albumName = albumName;
            this.localCoverVersion = localCoverVersion;
            this.contentVersion = contentVersion;
        }
    }

    /**
     * GET /api/sync/devices/:id/artwork — every album this device owns, each with
     * the folder-cover fingerprint (local_cover_version) and a content version.
     * The same generic feed iOS reads; the phone uses it to detect a cover that
     * changed on the Mac and re-materialize just that one.
     */
    public static List<ArtworkAlbum> getDeviceArtworkManifest(String bearerToken) throws IOException {
        String deviceId = URLEncoder.encode(Preferences.getCassetteDeviceId(), "UTF-8");
        JsonObject json = execute(request("/api/sync/devices/" + deviceId + "/artwork", bearerToken).get().build());
        List<ArtworkAlbum> albums = new ArrayList<>();
        if (json.has("albums") && json.get("albums").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("albums")) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                albums.add(new ArtworkAlbum(
                        optString(row, "artist_name"),
                        optString(row, "album_name"),
                        optString(row, "local_cover_version"),
                        optString(row, "content_version")));
            }
        }
        return albums;
    }

    // ------------------------------------------------------------------
    // Device inventory (the ACTUAL set — what this phone holds on disk)

    public static final class InventoryItem {
        public final String cassetteLocalId;
        @Nullable public final String mbid;

        public InventoryItem(String cassetteLocalId, @Nullable String mbid) {
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
        }
    }

    private static JsonArray inventoryArray(List<InventoryItem> items) {
        JsonArray array = new JsonArray();
        for (InventoryItem item : items) {
            JsonObject row = new JsonObject();
            row.addProperty("cassette_local_id", item.cassetteLocalId);
            if (item.mbid != null) row.addProperty("mbid", item.mbid);
            array.add(row);
        }
        return array;
    }

    /** POST /api/sync/device-inventory — differential report after an add/remove. */
    public static void reportInventoryDiff(String bearerToken, List<InventoryItem> added, List<InventoryItem> removed) throws IOException {
        if (added.isEmpty() && removed.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.addProperty("device_id", Preferences.getCassetteDeviceId());
        body.addProperty("device_label", deviceLabel());
        if (!added.isEmpty()) body.add("added", inventoryArray(added));
        if (!removed.isEmpty()) body.add("removed", inventoryArray(removed));
        execute(request("/api/sync/device-inventory", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
    }

    /**
     * POST /api/sync/device-inventory full_state — a complete snapshot of the
     * owned set. This stamps the device's inventory freshness server-side,
     * which is the gate the cloud reconciler requires before it will queue
     * removes for this device — so it must run periodically, not just once.
     */
    public static void reportFullInventory(String bearerToken, List<InventoryItem> items) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("device_id", Preferences.getCassetteDeviceId());
        body.addProperty("device_label", deviceLabel());
        body.addProperty("full_state", true);
        body.add("items", inventoryArray(items));
        execute(request("/api/sync/device-inventory", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        Log.d(TAG, "reportFullInventory: " + items.size() + " items");
    }

    // ------------------------------------------------------------------
    // Play spine (cross-surface listening history)

    /** One completed play. `playedAtIso` is a UTC ISO-8601 timestamp. */
    public static final class PlayItem {
        public final String cassetteLocalId;
        @Nullable public final String mbid;
        public final String playedAtIso;

        public PlayItem(String cassetteLocalId, @Nullable String mbid, String playedAtIso) {
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
            this.playedAtIso = playedAtIso;
        }
    }

    /**
     * POST /api/sync/plays — batch-report completed plays to the cross-surface
     * play spine. Append-only + no server-side dedup, so the caller must only
     * send plays it hasn't sent (CassettePlaySync advances a high-water mark on
     * success). Identity is cassette_local_id, computed on-device via
     * CassetteLocalId so it keys on the same id as the web-computed library
     * index. Send at most 500 per call (the endpoint's cap).
     */
    public static void recordPlays(String bearerToken, List<PlayItem> plays) throws IOException {
        if (plays.isEmpty()) return;
        JsonArray array = new JsonArray();
        for (PlayItem play : plays) {
            JsonObject row = new JsonObject();
            row.addProperty("cassette_local_id", play.cassetteLocalId);
            row.addProperty("played_at", play.playedAtIso);
            row.addProperty("source_device", "android");
            if (play.mbid != null) row.addProperty("mbid", play.mbid);
            array.add(row);
        }
        JsonObject body = new JsonObject();
        body.add("plays", array);
        execute(request("/api/sync/plays", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
    }

    /** One play row from GET /api/sync/plays (ids only — no title/artist). */
    public static final class RemotePlayData {
        @Nullable public final String cassetteLocalId;
        @Nullable public final String mbid;
        @Nullable public final String playedAtIso;
        @Nullable public final String sourceDevice;

        RemotePlayData(@Nullable String cassetteLocalId, @Nullable String mbid,
                       @Nullable String playedAtIso, @Nullable String sourceDevice) {
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
            this.playedAtIso = playedAtIso;
            this.sourceDevice = sourceDevice;
        }
    }

    /**
     * GET /api/sync/plays — the account's play history across ALL devices,
     * newest first. `sinceIso` (optional, UTC ISO-8601) returns only plays after
     * that instant; `limit` caps the batch (server max 1000). Used by
     * CassettePlayPull for the cross-surface "recently played" read.
     */
    public static List<RemotePlayData> listPlaysSince(String bearerToken, @Nullable String sinceIso, int limit) throws IOException {
        StringBuilder path = new StringBuilder("/api/sync/plays?limit=").append(limit);
        if (sinceIso != null) path.append("&since=").append(URLEncoder.encode(sinceIso, "UTF-8"));
        JsonObject json = execute(request(path.toString(), bearerToken).get().build());
        List<RemotePlayData> out = new ArrayList<>();
        JsonArray plays = json.getAsJsonArray("plays");
        if (plays != null) {
            for (JsonElement el : plays) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                out.add(new RemotePlayData(
                        optString(row, "cassette_local_id"),
                        optString(row, "mbid"),
                        optString(row, "played_at"),
                        optString(row, "source_device")
                ));
            }
        }
        return out;
    }

    /** One ranked song from POST /api/sync/popular-rank. */
    public static final class PopularRankItem {
        public final String cassetteLocalId;
        public final String title;
        public final int playCount;

        public PopularRankItem(String cassetteLocalId, String title, int playCount) {
            this.cassetteLocalId = cassetteLocalId;
            this.title = title;
            this.playCount = playCount;
        }
    }

    /**
     * POST /api/sync/popular-rank — cloud play-count ranking for an artist's
     * owned songs (same order as the web/desktop artist rail). Caller sends
     * cassette_local_id + title; server returns play_count ↓, title A→Z,
     * capped at 20. Offline clients should fall back to a local tally with
     * the same comparator.
     */
    public static List<PopularRankItem> rankPopular(
            String bearerToken,
            List<PopularRankItem> items
    ) throws IOException {
        if (items.isEmpty()) return new ArrayList<>();
        JsonArray array = new JsonArray();
        for (PopularRankItem item : items) {
            JsonObject row = new JsonObject();
            row.addProperty("cassette_local_id", item.cassetteLocalId);
            row.addProperty("title", item.title != null ? item.title : "");
            array.add(row);
        }
        JsonObject body = new JsonObject();
        body.add("items", array);
        JsonObject json = execute(request("/api/sync/popular-rank", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        List<PopularRankItem> out = new ArrayList<>();
        JsonArray ranked = json.getAsJsonArray("items");
        if (ranked != null) {
            for (JsonElement el : ranked) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                String id = optString(row, "cassette_local_id");
                if (id == null) continue;
                String title = optString(row, "title");
                int count = row.has("play_count") && !row.get("play_count").isJsonNull()
                        ? row.get("play_count").getAsInt()
                        : 0;
                out.add(new PopularRankItem(id, title != null ? title : "", count));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Cloud playlists (user_playlists)

    public static final class CloudPlaylistItem {
        public final String id;
        public final String cassetteLocalId;
        @Nullable public final String mbid;
        public final double position;

        CloudPlaylistItem(String id, String cassetteLocalId, @Nullable String mbid, double position) {
            this.id = id;
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
            this.position = position;
        }
    }

    public static final class CloudPlaylist {
        public final String id;
        public final String name;
        @Nullable public final String description;
        @Nullable public final String coverImageUrl;
        public final List<CloudPlaylistItem> items;

        CloudPlaylist(String id, String name, @Nullable String description,
                      @Nullable String coverImageUrl, List<CloudPlaylistItem> items) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.coverImageUrl = coverImageUrl;
            this.items = items;
        }
    }

    private static CloudPlaylist parsePlaylist(JsonObject row) {
        List<CloudPlaylistItem> items = new ArrayList<>();
        JsonArray arr = row.getAsJsonArray("items");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject it = el.getAsJsonObject();
                if (it.has("removed_at") && !it.get("removed_at").isJsonNull()) continue;
                String id = optString(it, "id");
                String lid = optString(it, "cassette_local_id");
                if (id == null || lid == null) continue;
                double pos = it.has("position") && !it.get("position").isJsonNull()
                        ? it.get("position").getAsDouble() : 0;
                items.add(new CloudPlaylistItem(id, lid, optString(it, "mbid"), pos));
            }
            items.sort((a, b) -> Double.compare(a.position, b.position));
        }
        String id = optString(row, "id");
        String name = optString(row, "name");
        if (id == null || name == null) {
            throw new IllegalArgumentException("playlist missing id/name");
        }
        return new CloudPlaylist(id, name, optString(row, "description"),
                optString(row, "cover_image_url"), items);
    }

    /** GET /api/sync/playlists?include=items */
    public static List<CloudPlaylist> listPlaylists(String bearerToken, boolean includeItems) throws IOException {
        String path = includeItems ? "/api/sync/playlists?include=items" : "/api/sync/playlists";
        JsonObject json = execute(request(path, bearerToken).get().build());
        List<CloudPlaylist> out = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray("playlists");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                try {
                    out.add(parsePlaylist(el.getAsJsonObject()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    public static CloudPlaylist getPlaylist(String bearerToken, String id) throws IOException {
        JsonObject json = execute(request("/api/sync/playlists/" + id, bearerToken).get().build());
        return parsePlaylist(json.getAsJsonObject("playlist"));
    }

    public static CloudPlaylist createPlaylist(String bearerToken, String id, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("name", name);
        JsonObject json = execute(request("/api/sync/playlists", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        return parsePlaylist(json.getAsJsonObject("playlist"));
    }

    public static CloudPlaylist updatePlaylist(String bearerToken, String id, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        JsonObject json = execute(request("/api/sync/playlists/" + id, bearerToken)
                .put(RequestBody.create(body.toString(), JSON))
                .build());
        return parsePlaylist(json.getAsJsonObject("playlist"));
    }

    public static void deletePlaylist(String bearerToken, String id) throws IOException {
        execute(request("/api/sync/playlists/" + id, bearerToken)
                .delete()
                .build());
    }

    public static CloudPlaylist applyPlaylistOps(String bearerToken, String playlistId, JsonArray ops)
            throws IOException {
        JsonObject body = new JsonObject();
        body.add("ops", ops);
        JsonObject json = execute(request("/api/sync/playlists/" + playlistId + "/ops", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        return parsePlaylist(json.getAsJsonObject("playlist"));
    }

    // ------------------------------------------------------------------
    // Cloud favorites / Saved

    public static final class CloudFavorite {
        public final String cassetteLocalId;
        @Nullable public final String mbid;
        @Nullable public final String trackTitle;
        @Nullable public final String artistName;
        @Nullable public final String albumName;

        CloudFavorite(String cassetteLocalId, @Nullable String mbid,
                      @Nullable String trackTitle, @Nullable String artistName,
                      @Nullable String albumName) {
            this.cassetteLocalId = cassetteLocalId;
            this.mbid = mbid;
            this.trackTitle = trackTitle;
            this.artistName = artistName;
            this.albumName = albumName;
        }
    }

    public static List<CloudFavorite> listFavorites(String bearerToken) throws IOException {
        JsonObject json = execute(request("/api/sync/favorites", bearerToken).get().build());
        List<CloudFavorite> out = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray("favorites");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                String lid = optString(row, "cassette_local_id");
                if (lid == null) continue;
                out.add(new CloudFavorite(lid, optString(row, "mbid"),
                        optString(row, "track_title"), optString(row, "artist_name"),
                        optString(row, "album_name")));
            }
        }
        return out;
    }

    public static void upsertFavorite(String bearerToken, String cassetteLocalId,
                                      @Nullable String mbid, @Nullable String trackTitle,
                                      @Nullable String artistName, @Nullable String albumName)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("cassette_local_id", cassetteLocalId);
        body.addProperty("event_at", java.time.Instant.now().toString());
        body.addProperty("source_device", "android");
        if (mbid != null) body.addProperty("mbid", mbid);
        if (trackTitle != null) body.addProperty("track_title", trackTitle);
        if (artistName != null) body.addProperty("artist_name", artistName);
        if (albumName != null) body.addProperty("album_name", albumName);
        execute(request("/api/sync/favorites", bearerToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build());
    }

    public static void removeFavorite(String bearerToken, String cassetteLocalId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("cassette_local_id", cassetteLocalId);
        body.addProperty("event_at", java.time.Instant.now().toString());
        execute(request("/api/sync/favorites", bearerToken)
                .delete(RequestBody.create(body.toString(), JSON))
                .build());
    }
}
