package com.cappielloantonio.tempo.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.cappielloantonio.tempo.BuildConfig;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.interfaces.DialogClickCallback;
import com.cappielloantonio.tempo.interfaces.ScanCallback;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.DeleteDownloadStorageDialog;
import com.cappielloantonio.tempo.ui.dialog.DownloadStorageDialog;
import com.cappielloantonio.tempo.ui.dialog.StarredSyncDialog;
import com.cappielloantonio.tempo.ui.dialog.StreamingCacheStorageDialog;
import com.cappielloantonio.tempo.util.CassetteDiagnostics;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.UIUtil;
import com.cappielloantonio.tempo.viewmodel.SettingViewModel;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String TAG = "SettingsFragment";

    private MainActivity activity;
    private SettingViewModel settingViewModel;

    private ActivityResultLauncher<Intent> someActivityResultLauncher;

    // cassette: the lean settings are nested PreferenceScreens (Account /
    // Playback / Storage / About), iOS-style drill-in. androidx does not
    // navigate nested screens on its own, so we swap the shown screen in place
    // and keep a back callback to return to the root.
    private androidx.preference.PreferenceScreen rootScreen;
    private androidx.activity.OnBackPressedCallback backToRoot;
    private com.google.android.material.appbar.MaterialToolbar settingsToolbar;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        someActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        View prefView = super.onCreateView(inflater, container, savedInstanceState);
        settingViewModel = new ViewModelProvider(requireActivity()).get(SettingViewModel.class);

        if (prefView != null) {
            getListView().setPadding(0, 0, 0, (int) getResources().getDimension(R.dimen.global_padding_bottom));
        }

        // cassette: wrap the preference list with a centered-title header so
        // Settings reads like the rest of the app (Home / list pages). The back
        // arrow returns to a sub-screen's parent or leaves Settings.
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        settingsToolbar = (com.google.android.material.appbar.MaterialToolbar)
                inflater.inflate(R.layout.cs_settings_header, root, false);
        settingsToolbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        root.addView(settingsToolbar);
        if (prefView != null) {
            root.addView(prefView, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(false);
        activity.setBottomSheetVisibility(false);
    }

    @Override
    public void onResume() {
        super.onResume();

        // cassette: resume at the root screen so the setup below (which uses
        // findPreference against the shown screen) always resolves every kept
        // control, and back is disarmed.
        if (rootScreen != null && getPreferenceScreen() != rootScreen) {
            setPreferenceScreen(rootScreen);
        }
        if (settingsToolbar != null) settingsToolbar.setTitle(R.string.menu_settings_button);
        if (backToRoot != null) backToRoot.setEnabled(false);

        // cassette: only the kept settings are wired now. Scan, sync-starred,
        // keep-screen-on and the theme handler went with the pruned controls.
        checkEqualizer();
        checkCacheStorage();
        checkStorage();
        checkStreamingUi();

        setStreamingCacheSize();
        setAppLanguage();
        setVersion();
        setDownloadQualityInfo();

        actionLogout();
        actionAppearance();
        fillAnswers();
        actionChangeStreamingCacheStorage();
        actionChangeDownloadStorage();
        actionDeleteDownloadStorage();
        actionReportProblem();
    }

    @Override
    public void onStop() {
        super.onStop();
        activity.setBottomSheetVisibility(true);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.global_preferences, rootKey);
        rootScreen = getPreferenceScreen();
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull Preference preference) {
        // cassette: drill into a nested screen in place, and arm the back
        // callback so the system back returns to the root instead of leaving
        // Settings. findPreference (used to wire the sub-screen controls)
        // searches the whole tree, so listeners survive the swap.
        if (preference instanceof androidx.preference.PreferenceScreen) {
            androidx.preference.PreferenceScreen sub = (androidx.preference.PreferenceScreen) preference;
            setPreferenceScreen(sub);
            if (settingsToolbar != null) settingsToolbar.setTitle(sub.getTitle());
            if (backToRoot != null) backToRoot.setEnabled(true);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // cassette: no hairlines between rows or groups (founder, 2026-10-21)
        setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        setDividerHeight(0);
        backToRoot = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (rootScreen != null && getPreferenceScreen() != rootScreen) {
                    setPreferenceScreen(rootScreen);
                    if (settingsToolbar != null) settingsToolbar.setTitle(R.string.menu_settings_button);
                    setEnabled(false);
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backToRoot);
    }

    private void checkEqualizer() {
        Preference equalizer = findPreference("equalizer");

        if (equalizer == null) return;

        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);

        if ((intent.resolveActivity(requireActivity().getPackageManager()) != null)) {
            equalizer.setOnPreferenceClickListener(preference -> {
                someActivityResultLauncher.launch(intent);
                return true;
            });
        } else {
            equalizer.setVisible(false);
        }
    }

    private void checkCacheStorage() {
        Preference storage = findPreference("streaming_cache_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                storage.setSummary(Preferences.getDownloadStoragePreference() == 0 ? R.string.download_storage_internal_dialog_negative_button : R.string.download_storage_external_dialog_positive_button);
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    private void checkStorage() {
        Preference storage = findPreference("download_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                storage.setSummary(Preferences.getDownloadStoragePreference() == 0 ? R.string.download_storage_internal_dialog_negative_button : R.string.download_storage_external_dialog_positive_button);
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    // cassette: hide the streaming-cache controls while streaming is gated off
    // in the shipped build (BuildConfig.STREAMING_UI_ENABLED = false). Mirrors
    // the checkEqualizer()/checkStorage() visibility pattern. Called last in
    // onResume so this force-hide wins over checkCacheStorage()'s volume logic.
    private void checkStreamingUi() {
        if (BuildConfig.STREAMING_UI_ENABLED) return;

        Preference cacheSize = findPreference("streaming_cache_size");
        if (cacheSize != null) cacheSize.setVisible(false);

        Preference cacheStorage = findPreference("streaming_cache_storage");
        if (cacheStorage != null) cacheStorage.setVisible(false);
    }

    private void setStreamingCacheSize() {
        ListPreference streamingCachePreference = findPreference("streaming_cache_size");

        if (streamingCachePreference != null) {
            streamingCachePreference.setSummaryProvider(new Preference.SummaryProvider<ListPreference>() {
                @Nullable
                @Override
                public CharSequence provideSummary(@NonNull ListPreference preference) {
                    CharSequence entry = preference.getEntry();

                    if (entry == null) return null;

                    long currentSizeMb = DownloadUtil.getStreamingCacheSize(requireActivity()) / (1024 * 1024);

                    return getString(R.string.settings_summary_streaming_cache_size, entry, String.valueOf(currentSizeMb));
                }
            });
        }
    }

    private void setAppLanguage() {
        ListPreference localePref = (ListPreference) findPreference("language");

        Map<String, String> locales = UIUtil.getLangPreferenceDropdownEntries(requireContext());

        CharSequence[] entries = locales.keySet().toArray(new CharSequence[locales.size()]);
        CharSequence[] entryValues = locales.values().toArray(new CharSequence[locales.size()]);

        localePref.setEntries(entries);
        localePref.setEntryValues(entryValues);

        localePref.setDefaultValue(entryValues[0]);
        localePref.setSummary(Locale.forLanguageTag(localePref.getValue()).getDisplayLanguage());

        localePref.setOnPreferenceChangeListener((preference, newValue) -> {
            LocaleListCompat appLocale = LocaleListCompat.forLanguageTags((String) newValue);
            AppCompatDelegate.setApplicationLocales(appLocale);
            return true;
        });
    }

    private void setVersion() {
        findPreference("version").setSummary(BuildConfig.VERSION_NAME);
    }

    // cassette: the download tier is account-sourced; show it read-only under
    // Playback so the screen reflects the account rather than fighting it.
    private void setDownloadQualityInfo() {
        Preference pref = findPreference("download_quality_info");
        if (pref == null) return;
        String tier = Preferences.getCassetteDownloadQuality();
        int label;
        switch (tier) {
            case "lossless": label = R.string.settings_download_quality_lossless; break;
            case "efficient": label = R.string.settings_download_quality_efficient; break;
            default: label = R.string.settings_download_quality_high; break;
        }
        pref.setSummary(getString(label));
    }

    /**
     * cassette: the root answers on sight (iPhone e080d4c0): who you are, your
     * computer and its state in one word, what is on this phone. The computer's
     * heartbeat is asked once as Settings opens, never in the background.
     */
    private void fillAnswers() {
        String name = Preferences.getCassetteAccountName();
        String email = Preferences.getCassetteAccountEmail();
        Preference account = findPreference("screen_account");
        if (account != null) {
            account.setTitle(name != null && !name.isEmpty() ? name : getString(R.string.settings_screen_account));
            account.setSummary(email);
        }
        Preference nameRow = findPreference("account_name");
        if (nameRow != null) nameRow.setSummary(name);
        Preference emailRow = findPreference("account_email");
        if (emailRow != null) emailRow.setSummary(email);

        Preference help = findPreference("help");
        // THIS PHONE: what is here and what it weighs, biggest first, and what is kept off
        Preference phoneAlbums = findPreference("phone_albums");
        if (phoneAlbums != null) phoneAlbums.setOnPreferenceClickListener(p -> {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.thisPhoneFragment);
            return true;
        });
        if (help != null) help.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://cassette.digital/help/your-android")));
            return true;
        });
        Preference credit = findPreference("credit");
        if (credit != null) credit.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/CappielloAntonio/tempo")));
            return true;
        });
        androidx.preference.SwitchPreferenceCompat crash = findPreference("cassette_diagnostics_consent");
        if (crash != null) {
            crash.setChecked(Preferences.getCassetteDiagnosticsConsent());
            crash.setOnPreferenceChangeListener((p, value) -> {
                boolean on = Boolean.TRUE.equals(value);
                String bearer = Preferences.getCassetteBearerToken();
                if (bearer != null) new Thread(() -> {
                    try {
                        com.cappielloantonio.tempo.util.CassetteSyncApi.setDiagnosticsConsent(bearer, on);
                    } catch (Exception e) {
                        android.util.Log.w("SettingsFragment", "diagnostics consent failed", e);
                    }
                }, "cassette-diagnostics-consent").start();
                return true;
            });
        }

        // YOUR COMPUTER: its page (name, state, how much is here, what's new)
        Preference computer = findPreference("computer_row");
        if (computer != null) computer.setOnPreferenceClickListener(p -> {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.computerPageFragment);
            return true;
        });

        Preference resync = findPreference("resync");
        // no Resync until there is a computer (iPhone c652049f)
        if (resync != null) resync.setVisible(!com.cappielloantonio.tempo.util.CassetteComputerLogin.waitingForComputer());
        if (resync != null) resync.setOnPreferenceClickListener(p -> {
            com.cappielloantonio.tempo.service.CassetteSyncEngine.kick(requireContext());
            android.widget.Toast.makeText(requireContext(), R.string.cassette_resync_started, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });

        paintComputer();
        com.cappielloantonio.tempo.util.CassetteComputer.refresh(summary -> {
            if (isAdded()) paintComputer();
        });

        // THIS PHONE: how many records, and how much room the music takes
        new Thread(() -> {
            java.util.List<com.cappielloantonio.tempo.model.Download> songs =
                    com.cappielloantonio.tempo.database.AppDatabase.getInstance().downloadDao().getAllDownloadedSync();
            java.util.Set<String> albums = new java.util.HashSet<>();
            for (com.cappielloantonio.tempo.model.Download d : songs) if (d.getAlbumId() != null) albums.add(d.getAlbumId());
            long bytes = 0;
            java.io.File dir = com.cappielloantonio.tempo.util.DownloadUtil.getDownloadDirectory(requireContext());
            if (dir != null) bytes = folderBytes(dir);
            final int albumCount = albums.size();
            final long size = bytes;
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                androidx.preference.PreferenceCategory phone = findPreference("cat_phone");
                if (phone != null) phone.setTitle(com.cappielloantonio.tempo.util.CassetteType.twoVoiceText(requireContext(),
                        getString(R.string.cassette_this_phone),
                        getResources().getQuantityString(R.plurals.cassette_album_count, albumCount, albumCount),
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_ink2), 18));
                Preference music = findPreference("phone_music");
                if (music != null) music.setSummary(android.text.format.Formatter.formatShortFileSize(requireContext(), size));
            });
        }, "cassette-this-phone").start();
    }

    private static long folderBytes(java.io.File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) total += folderBytes(k);
        return total;
    }

    /** YOUR COMPUTER *in sync*: the name, one true line, and the state in one word. */
    private void paintComputer() {
        androidx.preference.PreferenceCategory cat = findPreference("cat_computer");
        Preference row = findPreference("computer_row");
        if (cat == null || row == null) return;
        String state = com.cappielloantonio.tempo.util.CassetteComputer.state();
        cat.setTitle(com.cappielloantonio.tempo.util.CassetteType.twoVoiceText(requireContext(),
                getString(R.string.cassette_your_computer), state,
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_ink2), 18));
        row.setTitle(com.cappielloantonio.tempo.util.CassetteComputer.name());
        com.cappielloantonio.tempo.util.CassetteSyncStatus.Snapshot sync = com.cappielloantonio.tempo.util.CassetteSyncStatus.live().getValue();
        row.setSummary(sync != null && sync.active ? sync.detail() : com.cappielloantonio.tempo.util.CassetteComputer.syncedLine());
    }

    private void actionAppearance() {
        androidx.preference.ListPreference theme = findPreference("theme");
        if (theme == null) return;
        theme.setOnPreferenceChangeListener((preference, value) -> {
            com.cappielloantonio.tempo.helper.ThemeHelper.applyTheme(String.valueOf(value));
            return true;
        });
    }

    private void actionLogout() {
        // cassette (BUG-937): Sign out asks about the music on the phone, ends this
        // phone's sign-in on the cloud and forgets the account, the iPhone's way.
        // Delete Account: on the website's Account page, in the danger colour
        Preference delete = findPreference("delete_account");
        if (delete != null) {
            android.text.SpannableString title = new android.text.SpannableString(getString(R.string.cassette_delete_account));
            title.setSpan(new android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_danger)),
                    0, title.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Preference ignores a title with the same words (TextUtils.equals drops spans): clear it first
            delete.setTitle(null);
            delete.setTitle(title);
            delete.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://cassette.digital/settings")));
                return true;
            });
        }

        findPreference("logout").setOnPreferenceClickListener(preference -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.cassette_sign_out_title, com.cappielloantonio.tempo.util.CassetteSession.accountName()))
                    .setMessage(R.string.cassette_sign_out_message)
                    .setPositiveButton(R.string.cassette_sign_out_keep, (d, w) -> signOut(false))
                    .setNeutralButton(R.string.cassette_sign_out_remove, (d, w) -> signOut(true))
                    .setNegativeButton(R.string.cassette_cancel, null)
                    .show();
            return true;
        });
    }

    private void signOut(boolean removeMusic) {
        com.cappielloantonio.tempo.util.CassetteSession.signOut(requireContext(), removeMusic, () -> {
            if (activity != null) activity.quit();
        });
    }

    private void actionChangeStreamingCacheStorage() {
        findPreference("streaming_cache_storage").setOnPreferenceClickListener(preference -> {
            StreamingCacheStorageDialog dialog = new StreamingCacheStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_external_dialog_positive_button);
                }

                @Override
                public void onNegativeClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_internal_dialog_negative_button);
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionChangeDownloadStorage() {
        findPreference("download_storage").setOnPreferenceClickListener(preference -> {
            DownloadStorageDialog dialog = new DownloadStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_external_dialog_positive_button);
                }

                @Override
                public void onNegativeClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_internal_dialog_negative_button);
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionDeleteDownloadStorage() {
        findPreference("delete_download_storage").setOnPreferenceClickListener(preference -> {
            DeleteDownloadStorageDialog dialog = new DeleteDownloadStorageDialog();
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionReportProblem() {
        Preference report = findPreference("report_problem");
        if (report == null) return;

        report.setOnPreferenceClickListener(preference -> {
            // cassette: feedback now rides the diagnostics spine as
            // report_type=user_feedback (not email). Snapshot the current screen
            // first (so the dialog doesn't cover it), then prompt for the message.
            captureScreenshot(screenshotPng -> promptReportProblem(screenshotPng));
            return true;
        });
    }

    /**
     * Show the free-text "Report a problem" dialog and POST it to the diagnostics
     * spine as user_feedback (always allowed — the opt-out only gates auto crash
     * reports). The optional PNG snapshot rides along as a screenshot attachment.
     */
    private void promptReportProblem(@Nullable byte[] screenshotPng) {
        if (!isAdded()) return;

        EditText input = new EditText(requireActivity());
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.report_problem_hint);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout wrapper = new FrameLayout(requireActivity());
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.settings_report_problem_title)
                .setMessage(R.string.report_problem_dialog_message)
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.report_problem_send, (dialog, which) -> {
                    String message = input.getText() != null ? input.getText().toString().trim() : "";
                    if (message.isEmpty()) {
                        Toast.makeText(requireActivity(), R.string.report_problem_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CassetteDiagnostics.submitUserFeedback(requireActivity().getApplicationContext(), message, screenshotPng);
                    Toast.makeText(requireActivity(), R.string.report_problem_sent, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Snapshot the current window into PNG bytes for a screenshot attachment.
     * PixelCopy (API 26+) is the correct path — it captures hardware-accelerated
     * and Surface content the software {@code View.draw} path misses. On API 24-25
     * (and on any failure) we fall back to drawing the decor view, and ultimately
     * to no screenshot (the feedback message still ships on its own).
     */
    private void captureScreenshot(@NonNull java.util.function.Consumer<byte[]> onResult) {
        Activity act = getActivity();
        if (act == null || act.getWindow() == null) {
            onResult.accept(null);
            return;
        }
        View decor = act.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) {
            onResult.accept(null);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                PixelCopy.request(act.getWindow(), bitmap, copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult.accept(toPng(bitmap));
                    } else {
                        onResult.accept(drawToPng(decor));
                    }
                }, new Handler(Looper.getMainLooper()));
                return;
            } catch (Throwable t) {
                // IllegalArgumentException if the window isn't ready — fall through.
            }
        }
        onResult.accept(drawToPng(decor));
    }

    /** Software fallback: render the view hierarchy to a bitmap (no Surface content). */
    @Nullable
    private static byte[] drawToPng(View view) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            return toPng(bitmap);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static byte[] toPng(Bitmap bitmap) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

}
