package com.cappielloantonio.tempo.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.interfaces.SystemCallback;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.repository.ServerRepository;
import com.cappielloantonio.tempo.repository.SystemRepository;
import com.cappielloantonio.tempo.util.Cassette;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.UUID;

/**
 * Lands the cassette://auth/callback?token=… redirect from the Cassette
 * sign-in flow (or ?error=cancelled, the pairing page's Cancel). Exchanges the bearer token at /api/player/me for the hub's
 * LAN address + Subsonic credential, saves it as the active server (exactly
 * like tapping a saved server on the login screen), and enters the app.
 */
public class PairCallbackActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair_callback);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        // Cancel on the pairing page comes back as error=cancelled (BUG-642): back to the
        // app where pairing started, without an error.
        if (data != null && "cancelled".equals(data.getQueryParameter("error"))) {
            Intent back = new Intent(this, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(back);
            finish();
            return;
        }
        String token = data != null ? data.getQueryParameter("token") : null;

        if (token == null || token.isEmpty()) {
            fail(getString(R.string.cassette_pairing_failed));
            return;
        }

        pair(token);
    }

    private void pair(String token) {
        new Thread(() -> {
            try {
                Cassette.PlayerConfig config = Cassette.fetchPlayerConfig(token);
                // Keep the bearer token: it lets the app re-read /api/player/me
                // later (skin changes, hub address drift) without re-pairing.
                Preferences.setCassetteBearerToken(token);
                Preferences.setCassetteThemeId(config.themeId);
                runOnUiThread(() -> activate(config));
            } catch (Exception exception) {
                String message = exception.getMessage() != null
                        ? exception.getMessage()
                        : getString(R.string.cassette_pairing_failed);
                runOnUiThread(() -> fail(message));
            }
        }).start();
    }

    /** Save + activate, mirroring LoginFragment's server-tap path. */
    private void activate(Cassette.PlayerConfig config) {
        // Stable identity: re-pairing must REPLACE the same server row (Room
        // insert is REPLACE on the primary key), not mint a new UUID — play
        // history, downloads, and the queue are all keyed to this id, and a
        // fresh id every re-pair orphans them (the local twin of the iOS
        // credential-stranding lesson).
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

        // Reachability is transient (the hub may be on another network right
        // now); the pairing itself is done either way, so keep the credential
        // and enter the app. The library surfaces unreachable state itself.
        new SystemRepository().checkUserCredential(new SystemCallback() {
            @Override
            public void onError(Exception exception) {
                Toast.makeText(PairCallbackActivity.this, R.string.cassette_pairing_unreachable, Toast.LENGTH_LONG).show();
                enterApp();
            }

            @Override
            public void onSuccess(String password, String token, String salt) {
                enterApp();
            }
        });
    }

    private void enterApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void fail(String message) {
        ProgressBar progressBar = findViewById(R.id.pairing_progress_bar);
        TextView statusTextView = findViewById(R.id.pairing_status_text_view);
        progressBar.setVisibility(View.GONE);
        statusTextView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
