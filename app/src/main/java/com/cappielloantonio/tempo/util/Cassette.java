package com.cappielloantonio.tempo.util;

import android.os.Build;

import com.cappielloantonio.tempo.BuildConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Cassette account pairing. The phone signs into the Cassette account in the
 * browser (/auth/player), the cloud redirects back with a bearer token via the
 * cassette:// scheme, and this client exchanges the token at /api/player/me for
 * the hub's LAN address + Subsonic credential. The cloud is authoritative for
 * that credential and keeps it stable across re-pairs, so the phone is never
 * re-keyed (the iOS stranding lesson).
 */
public final class Cassette {
    /** www, not apex: the apex 307-redirects and would double every request. */
    public static final String BASE_URL = "https://www.cassette.digital";

    public static final String CLIENT_ID = "cassette-android";
    public static final String REDIRECT_URI = "cassette://auth/callback";

    private Cassette() {
    }

    /**
     * Cassette dynamic color: map the account skin to its theme overlay. The
     * accents are the web's own dial math (scripts/icon/spec.mjs skinColors).
     */
    public static int themeOverlayFor(String themeId) {
        if (themeId == null) return com.cappielloantonio.tempo.R.style.CassetteSkin;
        switch (themeId) {
            case "amber":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Amber;
            case "rust":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Rust;
            case "marine":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Marine;
            case "lime":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Lime;
            case "violet":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Violet;
            case "rose":
                return com.cappielloantonio.tempo.R.style.CassetteSkin_Rose;
            default:
                return com.cappielloantonio.tempo.R.style.CassetteSkin;
        }
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT;
        return fingerprint != null
                && (fingerprint.contains("generic")
                || fingerprint.contains("emulator")
                || fingerprint.contains("sdk_gphone"));
    }

    /**
     * The page that starts the sign-in, with this sign-in's one-time code and public key
     * (CassettePairing, security design rule 3). Both are base64url, safe in an address as is.
     */
    public static String pairingUrl(String state, String key) {
        try {
            return BASE_URL + "/auth/player?client=" + CLIENT_ID
                    + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                    + "&state=" + URLEncoder.encode(state, "UTF-8")
                    + "&key=" + URLEncoder.encode(key, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 always exists
        }
    }

    /**
     * cassette (BUG-876, BUG-899, iPhone c652049f): the address a phone signs in to before its
     * account has a computer. It never resolves; Home shows "Not set up", radio works, and
     * the phone connects on its own once a computer signs in (CassetteComputerLogin).
     */
    public static final String NO_COMPUTER_HOST = "no-computer.invalid";

    /** Whether this server address is the stand-in for a computer the account doesn't have yet. */
    public static boolean isNoComputer(String serverUrl) {
        if (serverUrl == null) return false;
        try {
            return NO_COMPUTER_HOST.equals(new java.net.URI(serverUrl).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** The registered player config, as returned by /api/player/me. */
    public static final class PlayerConfig {
        public final String lanHostname;
        public final int lanPort;
        public final String subsonicUsername;
        public final String subsonicPassword;
        /** The account's cross-device skin (users.theme_id); null on older clouds. */
        public final String themeId;
        /**
         * The computer's key (SHA-256 of its public key, base64), or null until the cloud has
         * one: the phone then talks to the computer encrypted and to that key only
         * (CassetteHubTrust, security design rule 2).
         */
        public final String lanTlsPin;

        PlayerConfig(String lanHostname, int lanPort, String subsonicUsername, String subsonicPassword, String themeId) {
            this(lanHostname, lanPort, subsonicUsername, subsonicPassword, themeId, null);
        }

        PlayerConfig(String lanHostname, int lanPort, String subsonicUsername, String subsonicPassword, String themeId, String lanTlsPin) {
            this.lanHostname = lanHostname;
            this.lanPort = lanPort;
            this.subsonicUsername = subsonicUsername;
            this.subsonicPassword = subsonicPassword;
            this.themeId = themeId;
            this.lanTlsPin = lanTlsPin;
        }

        public String address() {
            return "http://" + lanHostname + ":" + lanPort;
        }

        /** The stand-in: the account has no computer yet. */
        public boolean isNoComputer() {
            return NO_COMPUTER_HOST.equals(lanHostname);
        }

        static PlayerConfig noComputer(String themeId) {
            return new PlayerConfig(NO_COMPUTER_HOST, 4533, "cassette-pending", "pending", themeId);
        }
    }

    /** Blocking; call off the main thread. */
    public static PlayerConfig fetchPlayerConfig(String bearerToken) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/player/me")
                .header("Authorization", "Bearer " + bearerToken)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            // No computer on the account yet: the cloud answers 404, the iPhone reads that as
            // "none" (LoginVC.fetch), and so does this: sign in to the stand-in, Home shows Not set up.
            if (response.code() == 404) {
                return PlayerConfig.noComputer(null);
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Cassette sign-in failed (HTTP " + response.code() + ")");
            }

            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!json.has("lanHostname") || json.get("lanHostname").isJsonNull()
                    || !json.has("subsonicUsername") || json.get("subsonicUsername").isJsonNull()
                    || !json.has("subsonicPassword") || json.get("subsonicPassword").isJsonNull()) {
                // no computer on the account yet: sign in anyway, to the stand-in (iPhone c652049f)
                String themeId = json.has("themeId") && !json.get("themeId").isJsonNull() ? json.get("themeId").getAsString() : null;
                return PlayerConfig.noComputer(themeId);
            }

            int port = json.has("lanPort") && !json.get("lanPort").isJsonNull()
                    ? json.get("lanPort").getAsInt()
                    : 4533;

            String lanHostname = json.get("lanHostname").getAsString();
            // Emulator dev affordance (debug builds only): the emulator has no
            // mDNS, so the hub's .local name never resolves. 10.0.2.2 is the
            // emulator's fixed alias for the host Mac — which is where the hub
            // runs during development. Real devices always use the LAN name.
            if (BuildConfig.DEBUG && isEmulator()) {
                lanHostname = "10.0.2.2";
            }

            String themeId = json.has("themeId") && !json.get("themeId").isJsonNull()
                    ? json.get("themeId").getAsString()
                    : null;

            String lanTlsPin = json.has("lanTlsPin") && !json.get("lanTlsPin").isJsonNull()
                    ? json.get("lanTlsPin").getAsString()
                    : null;

            return new PlayerConfig(
                    lanHostname,
                    port,
                    json.get("subsonicUsername").getAsString(),
                    json.get("subsonicPassword").getAsString(),
                    themeId,
                    lanTlsPin);
        }
    }
}
