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
     */
    public static Server save(Cassette.PlayerConfig config) {
        Server server = new Server(
                "cassette-" + config.subsonicUsername,
                "Cassette",
                config.subsonicUsername,
                config.subsonicPassword,
                config.address(),
                null,
                System.currentTimeMillis(),
                false);
        new ServerRepository().insert(server);
        Preferences.setServerId(server.getServerId());
        Preferences.setServer(server.getAddress());
        Preferences.setLocalAddress(null);
        Preferences.setUser(server.getUsername());
        Preferences.setPassword(server.getPassword());
        Preferences.setLowSecurity(false);
        App.getSubsonicClientInstance(true);
        return server;
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
