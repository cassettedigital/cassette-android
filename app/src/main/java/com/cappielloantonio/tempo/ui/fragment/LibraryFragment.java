package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentLibraryBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.interfaces.PlaylistCallback;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumCatalogueAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistCatalogueAdapter;
import com.cappielloantonio.tempo.ui.adapter.PlaylistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.AlbumCatalogueViewModel;
import com.cappielloantonio.tempo.viewmodel.ArtistCatalogueViewModel;
import com.cappielloantonio.tempo.viewmodel.PlaylistCatalogueViewModel;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.Objects;

/**
 * cassette: Library = the iOS pattern. One full catalogue (not sampler rows),
 * the title is a category dropdown (Albums / Artists / Playlists / Downloads),
 * and the screen reopens on the last category viewed.
 */
@UnstableApi
public class LibraryFragment extends Fragment implements ClickCallback {
    private static final String TAG = "LibraryFragment";

    public static final String CATEGORY_ALBUMS = "albums";
    public static final String CATEGORY_ARTISTS = "artists";
    public static final String CATEGORY_PLAYLISTS = "playlists";

    /**
     * cassette: iOS shows three columns on a phone (Settings.albumsGridSizeSetting,
     * default 3, user-adjustable 2 to 5). Android was hard-coded to two. Snapped
     * to iOS's default; the slider is not built here yet - see the parity ledger.
     */
    private static final int GRID_COLUMNS = 3;

    private FragmentLibraryBinding bind;
    private MainActivity activity;

    private AlbumCatalogueViewModel albumCatalogueViewModel;
    private ArtistCatalogueViewModel artistCatalogueViewModel;
    private PlaylistCatalogueViewModel playlistCatalogueViewModel;

    private AlbumCatalogueAdapter albumAdapter;
    private ArtistCatalogueAdapter artistAdapter;
    private PlaylistHorizontalAdapter playlistAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentLibraryBinding.inflate(inflater, container, false);

        albumCatalogueViewModel = new ViewModelProvider(requireActivity()).get(AlbumCatalogueViewModel.class);
        artistCatalogueViewModel = new ViewModelProvider(requireActivity()).get(ArtistCatalogueViewModel.class);
        playlistCatalogueViewModel = new ViewModelProvider(requireActivity()).get(PlaylistCatalogueViewModel.class);

        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initAppBar();
        initAdapters();
        initObservers();
        initCategorySwitcher();

        applyCategory(Preferences.getLibraryLastCategory());
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private int albumCount = -1, artistCount = -1, playlistCount = -1, songsSeen = -1;

    /** "139 ALBUMS" over the list, or one plain title when there is nothing here. */
    private void refreshCountLine() {
        if (bind == null) return;
        String category = Preferences.getLibraryLastCategory();
        int count;
        int plural;
        int empty;
        if (CATEGORY_ARTISTS.equals(category)) {
            count = artistCount; plural = R.plurals.cassette_artist_count; empty = R.string.cassette_empty_phone;
        } else if (CATEGORY_PLAYLISTS.equals(category)) {
            count = playlistCount; plural = R.plurals.cassette_playlist_count; empty = R.string.cassette_empty_playlists;
        } else {
            count = albumCount; plural = R.plurals.cassette_album_count; empty = R.string.cassette_empty_phone;
        }
        bind.libraryCountLine.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        if (count > 0) bind.libraryCountLine.setText(getResources().getQuantityString(plural, count, count));
        bind.libraryEmpty.emptyStateTitle.setText(empty);
        bind.libraryEmpty.emptyStateTitle.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private void initAppBar() {
        MaterialToolbar materialToolbar = bind.getRoot().findViewById(R.id.toolbar);

        activity.setSupportActionBar(materialToolbar);
        Objects.requireNonNull(materialToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
    }

    /**
     * cassette: the reachable library grid had NO item decoration, so covers
     * physically touched each other. iOS uses a 16pt gutter and a 16pt edge
     * inset (AlbumsCollectionVC). Added once here rather than per category, and
     * guarded so switching categories does not stack decorations.
     */
    private void applyGridSpacing(int spanCount) {
        if (bind == null) return;
        while (bind.libraryCatalogueRecyclerView.getItemDecorationCount() > 0) {
            bind.libraryCatalogueRecyclerView.removeItemDecorationAt(0);
        }
        if (spanCount > 1) {
            int gutter = Math.round(16 * getResources().getDisplayMetrics().density);
            bind.libraryCatalogueRecyclerView.addItemDecoration(
                    new com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration(spanCount, gutter, false));
        }
    }

    private void initAdapters() {
        albumAdapter = new AlbumCatalogueAdapter(this, true);
        artistAdapter = new ArtistCatalogueAdapter(this);
        playlistAdapter = new PlaylistHorizontalAdapter(this);
    }

    private void initObservers() {
        albumCatalogueViewModel.loadAlbums();
        artistCatalogueViewModel.loadArtists();

        albumCatalogueViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            if (albums != null) albumAdapter.setItems(albums);
            albumCount = albums != null ? albums.size() : -1;
            refreshCountLine();
        });
        artistCatalogueViewModel.getArtistList().observe(getViewLifecycleOwner(), artists -> {
            if (artists != null) artistAdapter.setItems(artists);
            artistCount = artists != null ? artists.size() : -1;
            refreshCountLine();
        });
        playlistCatalogueViewModel.getPlaylistList(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), playlists -> {
            if (playlists != null) playlistAdapter.setItems(playlists);
            playlistCount = playlists != null ? playlists.size() : -1;
            refreshCountLine();
        });

