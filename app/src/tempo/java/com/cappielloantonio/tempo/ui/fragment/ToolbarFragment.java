package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentToolbarBinding;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.google.android.gms.cast.framework.CastButtonFactory;

@UnstableApi
public class ToolbarFragment extends Fragment {
    private static final String TAG = "ToolbarFragment";

    private FragmentToolbarBinding bind;
    private MainActivity activity;

    public ToolbarFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.main_page_menu, menu);
        CastButtonFactory.setUpMediaRouteButton(requireContext(), menu, R.id.media_route_menu_item);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentToolbarBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        // cassette: the account chip is the left-hand anchor on every screen
        // that carries this bar, the way the iOS app opens with the profile.
        bind.toolbarAvatar.setOnClickListener(v -> activity.navController.navigate(R.id.settingsFragment));
        bind.toolbarTitle.setText(titleForHost());

        return view;
    }

    /**
     * cassette: the bar names the screen you are on. Library replaces the name
     * with a centred picker (see setPicker), so it gets no left-hand title.
     */
    private String titleForHost() {
        Fragment host = getParentFragment();
        if (host instanceof HomeFragment) return getString(R.string.home_screen_title);
        if (host instanceof DownloadFragment) return getString(R.string.download_screen_title);
        return "";
    }

    /**
     * cassette: put a centred picker in the bar, iOS-style. Library uses it for
     * Albums / Artists / Playlists / Downloads, which used to be a 30sp heading
     * sitting in the content below the bar.
     */
    public void setPicker(CharSequence label, View.OnClickListener onClick) {
        if (bind == null) return;
        bind.toolbarPicker.setText(label);
        bind.toolbarPicker.setOnClickListener(onClick);
        bind.toolbarPicker.setVisibility(label == null ? View.GONE : View.VISIBLE);
        bind.toolbarTitle.setVisibility(label == null ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            activity.navController.navigate(R.id.searchFragment);
            return true;
        } else if (item.getItemId() == R.id.action_settings) {
            activity.navController.navigate(R.id.settingsFragment);
            return true;
        }

        return false;
    }
}