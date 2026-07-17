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
}
