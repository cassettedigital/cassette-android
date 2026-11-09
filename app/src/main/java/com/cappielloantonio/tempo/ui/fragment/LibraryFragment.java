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
import androidx.recyclerview.widget.RecyclerView;

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
        // the dial watches the view's life (saved stations): build it again with the next view
        radio = null;
        genres = null;
        mixes = null;
    }

    private int albumCount = -1, artistCount = -1, playlistCount = -1, songsSeen = -1, stationCount = -1;
    private com.cappielloantonio.tempo.ui.adapter.CassetteLetteredAlbumAdapter letteredAlbums;
    private boolean showingLetters = false;
    public static final String CATEGORY_RADIO = "radio";
    public static final String CATEGORY_GENRES = "genres";
    private int genreCount = -1;
    private com.cappielloantonio.tempo.ui.adapter.CassetteGenreTileAdapter genres;

    private com.cappielloantonio.tempo.ui.adapter.CassetteMixListAdapter mixes;

    /** cassette: Home's made-for-you playlists, under your own (iPhone I-M8). */
    private com.cappielloantonio.tempo.ui.adapter.CassetteMixListAdapter mixAdapter() {
        if (mixes == null) {
            mixes = new com.cappielloantonio.tempo.ui.adapter.CassetteMixListAdapter(mix -> {
                Bundle bundle = new Bundle();
                bundle.putString(MixPageFragment.ARG_MIX_ID, mix.getId());
                Navigation.findNavController(requireView()).navigate(R.id.mixPageFragment, bundle);
            });
        }
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            java.util.List<com.cappielloantonio.tempo.util.CassetteHome.DrawnMix> found = new java.util.ArrayList<>();
            for (com.cappielloantonio.tempo.util.CassetteHome.DrawnRow row : com.cappielloantonio.tempo.util.CassetteHome.rowsNow(app)) {
                if ("mixes".equals(row.getKind())) for (com.cappielloantonio.tempo.util.CassetteHome.DrawnMix m : row.getMixes()) {
                    if (!m.getSongs().isEmpty()) found.add(m);
                }
            }
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> { if (mixes != null) mixes.setItems(found); });
        }, "cassette-library-mixes").start();
        return mixes;
    }

    /** cassette: Library > Genres, the genres of the music on this phone as soft tiles, two to a row. */
    private com.cappielloantonio.tempo.ui.adapter.CassetteGenreTileAdapter genreAdapter() {
        if (genres == null) {
            genres = new com.cappielloantonio.tempo.ui.adapter.CassetteGenreTileAdapter(genre -> {
                Bundle bundle = new Bundle();
                bundle.putString(GenrePageFragment.GENRE_NAME, genre.getName());
                Navigation.findNavController(requireView()).navigate(R.id.genrePageFragment, bundle);
            });
            com.cappielloantonio.tempo.util.CassetteGenres.refreshIfDue(requireContext(), this::loadGenres);
        }
        loadGenres();
        return genres;
    }

    private void loadGenres() {
        if (bind == null || genres == null) return;
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            java.util.List<com.cappielloantonio.tempo.util.CassetteGenres.Genre> all =
                    com.cappielloantonio.tempo.util.CassetteGenres.genres(app);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (bind == null || genres == null) return;
                genres.setItems(all);
                genreCount = all.size();
                refreshCountLine();
            });
        }, "cassette-genres").start();
    }
    private com.cappielloantonio.tempo.ui.adapter.CassetteRadioAdapter radio;
    private com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser> radioBrowser;

    // what's on at each station, read every 30 seconds while the dial is on screen
    private final android.os.Handler onNowHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable onNowTick = new Runnable() {
        @Override
        public void run() {
            if (bind == null || radio == null || !CATEGORY_RADIO.equals(Preferences.getLibraryLastCategory())) return;
            com.cappielloantonio.tempo.util.CassetteRadio.onNow(lines -> { if (radio != null) radio.setOnNow(lines); });
            onNowHandler.postDelayed(this, 30_000);
        }
    };

    private void startOnNow() {
        onNowHandler.removeCallbacks(onNowTick);
        onNowHandler.post(onNowTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        onNowHandler.removeCallbacks(onNowTick);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (radio != null && CATEGORY_RADIO.equals(Preferences.getLibraryLastCategory())) startOnNow();
    }

    /** cassette: the dial (Library > Radio): every station, playing from its own stream. */
    private com.cappielloantonio.tempo.ui.adapter.CassetteRadioAdapter radioAdapter() {
        if (radio != null) return radio;
        com.cappielloantonio.tempo.util.CassetteRadio.refresh(requireContext().getApplicationContext());
        radio = new com.cappielloantonio.tempo.ui.adapter.CassetteRadioAdapter(new com.cappielloantonio.tempo.ui.adapter.CassetteRadioAdapter.Listener() {
            @Override
            public void onOpen(com.cappielloantonio.tempo.util.CassetteRadio.Station station) {
                StationPageFragment.open(LibraryFragment.this, station.getSlug());
            }

            @Override
            public void onTune(com.cappielloantonio.tempo.util.CassetteRadio.Station station) {
                if (radioBrowser == null) {
                    radioBrowser = new androidx.media3.session.MediaBrowser.Builder(requireContext(),
                            new androidx.media3.session.SessionToken(requireContext(),
                                    new android.content.ComponentName(requireContext(), com.cappielloantonio.tempo.service.MediaService.class))).buildAsync();
                }
                com.cappielloantonio.tempo.service.MediaManager.startRadio(radioBrowser, station.toInternetRadioStation());
                radio.setOnAir("cassette-radio-" + station.getSlug());
                activity.setBottomSheetInPeek(true);
            }

            @Override
            public void onMenu(View anchor, com.cappielloantonio.tempo.util.CassetteRadio.Station station) {
                PopupMenu popup = new PopupMenu(new android.view.ContextThemeWrapper(requireContext(), R.style.Cassette_NeutralTintContext), anchor);
                popup.getMenu().add(0, 1, 0, R.string.cassette_play);
                boolean saved = com.cappielloantonio.tempo.util.CassetteRadioSaved.isSaved(requireContext(), station.getSlug());
                popup.getMenu().add(0, 4, 1, saved ? R.string.cassette_remove_from_your_stations : R.string.cassette_save);
                if (station.site() != null) popup.getMenu().add(0, 2, 2, getString(R.string.cassette_go_to_site, station.site()));
                if (station.getSupportUrl() != null) popup.getMenu().add(0, 3, 3, getString(R.string.cassette_support_station, station.getName()));
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) onTune(station);
                    else if (item.getItemId() == 4) {
                        android.content.Context app = requireContext().getApplicationContext();
                        com.cappielloantonio.tempo.util.CassetteRadioSaved.set(requireContext(), station.getSlug(), !saved, ok -> {
                            if (!ok) android.widget.Toast.makeText(app, R.string.cassette_save_failed, android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }
                    else if (item.getItemId() == 2) startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(station.getHomepage())));
                    else if (item.getItemId() == 3) startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(station.getSupportUrl())));
                    return true;
                });
                popup.show();
            }
        });
        startOnNow();
        com.cappielloantonio.tempo.util.CassetteRadioSaved.refresh(requireContext());
        com.cappielloantonio.tempo.util.CassetteRadioSaved.saved(requireContext()).observe(getViewLifecycleOwner(),
                slugs -> { if (radio != null) radio.setSaved(slugs, getString(R.string.cassette_your_stations)); });
        new Thread(() -> {
            java.util.List<com.cappielloantonio.tempo.util.CassetteRadio.Station> stations =
                    com.cappielloantonio.tempo.util.CassetteRadio.stations(requireContext().getApplicationContext());
            requireActivity().runOnUiThread(() -> {
                if (bind == null) return;
                radio.setStations(stations);
                stationCount = stations.size();
                refreshCountLine();
            });
        }, "cassette-radio-dial").start();
        return radio;
    }

    /** "139 ALBUMS" over the list, or one plain title when there is nothing here. */
    private void refreshCountLine() {
        if (bind == null) return;
        String category = Preferences.getLibraryLastCategory();
        int count;
        int plural;
        int empty;
        if (CATEGORY_GENRES.equals(category)) {
            count = genreCount; plural = R.plurals.cassette_genre_count; empty = R.string.cassette_empty_phone;
        } else if (CATEGORY_RADIO.equals(category)) {
            count = stationCount; plural = R.plurals.cassette_station_count; empty = R.string.cassette_empty_stations;
        } else if (CATEGORY_ARTISTS.equals(category)) {
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
        letteredAlbums = new com.cappielloantonio.tempo.ui.adapter.CassetteLetteredAlbumAdapter(this);
        artistAdapter = new ArtistCatalogueAdapter(this);
        playlistAdapter = new PlaylistHorizontalAdapter(this);
    }

    private void initObservers() {
        albumCatalogueViewModel.loadAlbums();
        artistCatalogueViewModel.loadArtists();

        albumCatalogueViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            albumCount = albums != null ? albums.size() : -1;
            if (albums != null) {
                albumAdapter.setItems(albums);
                letteredAlbums.setItems(albums);
                boolean lettered = albums.size() > com.cappielloantonio.tempo.util.CassetteLetters.THRESHOLD;
                if (lettered != showingLetters && CATEGORY_ALBUMS.equals(Preferences.getLibraryLastCategory())) {
                    applyCategory(CATEGORY_ALBUMS);
                }
            }
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
                } else if (id == R.id.library_menu_radio) {
                    applyCategory(CATEGORY_RADIO);
                } else if (id == R.id.library_menu_genres) {
                    applyCategory(CATEGORY_GENRES);
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
            case CATEGORY_RADIO:
                pushPickerToToolbar(R.string.cassette_radio);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                applyGridSpacing(1);
                bind.libraryCatalogueRecyclerView.setAdapter(radioAdapter());
                startOnNow();
                break;
            case CATEGORY_GENRES: {
                pushPickerToToolbar(R.string.cassette_genres);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
                applyGridSpacing(1);
                bind.libraryCatalogueRecyclerView.addItemDecoration(new com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration(
                        2, Math.round(10 * getResources().getDisplayMetrics().density), false));
                bind.libraryCatalogueRecyclerView.setAdapter(genreAdapter());
                break;
            }
            case CATEGORY_PLAYLISTS:
                pushPickerToToolbar(R.string.library_title_playlist);
                bind.libraryCatalogueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                applyGridSpacing(1);
                bind.libraryCatalogueRecyclerView.setAdapter(new androidx.recyclerview.widget.ConcatAdapter(playlistAdapter, mixAdapter()));
                break;
            case CATEGORY_ALBUMS:
            default:
                category = CATEGORY_ALBUMS;
                pushPickerToToolbar(R.string.library_title_album);
                // past 60 records the grid files by letter, with the # to Z index (iPhone 42a38ecb)
                showingLetters = albumCount > com.cappielloantonio.tempo.util.CassetteLetters.THRESHOLD;
                GridLayoutManager grid = new GridLayoutManager(requireContext(), GRID_COLUMNS);
                if (showingLetters) grid.setSpanSizeLookup(letteredAlbums.spans(GRID_COLUMNS));
                bind.libraryCatalogueRecyclerView.setLayoutManager(grid);
                applyGridSpacing(showingLetters ? 1 : GRID_COLUMNS);
                if (showingLetters) {
                    bind.libraryCatalogueRecyclerView.addItemDecoration(new com.cappielloantonio.tempo.helper.recyclerview.CassetteLetteredGridSpacing(
                            GRID_COLUMNS, Math.round(16 * getResources().getDisplayMetrics().density)));
                }
                bind.libraryCatalogueRecyclerView.setAdapter(showingLetters ? letteredAlbums : albumAdapter);
                break;
        }

        Preferences.setLibraryLastCategory(category);
        boolean index = CATEGORY_ALBUMS.equals(category) && showingLetters;
        bind.libraryLetterIndex.setVisibility(index ? View.VISIBLE : View.GONE);
        int end = Math.round((index ? 30 : 16) * getResources().getDisplayMetrics().density);
        bind.libraryCatalogueRecyclerView.setPadding(bind.libraryCatalogueRecyclerView.getPaddingLeft(),
                bind.libraryCatalogueRecyclerView.getPaddingTop(), end, bind.libraryCatalogueRecyclerView.getPaddingBottom());
        bind.libraryLetterIndex.setOnLetter(letter -> {
            int at = letteredAlbums.positionFor(letter);
            RecyclerView.LayoutManager lm = bind.libraryCatalogueRecyclerView.getLayoutManager();
            if (at >= 0 && lm instanceof GridLayoutManager) ((GridLayoutManager) lm).scrollToPositionWithOffset(at, 0);
        });
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
