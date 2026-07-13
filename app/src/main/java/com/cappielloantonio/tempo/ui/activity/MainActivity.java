package com.cappielloantonio.tempo.ui.activity;

import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.broadcast.receiver.ConnectivityStatusBroadcastReceiver;
import com.cappielloantonio.tempo.databinding.ActivityMainBinding;
import com.cappielloantonio.tempo.github.utils.UpdateUtil;
import com.cappielloantonio.tempo.service.CassetteSyncEngine;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.ui.activity.base.BaseActivity;
import com.cappielloantonio.tempo.ui.dialog.ConnectionAlertDialog;
import com.cappielloantonio.tempo.ui.dialog.GithubTempoUpdateDialog;
import com.cappielloantonio.tempo.ui.dialog.ServerUnreachableDialog;
import com.cappielloantonio.tempo.ui.fragment.PlayerBottomSheetFragment;
import com.cappielloantonio.tempo.util.Cassette;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.MainViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.color.DynamicColors;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

@UnstableApi
public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivityLogs";

    public ActivityMainBinding bind;
    private MainViewModel mainViewModel;

    private FragmentManager fragmentManager;
    private NavHostFragment navHostFragment;
    private BottomNavigationView bottomNavigationView;
    public NavController navController;
    private BottomSheetBehavior bottomSheetBehavior;

    ConnectivityStatusBroadcastReceiver connectivityStatusBroadcastReceiver;

    // cassette: foreground intent polling (iOS cadence — once on foreground,
    // then every 30s while the app is up; each pass is cheap and idempotent).
    private final Handler cassetteSyncHandler = new Handler();
    private final Runnable cassetteSyncTick = new Runnable() {
        @Override
        public void run() {
            CassetteSyncEngine.kick(MainActivity.this);
            cassetteSyncHandler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        // cassette: NO Material You dynamic color — it re-themes the whole app
        // from the device wallpaper at runtime, stomping the brand palette
        // (the "washed out" gray-blue cards/buttons). Cassette IS the theme:
        // the account's cross-device skin (users.theme_id, via pairing) picks
        // the accent overlay instead.
        getTheme().applyStyle(Cassette.themeOverlayFor(Preferences.getCassetteThemeId()), true);
        refreshCassetteAccount();

        super.onCreate(savedInstanceState);

        bind = ActivityMainBinding.inflate(getLayoutInflater());
        View view = bind.getRoot();
        setContentView(view);

        mainViewModel = new ViewModelProvider(this).get(MainViewModel.class);

        connectivityStatusBroadcastReceiver = new ConnectivityStatusBroadcastReceiver(this);
        connectivityStatusReceiverManager(true);

        init();
        checkConnectionType();
        getOpenSubsonicExtensions();
        checkTempoUpdate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pingServer();
        cassetteSyncHandler.post(cassetteSyncTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        cassetteSyncHandler.removeCallbacks(cassetteSyncTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectivityStatusReceiverManager(false);
        bind = null;
    }

    @Override
    public void onBackPressed() {
        if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED)
            collapseBottomSheetDelayed();
        else
            super.onBackPressed();
    }

    public void init() {
        fragmentManager = getSupportFragmentManager();

        initBottomSheet();
        initNavigation();

        if (Preferences.getPassword() != null || (Preferences.getToken() != null && Preferences.getSalt() != null)) {
            goFromLogin();
        } else {
            goToLogin();
        }
    }

    // BOTTOM SHEET/NAVIGATION
    private void initBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.player_bottom_sheet));
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        fragmentManager.beginTransaction().replace(R.id.player_bottom_sheet, new PlayerBottomSheetFragment(), "PlayerBottomSheet").commit();

        checkBottomSheetAfterStateChanged();
    }

    public void setBottomSheetInPeek(Boolean isVisible) {
        if (isVisible) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }

    public void setBottomSheetVisibility(boolean visibility) {
        if (visibility) {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.player_bottom_sheet).setVisibility(View.GONE);
        }
    }

    private void checkBottomSheetAfterStateChanged() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        handler.postDelayed(runnable, 100);
    }

    public void collapseBottomSheetDelayed() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        handler.postDelayed(runnable, 100);
    }

    public void expandBottomSheet() {
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public void setBottomSheetDraggableState(Boolean isDraggable) {
        bottomSheetBehavior.setDraggable(isDraggable);
    }

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
            new BottomSheetBehavior.BottomSheetCallback() {
                int navigationHeight;

                @Override
                public void onStateChanged(@NonNull View view, int state) {
                    PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");

                    switch (state) {
                        case BottomSheetBehavior.STATE_HIDDEN:
                            resetMusicSession();
                            break;
                        case BottomSheetBehavior.STATE_COLLAPSED:
                            if (playerBottomSheetFragment != null)
                                playerBottomSheetFragment.goBackToFirstPage();
                            break;
                        case BottomSheetBehavior.STATE_SETTLING:
                        case BottomSheetBehavior.STATE_EXPANDED:
                        case BottomSheetBehavior.STATE_DRAGGING:
                        case BottomSheetBehavior.STATE_HALF_EXPANDED:
                            break;
                    }
                }

                @Override
                public void onSlide(@NonNull View view, float slideOffset) {
                    animateBottomSheet(slideOffset);
                    animateBottomNavigation(slideOffset, navigationHeight);
                }
            };

    private void animateBottomSheet(float slideOffset) {
        PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
        if (playerBottomSheetFragment != null) {
            float condensedSlideOffset = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.2f)) / 0.2f;
            playerBottomSheetFragment.getPlayerHeader().setAlpha(1 - condensedSlideOffset);
            playerBottomSheetFragment.getPlayerHeader().setVisibility(condensedSlideOffset > 0.99 ? View.GONE : View.VISIBLE);
        }
    }

    private void animateBottomNavigation(float slideOffset, int navigationHeight) {
        if (slideOffset < 0) return;

        if (navigationHeight == 0) {
            navigationHeight = bind.bottomNavigation.getHeight();
        }

        float slideY = navigationHeight - navigationHeight * (1 - slideOffset);

        bind.bottomNavigation.setTranslationY(slideY);
    }

    private void initNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        navHostFragment = (NavHostFragment) fragmentManager.findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        /*
         * In questo modo intercetto il cambio schermata tramite navbar e se il bottom sheet è aperto,
         * lo chiudo
         */
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED && (
                    destination.getId() == R.id.homeFragment ||
                            destination.getId() == R.id.libraryFragment ||
                            destination.getId() == R.id.downloadFragment)
            ) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });

        NavigationUI.setupWithNavController(bottomNavigationView, navController);
    }

    public void setBottomNavigationBarVisibility(boolean visibility) {
        if (visibility) {
            bottomNavigationView.setVisibility(View.VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
    }

    /**
     * cassette: refresh the account-side config (skin, hub drift) using the
     * stored pairing token. Best-effort and silent — a changed skin applies on
     * the next launch, so there's no mid-session re-theme jank.
     */
    private void refreshCassetteAccount() {
        String token = Preferences.getCassetteBearerToken();
        if (token == null || token.isEmpty()) return;

        new Thread(() -> {
            try {
                Cassette.PlayerConfig config = Cassette.fetchPlayerConfig(token);
                if (config.themeId != null) Preferences.setCassetteThemeId(config.themeId);
            } catch (Exception ignored) {
                // Offline or cloud unreachable — the cached skin stands.
            }
        }).start();
    }

    private void initService() {
        MediaManager.check(getMediaBrowserListenableFuture());

        getMediaBrowserListenableFuture().addListener(() -> {
            try {
                getMediaBrowserListenableFuture().get().addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                            setBottomSheetInPeek(true);
                        }
                    }
                });
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void goToLogin() {
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        setBottomNavigationBarVisibility(false);
        setBottomSheetVisibility(false);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.landingFragment) {
            navController.navigate(R.id.action_landingFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.settingsFragment) {
            navController.navigate(R.id.action_settingsFragment_to_loginFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.homeFragment) {
            navController.navigate(R.id.action_homeFragment_to_loginFragment);
        }
    }

    private void goToHome() {
        bottomNavigationView.setVisibility(View.VISIBLE);

        if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.landingFragment) {
            navController.navigate(R.id.action_landingFragment_to_homeFragment);
        } else if (Objects.requireNonNull(navController.getCurrentDestination()).getId() == R.id.loginFragment) {
            navController.navigate(R.id.action_loginFragment_to_homeFragment);
        }
    }

    public void goFromLogin() {
        setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        goToHome();
    }

    public void quit() {
        resetUserSession();
        resetMusicSession();
        resetViewModel();
        goToLogin();
    }

    private void resetUserSession() {
        Preferences.setServerId(null);
        Preferences.setSalt(null);
        Preferences.setToken(null);
        Preferences.setPassword(null);
        Preferences.setServer(null);
        Preferences.setLocalAddress(null);
        Preferences.setUser(null);

        // TODO Enter all settings to be reset
        Preferences.setOpenSubsonic(false);
        Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_100);
        Preferences.setSkipSilenceMode(false);
        Preferences.setDataSavingMode(false);
        Preferences.setStarredSyncEnabled(false);
    }

    private void resetMusicSession() {
        MediaManager.reset(getMediaBrowserListenableFuture());
    }

    private void hideMusicSession() {
        MediaManager.hide(getMediaBrowserListenableFuture());
    }

    private void resetViewModel() {
        this.getViewModelStore().clear();
    }

    // CONNECTION
    private void connectivityStatusReceiverManager(boolean isActive) {
        if (isActive) {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityStatusBroadcastReceiver, filter);
        } else {
            unregisterReceiver(connectivityStatusBroadcastReceiver);
        }
    }

    private void pingServer() {
        if (Preferences.getToken() == null) return;

        if (Preferences.isInUseServerAddressLocal()) {
            mainViewModel.ping().observe(this, subsonicResponse -> {
                if (subsonicResponse == null) {
                    Preferences.setServerSwitchableTimer();
                    Preferences.switchInUseServerAddress();
                    App.refreshSubsonicClient();
                    pingServer();
                } else {
                    Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                }
            });
        } else {
            if (Preferences.isServerSwitchable()) {
                Preferences.setServerSwitchableTimer();
                Preferences.switchInUseServerAddress();
                App.refreshSubsonicClient();
                pingServer();
            } else {
                mainViewModel.ping().observe(this, subsonicResponse -> {
                    if (subsonicResponse == null) {
                        // cassette: offline-first is a local player — a parked/
                        // unreachable hub is normal, not an error to nag about.
                        // The sync engine handles reachability (parking
                        // downloads); browse + playback never touch the hub.
                        if (Preferences.showServerUnreachableDialog() && !Preferences.isOfflineFirst()) {
                            ServerUnreachableDialog dialog = new ServerUnreachableDialog();
                            dialog.show(getSupportFragmentManager(), null);
                        }
                    } else {
                        Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                    }
                });
            }
        }
    }

    private void getOpenSubsonicExtensions() {
        if (Preferences.getToken() != null) {
            mainViewModel.getOpenSubsonicExtensions().observe(this, openSubsonicExtensions -> {
                if (openSubsonicExtensions != null) {
                    Preferences.setOpenSubsonicExtensions(openSubsonicExtensions);
                }
            });
        }
    }

    private void checkTempoUpdate() {
        // cassette: disabled. This is stock Tempo's GitHub release check — it
        // pops a "new version on GitHub / Support me" dialog pointing at the
        // upstream project, a branding leak. Cassette isn't distributed through
        // Tempo's releases, so there's nothing to check.
    }

    private void checkConnectionType() {
        if (Preferences.isWifiOnly()) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

            if (networkInfo != null && networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
                ConnectionAlertDialog dialog = new ConnectionAlertDialog();
                dialog.show(getSupportFragmentManager(), null);
            }
        }
    }
}