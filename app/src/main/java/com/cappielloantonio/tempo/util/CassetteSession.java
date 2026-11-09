package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.repository.ServerRepository;

/**
 * cassette: signing out of Cassette on this phone (BUG-937), and noticing when
 * the cloud has already signed it out (BUG-946).
 *
 * Signing out ends this phone's sign-in on the cloud (POST /api/player/sign-out,
 * which revokes exactly this token), forgets the account on the phone, drops the
 * saved Cassette server so the next sign-in goes through Cassette, and, when the
 * person asks, removes the music on this phone. The sync loop is gated on the
 * sign-in token, so it stops with it.
 */
public final class CassetteSession {
    private static final String TAG = "CassetteSession";
    private static final MutableLiveData<Boolean> SIGNED_OUT = new MutableLiveData<>(false);

    private CassetteSession() {}

    /** True once the cloud has refused this phone's sign-in. */
    public static LiveData<Boolean> signedOutByCloud() {
        return SIGNED_OUT;
    }

    /** The cloud answered 401 to this phone's token: say so once. */
    static void cloudRefusedToken() {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null || bearer.isEmpty()) return;
        if (!Boolean.TRUE.equals(SIGNED_OUT.getValue())) SIGNED_OUT.postValue(true);
    }

    /**
     * Sign out of Cassette. Runs the cloud call and the clean-up off the main
     * thread, then {@code then} on the main thread (typically the activity's
     * quit to the sign-in screen).
     */
    public static void signOut(Context context, boolean removeMusic, Runnable then) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            String bearer = Preferences.getCassetteBearerToken();
            if (bearer != null && !bearer.isEmpty()) {
                try {
                    CassetteSyncApi.signOut(bearer);
                } catch (Exception e) {
                    // Offline or already revoked: the phone forgets the token either way.
                    Log.w(TAG, "sign-out call failed", e);
                }
            }

            String serverId = Preferences.getServerId();
            if (serverId != null && serverId.startsWith("cassette-")) {
                new ServerRepository().delete(new Server(serverId, "", "", "", "", null, 0L, false));
            }

            Preferences.setCassetteBearerToken(null);
            CassetteHubTrust.forget();
            CassettePairing.forget();
            Preferences.setCassetteAccountName(null);
            Preferences.setCassetteAccountEmail(null);
            Preferences.setCassetteAccountImage(null);
            Preferences.setCassetteAvatarPreset(null);

            if (removeMusic) {
                DownloadUtil.getDownloadTracker(app).removeAll();
            }

            // what the phone kept of this account: its Home, its genres and song tags, its stations
            for (String name : new String[]{"cassette_home.json", "album-genres.json", "library-moods.json"}) {
                new java.io.File(app.getFilesDir(), name).delete();
            }
            App.getInstance().getPreferences().edit()
                    .remove("cassette_album_genres_at").remove("cassette_library_moods_at").apply();
            CassetteRadioSaved.forget(app);

            SIGNED_OUT.postValue(false);
            new Handler(Looper.getMainLooper()).post(then);
        }, "cassette-sign-out").start();
    }

    /** The account's display name, for the sign-out question. */
    public static String accountName() {
        String name = Preferences.getCassetteAccountName();
        return name != null && !name.isEmpty() ? name : App.getContext().getString(com.cappielloantonio.tempo.R.string.app_name);
    }
}
