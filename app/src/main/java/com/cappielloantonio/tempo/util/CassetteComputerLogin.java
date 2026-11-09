package com.cappielloantonio.tempo.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.repository.ServerRepository;

/**
 * cassette: the phone's computer, from the sign-in answer (`/api/player/me`), in one place
 * for the sign-in and for the hand-over (iPhone CassetteComputerLogin, c652049f). A phone that
 * signed in before its account had a computer asks again, at most every 10 seconds, while
 * Home shows "Not set up" and whenever the app comes back to the front; once a computer has
 * signed in, the phone signs in to it on its own and the stand-in goes. The Cassette sign-in
 * itself stays.
 */
public final class CassetteComputerLogin {
    private static final String TAG = "CassetteComputerLogin";
    private static final long EVERY_MS = 10_000;
    private static volatile boolean inFlight = false;
    private static volatile long lastAskAt = 0;

    private CassetteComputerLogin() {
    }

    /** The phone signed in to the stand-in: its account has no computer yet. */
    public static boolean waitingForComputer() {
        return Cassette.isNoComputer(Preferences.getServer());
    }

    /**
     * Save a computer (or the stand-in) as this phone's server and make it the one in use.
     * Re-pairing REPLACES the same row (Room insert is REPLACE on the primary key): play
     * history, downloads and the queue are keyed to it.
     *
     * The row keeps no password (security design rule 1): the password is sealed to this phone
     * in CassetteSecureStore, and the saved-server table is a plain database file. The computer's
     * key, when the cloud has one, is recorded with it (CassetteHubTrust, rule 2).
     */
    public static Server save(Cassette.PlayerConfig config) {
        Server server = new Server(
                "cassette-" + config.subsonicUsername,
                "Cassette",
                config.subsonicUsername,
                "",
                config.address(),
                null,
                System.currentTimeMillis(),
                false);
        new ServerRepository().insert(server);
        Preferences.setServerId(server.getServerId());
        Preferences.setServer(server.getAddress());
        Preferences.setLocalAddress(null);
        Preferences.setUser(server.getUsername());
        Preferences.setPassword(config.subsonicPassword);
        Preferences.setLowSecurity(false);
        CassetteHubTrust.record(config);
        App.getSubsonicClientInstance(true);
        return server;
    }

    /**
     * One-time, off the main thread: phones that signed in before rule 1 kept the computer's
     * password in the saved-server table too. Seal it (if the settings file somehow lost its own
     * copy) and empty the table's. Cheap and idempotent, so it simply runs at every start.
     */
    public static void sealSavedPasswords() {
        new Thread(() -> {
            try {
                com.cappielloantonio.tempo.database.dao.ServerDao dao =
                        com.cappielloantonio.tempo.database.AppDatabase.getInstance().serverDao();
                for (Server s : dao.getAllSync()) {
                    if (!s.getServerId().startsWith("cassette-") || s.getPassword().isEmpty()) continue;
                    if (s.getServerId().equals(Preferences.getServerId()) && Preferences.getPassword() == null) {
                        Preferences.setPassword(s.getPassword());
                    }
                    dao.forgetPassword(s.getServerId());
                    Log.i(TAG, "the saved computer password now lives only in the sealed store");
                }
            } catch (Exception e) {
                Log.w(TAG, "couldn't seal the saved computer password", e);
            }
        }, "cassette-seal-saved").start();
    }

    /** Ask whether the account's computer has signed in yet; [onConnected] on the main thread when it has. */
    public static void connectIfReady(Runnable onConnected) {
        if (!waitingForComputer() || inFlight) return;
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null || bearer.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAskAt < EVERY_MS) return;
        lastAskAt = now;
        inFlight = true;
        new Thread(() -> {
            try {
                Cassette.PlayerConfig config = Cassette.fetchPlayerConfig(bearer);
                if (config.isNoComputer() || !waitingForComputer()) return;
                String standIn = Preferences.getServerId();
                save(config);
                // the stand-in held nothing of the person's: no computer ever filled it
                if (standIn != null && !standIn.equals(Preferences.getServerId())) {
                    new ServerRepository().delete(new Server(standIn, "", "", "", "", null, 0L, false));
                }
                Log.i(TAG, "computer arrived: stand-in sign-in replaced");
                new Handler(Looper.getMainLooper()).post(onConnected);
            } catch (Exception e) {
                Log.w(TAG, "computer check failed", e);
            } finally {
                inFlight = false;
            }
        }, "cassette-computer-login").start();
    }
}