        // cassette: the grid fills in as music arrives on the phone, without a
        // relaunch (iPhone 42a38ecb): a change in the phone's songs reloads it.
        com.cappielloantonio.tempo.database.AppDatabase.getInstance().downloadDao().getAll()
                .observe(getViewLifecycleOwner(), songs -> {
                    int size = songs != null ? songs.size() : 0;
                    if (size == songsSeen) return;
                    boolean first = songsSeen < 0;
                    songsSeen = size;
                    if (first) return;
                    albumCatalogueViewModel.loadAlbums();
                    artistCatalogueViewModel.loadArtists();
                });
    }

    /**
     * cassette: the category picker now lives centred in the top bar, iOS's
     * nav-bar pattern, instead of being a 30sp heading in the content. Same
     * menu, same behaviour, one row of vertical space back.
     */
    private void pushPickerToToolbar(int labelRes) {
        Fragment toolbar = getChildFragmentManager().findFragmentById(R.id.toolbar_fragment);
        if (toolbar instanceof ToolbarFragment) {
            ((ToolbarFragment) toolbar).setPicker(getString(labelRes), this::showCategoryMenu);
        }
    }

    private void initCategorySwitcher() {
        bind.libraryCategoryTitle.setOnClickListener(this::showCategoryMenu);
    }

    private void showCategoryMenu(View v) {
        {
            // cassette: wrap the context so Material tints the popup with a
            // neutral (not the orange primary) — see Cassette.NeutralTintContext.
            android.content.Context popupContext = new android.view.ContextThemeWrapper(
                    requireContext(), R.style.Cassette_NeutralTintContext);
            PopupMenu popup = new PopupMenu(popupContext, v);
            popup.getMenuInflater().inflate(R.menu.library_category_menu, popup.getMenu());
            // cassette: "Downloads" is a manual-download concept, shown only in
            // streaming mode. In offline-first the library IS what is on the
            // phone, so a separate Downloads category is noise.
            if (popup.getMenu().findItem(R.id.library_menu_downloads) != null) {
                popup.getMenu().findItem(R.id.library_menu_downloads)
                        .setVisible(Preferences.isServerMode());
            }
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.library_menu_albums) {
                    applyCategory(CATEGORY_ALBUMS);
                } else if (id == R.id.library_menu_artists) {
                    applyCategory(CATEGORY_ARTISTS);
                } else if (id == R.id.library_menu_playlists) {
                    applyCategory(CATEGORY_PLAYLISTS);
                } else if (id == R.id.library_menu_downloads) {
                    // Downloads is its own screen, not a category: navigate,
                    // and keep the remembered category unchanged.
                    activity.navController.navigate(R.id.downloadFragment);
                }
                return true;
            });
            popup.show();
        }
    }

    private void applyCategory(String category) {
        if (bind == null) return;

        switch (category) {
            case CATEGORY_ARTISTS:
                pushPickerToToolbar(R.string.library_title_artist);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
                applyGridSpacing(GRID_COLUMNS);
                bind.libraryCatalogueRecyclerView.setAdapter(artistAdapter);
                break;
            case CATEGORY_PLAYLISTS:
                pushPickerToToolbar(R.string.library_title_playlist);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                applyGridSpacing(1);
                bind.libraryCatalogueRecyclerView.setAdapter(playlistAdapter);
                break;
            case CATEGORY_ALBUMS:
            default:
                category = CATEGORY_ALBUMS;
                pushPickerToToolbar(R.string.library_title_album);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
                applyGridSpacing(GRID_COLUMNS);
                bind.libraryCatalogueRecyclerView.setAdapter(albumAdapter);
                break;
        }

        Preferences.setLibraryLastCategory(category);
        refreshCountLine();
    }

    @Override
    public void onAlbumClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }

    @Override
    public void onArtistClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
    }

    @Override
    public void onArtistLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
    }

    @Override
    public void onPlaylistClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.playlistPageFragment, bundle);
    }

    @Override
    public void onPlaylistLongClick(Bundle bundle) {
        PlaylistEditorDialog dialog = new PlaylistEditorDialog(new PlaylistCallback() {
            @Override
            public void onDismiss() {
                // Playlist list refreshes via its LiveData observer.
            }
        });

        dialog.setArguments(bundle);
        dialog.show(activity.getSupportFragmentManager(), null);
    }
}
