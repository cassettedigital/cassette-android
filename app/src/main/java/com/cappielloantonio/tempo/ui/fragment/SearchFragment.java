package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.databinding.FragmentSearchBinding;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.SearchViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;

@UnstableApi
public class SearchFragment extends Fragment implements ClickCallback {
    private static final String TAG = "SearchFragment";

    private FragmentSearchBinding bind;
    private MainActivity activity;
    private SearchViewModel searchViewModel;

    private SongHorizontalAdapter songAdapter;
    private com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter albumTiles;
    private com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter artistTiles;
    private com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter playlistTiles;
    private com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter stationTiles;
    private java.util.List<com.cappielloantonio.tempo.util.CassetteRadio.Station> stationResults = new java.util.ArrayList<>();
    private java.util.List<com.cappielloantonio.tempo.subsonic.models.Playlist> playlists = new java.util.ArrayList<>();

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int generation = 0;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentSearchBinding.inflate(inflater, container, false);
        searchViewModel = new ViewModelProvider(requireActivity()).get(SearchViewModel.class);

        worker.execute(com.cappielloantonio.tempo.util.CassetteSearch::warm);
        initResults();
        initField();
        renderRecent();

        // coming back from a result keeps what was typed and its results
        String held = searchViewModel.getQuery();
        if (held != null && !held.isEmpty()) {
            bind.searchField.setText(held);
            bind.searchField.setSelection(held.length());
        }
        return bind.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeMediaBrowser();
    }

    @Override
    public void onStop() {
        releaseMediaBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initResults() {
        bind.searchSongs.setLayoutManager(new LinearLayoutManager(requireContext()));
        songAdapter = new SongHorizontalAdapter(this, true, true, null);
        bind.searchSongs.setAdapter(songAdapter);

        albumTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter();
        bind.searchAlbums.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchAlbums.setAdapter(albumTiles);

        artistTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter();
        bind.searchArtists.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchArtists.setAdapter(artistTiles);

        playlistTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter();
        bind.searchPlaylists.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchPlaylists.setAdapter(playlistTiles);

        stationTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter();
        bind.searchStations.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchStations.setAdapter(stationTiles);

        // GENRES on the start page: two to a row; a tile opens the genre's page
        genreTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteGenreTileAdapter(genre -> {
            Bundle bundle = new Bundle();
            bundle.putString(GenrePageFragment.GENRE_NAME, genre.getName());
            Navigation.findNavController(requireView()).navigate(R.id.genrePageFragment, bundle);
        });
        bind.searchGenres.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2));
        bind.searchGenres.addItemDecoration(new com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration(
                2, Math.round(10 * getResources().getDisplayMetrics().density), false));
        bind.searchGenres.setAdapter(genreTiles);
        com.cappielloantonio.tempo.util.CassetteGenres.refreshIfDue(requireContext(), this::loadGenres);
        loadGenres();

        new com.cappielloantonio.tempo.repository.PlaylistRepository().getPinnedPlaylists()
                .observe(getViewLifecycleOwner(), list -> playlists = list != null ? list : new java.util.ArrayList<>());
    }

    private void initField() {
        bind.searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                runSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        bind.searchField.setOnEditorActionListener((v, actionId, event) -> {
            String query = bind.searchField.getText().toString().trim();
            if (!query.isEmpty()) searchViewModel.insertNewSearch(query);
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
    }

    private void renderRecent() {
        bind.searchRecentList.removeAllViews();
        java.util.List<String> recent = searchViewModel.getRecentSearchSuggestion();
        bind.searchRecentHeading.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        for (String suggestion : recent) {
            View view = LayoutInflater.from(requireContext()).inflate(R.layout.item_search_suggestion, bind.searchRecentList, false);
            ((ImageView) view.findViewById(R.id.search_suggestion_icon)).setImageResource(R.drawable.ic_history);
            ((TextView) view.findViewById(R.id.search_suggestion_title)).setText(suggestion);
            view.setOnClickListener(v -> {
                bind.searchField.setText(suggestion);
                bind.searchField.setSelection(suggestion.length());
            });
            view.findViewById(R.id.search_suggestion_delete_icon).setOnClickListener(v -> {
                searchViewModel.deleteRecentSearch(suggestion);
                renderRecent();
            });
            bind.searchRecentList.addView(view);
        }
    }

    private com.cappielloantonio.tempo.ui.adapter.CassetteGenreTileAdapter genreTiles;

    private void loadGenres() {
        if (bind == null) return;
        android.content.Context app = requireContext().getApplicationContext();
        worker.execute(() -> {
            java.util.List<com.cappielloantonio.tempo.util.CassetteGenres.Genre> all =
                    com.cappielloantonio.tempo.util.CassetteGenres.genres(app);
            main.post(() -> {
                if (bind == null) return;
                genreTiles.setItems(all);
                bind.searchGenresHeading.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    /** As you type: what is on this phone, off the main thread, newest typing wins. */
    private void runSearch(String query) {
        searchViewModel.setQuery(query);
        final int ticket = ++generation;
        if (query.trim().isEmpty()) {
            bind.searchResults.setVisibility(View.GONE);
            bind.searchEmpty.emptyStateTitle.setVisibility(View.GONE);
            bind.searchStart.setVisibility(View.VISIBLE);
            return;
        }
        bind.searchStart.setVisibility(View.GONE);
        worker.execute(() -> {
            com.cappielloantonio.tempo.util.CassetteSearch.Results results = com.cappielloantonio.tempo.util.CassetteSearch.search(query);
            java.util.List<com.cappielloantonio.tempo.util.CassetteRadio.Station> stations =
                    com.cappielloantonio.tempo.util.CassetteRadio.search(activity.getApplicationContext(), query);
            main.post(() -> {
                stationResults = stations;
                if (bind == null || ticket != generation) return;
                showResults(query, results);
            });
        });
    }

    private void showResults(String query, com.cappielloantonio.tempo.util.CassetteSearch.Results results) {
        String q = com.cappielloantonio.tempo.util.CassetteSearch.fold(query);
        java.util.List<com.cappielloantonio.tempo.subsonic.models.Playlist> matchedPlaylists = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.subsonic.models.Playlist p : playlists) {
            if (com.cappielloantonio.tempo.util.CassetteSearch.fold(p.getName()).contains(q)) matchedPlaylists.add(p);
        }
        boolean nothing = results.isEmpty() && matchedPlaylists.isEmpty() && stationResults.isEmpty();
        bind.searchResults.setVisibility(nothing ? View.GONE : View.VISIBLE);
        bind.searchEmpty.emptyStateTitle.setText(R.string.cassette_empty_search);
        bind.searchEmpty.emptyStateTitle.setVisibility(nothing ? View.VISIBLE : View.GONE);
        if (nothing) return;

        // the top card: an artist whose name starts with what was typed, else the first album
        com.cappielloantonio.tempo.subsonic.models.ArtistID3 leadArtist = null;
        for (com.cappielloantonio.tempo.subsonic.models.ArtistID3 a : results.getArtists()) {
            if (com.cappielloantonio.tempo.util.CassetteSearch.fold(a.getName()).startsWith(q)) { leadArtist = a; break; }
        }
        com.cappielloantonio.tempo.subsonic.models.AlbumID3 leadAlbum = results.getAlbums().isEmpty() ? null : results.getAlbums().get(0);
        bind.searchTopCard.setVisibility(leadArtist != null || leadAlbum != null ? View.VISIBLE : View.GONE);
        bind.searchSongsHeading.setVisibility(View.GONE);
        bind.searchTopGlow.setRecipe(com.cappielloantonio.tempo.ui.view.CoverGlowView.TOP_RESULT);
        if (leadArtist != null) {
            final com.cappielloantonio.tempo.subsonic.models.ArtistID3 artist = leadArtist;
            String cover = null;
            for (com.cappielloantonio.tempo.subsonic.models.AlbumID3 a : results.getAlbums()) if (artist.getId().equals(a.getArtistId())) { cover = a.getCoverArtId(); break; }
            bind.searchTopKicker.setText(R.string.cassette_kicker_artist);
            bind.searchTopTitle.setText(artist.getName() != null ? artist.getName().toUpperCase(java.util.Locale.getDefault()) : "");
            CustomGlideRequest.Builder.from(requireContext(), artist.getCoverArtId(), CustomGlideRequest.ResourceType.Artist).build().into(bind.searchTopCover);
            bind.searchTopGlow.setCover(cover, CustomGlideRequest.ResourceType.Album);
            bind.searchTopCard.setOnClickListener(v -> openArtist(artist));
            bind.searchTopPlay.setOnClickListener(v -> {
                MediaManager.playArtist(mediaBrowserListenableFuture, artist.getId());
                activity.setBottomSheetInPeek(true);
            });
        } else if (leadAlbum != null) {
            final com.cappielloantonio.tempo.subsonic.models.AlbumID3 album = leadAlbum;
            bind.searchTopKicker.setText(R.string.cassette_kicker_album);
            bind.searchTopTitle.setText(album.getName() != null ? album.getName().toUpperCase(java.util.Locale.getDefault()) : "");
            CustomGlideRequest.Builder.from(requireContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album).build().into(bind.searchTopCover);
            bind.searchTopGlow.setCover(album.getCoverArtId(), CustomGlideRequest.ResourceType.Album);
            bind.searchTopCard.setOnClickListener(v -> openAlbum(album));
            bind.searchTopPlay.setOnClickListener(v -> {
                MediaManager.playAlbum(mediaBrowserListenableFuture, album.getId(), false);
                activity.setBottomSheetInPeek(true);
            });
        }

        songAdapter.setItems(results.getSongs());
        bind.searchSongsHeading.setVisibility(results.getSongs().isEmpty() ? View.GONE : View.VISIBLE);

        java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> albums = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.subsonic.models.AlbumID3 album : results.getAlbums()) {
            albums.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(album.getName(), album.getArtist(),
                    album.getCoverArtId(), CustomGlideRequest.ResourceType.Album, false,
                    () -> openAlbum(album),
                    () -> {
                        MediaManager.playAlbum(mediaBrowserListenableFuture, album.getId(), false);
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        albumTiles.setItems(albums);
        bind.searchAlbumsHeading.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);

        java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> artists = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.subsonic.models.ArtistID3 artist : results.getArtists()) {
            artists.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(artist.getName(), null,
                    artist.getCoverArtId(), CustomGlideRequest.ResourceType.Artist, true,
                    () -> openArtist(artist),
                    () -> {
                        MediaManager.playArtist(mediaBrowserListenableFuture, artist.getId());
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        artistTiles.setItems(artists);
        bind.searchArtistsHeading.setVisibility(artists.isEmpty() ? View.GONE : View.VISIBLE);

        java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> lists = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.subsonic.models.Playlist playlist : matchedPlaylists) {
            lists.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(playlist.getName(), null,
                    playlist.getCoverArtId(), CustomGlideRequest.ResourceType.Playlist, false,
                    () -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlist);
                        Navigation.findNavController(requireView()).navigate(R.id.playlistPageFragment, bundle);
                    },
                    () -> new com.cappielloantonio.tempo.repository.PlaylistRepository().getPlaylistSongs(playlist.getId())
                            .observe(getViewLifecycleOwner(), songs -> {
                                if (songs == null || songs.isEmpty()) return;
                                MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                                com.cappielloantonio.tempo.repository.PlaylistRepository.notePlayed(playlist.getId());
                                activity.setBottomSheetInPeek(true);
                            })));
        }
        playlistTiles.setItems(lists);
        bind.searchPlaylistsHeading.setVisibility(lists.isEmpty() ? View.GONE : View.VISIBLE);

        // STATIONS: the best five on the whole dial, under the music
        java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> stations = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.util.CassetteRadio.Station station : stationResults) {
            Runnable tune = () -> {
                MediaManager.startRadio(mediaBrowserListenableFuture, station.toInternetRadioStation());
                activity.setBottomSheetInPeek(true);
            };
            stations.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(station.getName(), station.getLabel(),
                    station.getImage(), CustomGlideRequest.ResourceType.Radio, false, tune, tune));
        }
        stationTiles.setItems(stations);
        bind.searchStationsHeading.setVisibility(stations.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openAlbum(com.cappielloantonio.tempo.subsonic.models.AlbumID3 album) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(Constants.ALBUM_OBJECT, album);
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    private void openArtist(com.cappielloantonio.tempo.subsonic.models.ArtistID3 artist) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
        Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }
}
