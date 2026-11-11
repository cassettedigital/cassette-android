package com.cappielloantonio.tempo.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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
import com.cappielloantonio.tempo.util.CassetteComputerLogin;
import com.cappielloantonio.tempo.util.CassetteInsets;
import com.cappielloantonio.tempo.util.CassettePairing;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.UUID;

/**
 * Lands the cassette://auth/callback redirect from the Cassette sign-in flow
 * (or ?error=cancelled, the pairing page's Cancel). Takes the sign-in only when
 * it answers the sign-in this phone started, by its one-time code, and opens it
 * with this phone's key when the cloud sealed it (CassettePairing, BUG-938);
 * anything else is dropped and changes nothing. Exchanges the bearer token at
 * /api/player/me for the hub's LAN address + Subsonic credential, saves it as
 * the active server, and enters the app.
 */
public class PairCallbackActivity extends AppCompatActivity {
    private static final String TAG = "PairCallbackActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair_callback);
        CassetteInsets.keepClearOfBars(this);

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
        CassettePairing.Result answer = CassettePairing.accept(data);
        switch (answer.outcome) {
            case SIGN_IN:
                pair(answer.token);
                break;
            case NOT_ASKED:
                fail(getString(R.string.cassette_pairing_not_asked));
                break;
            case EXPIRED:
                fail(getString(R.string.cassette_pairing_expired));
                break;
            default:
                fail(getString(R.string.cassette_pairing_failed));
        }
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
                // The detail goes to the log; the person gets a plain line and a way back.
                Log.w(TAG, "sign-in: couldn't read the account's computer", exception);
                runOnUiThread(() -> fail(getString(R.string.cassette_pairing_failed)));
            }
        }).start();
    }

    /** Save + activate, mirroring LoginFragment's server-tap path. */
    private void activate(Cassette.PlayerConfig config) {
        CassetteComputerLogin.save(config);
        // no computer on the account yet: straight in, to Home's "Not set up" (iPhone c652049f)
        if (config.isNoComputer()) {
            enterApp();
            return;
        }

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
        Button back = findViewById(R.id.pairing_back_button);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }
}
