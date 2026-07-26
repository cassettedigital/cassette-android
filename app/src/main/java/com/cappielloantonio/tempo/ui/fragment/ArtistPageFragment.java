package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentArtistPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.CassetteLocalId;
import com.cappielloantonio.tempo.util.CassetteNowPlaying;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.ArtistPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class ArtistPageFragment extends Fragment implements ClickCallback {
    /** Match @discovery/cassette-contract POPULAR_SONGS_COLLAPSED_LIMIT. */
    private static final int POPULAR_COLLAPSED_LIMIT = 5;
    /** Match @discovery/cassette-contract POPULAR_SONGS_TEASER_LIMIT. */
    private static final int POPULAR_TEASER_LIMIT = 20;
    private static final ExecutorService POPULAR_RANK_EXECUTOR = Executors.newSingleThreadExecutor();

    private FragmentArtistPageBinding bind;
    private MainActivity activity;
    private ArtistPageViewModel artistPageViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;
    private AlbumAdapter albumAdapter;

    private List<Child> librarySongs = Collections.emptyList();
    private List<Child> popularSongsFull = Collections.emptyList();
    private boolean popularExpanded = false;
    /** Bumps when library songs change so a late cloud rank can't paint a stale artist. */
    private int popularRankGeneration = 0;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.artist_page_menu, menu);
        MenuItem options = menu.findItem(R.id.action_artist_options);
        if (options != null && options.getIcon() != null) {
            options.getIcon().setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_artist_options) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artistPageViewModel.getArtist());
            Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentArtistPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        artistPageViewModel = new ViewModelProvider(requireActivity()).get(ArtistPageViewModel.class);

        init();
        initAppBar();
        initHeader();
        initMusicButton();
        initAlbumsView();
        initPopularView();

        return view;
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

    private void init() {
        ArtistID3 artist = requireArguments().getParcelable(Constants.ARTIST_OBJECT);
        artistPageViewModel.setArtist(artist);
        popularExpanded = false;
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        bind.animToolbar.setTitle("");
        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
    }

    private void initHeader() {
        ArtistID3 artist = artistPageViewModel.getArtist();
        if (artist == null || bind == null) return;

        bind.artistNameLabel.setText(artist.getName());

        String coverId = artist.getCoverArtId() != null ? artist.getCoverArtId() : artist.getId();
        CustomGlideRequest.Builder
                .from(requireContext(), coverId, CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(bind.artistCoverImageView);

        // Counts come from the album list (ArtistID3 has no songCount on the wire).
        if (artistPageViewModel.getAlbumList() != null) {
            artistPageViewModel.getAlbumList().observe(getViewLifecycleOwner(), this::refreshMetadata);
        }
    }

    private void refreshMetadata(List<AlbumID3> albums) {
        if (bind == null) return;

        int albumCount = albums != null ? albums.size() : 0;
        int songCount = 0;
        if (albums != null) {
            for (AlbumID3 album : albums) {
                Integer count = album.getSongCount();
                if (count != null) songCount += count;
            }
        }

        // Fall back to library song list size if album songCount was missing.
        if (songCount == 0 && librarySongs != null && !librarySongs.isEmpty()) {
            songCount = librarySongs.size();
        }

        List<String> parts = new ArrayList<>();
        if (albumCount > 0) {
            parts.add(getString(
                    albumCount == 1 ? R.string.artist_page_album_count_one : R.string.artist_page_album_count_other,
                    albumCount));
        }
        if (songCount > 0) {
            parts.add(getString(
                    songCount == 1 ? R.string.artist_page_song_count_one : R.string.artist_page_song_count_other,
                    songCount));
        }

        if (parts.isEmpty()) {
            bind.artistMetadataLabel.setVisibility(View.GONE);
            return;
        }

        String line = parts.size() == 1
                ? parts.get(0)
                : getString(R.string.artist_page_metadata_joiner, parts.get(0), parts.get(1));
        bind.artistMetadataLabel.setText(line);
        bind.artistMetadataLabel.setVisibility(View.VISIBLE);
    }

    private void initMusicButton() {
        if (artistPageViewModel.getArtistLibrarySongs() == null) return;

        artistPageViewModel.getArtistLibrarySongs().observe(getViewLifecycleOwner(), songs -> {
            librarySongs = songs != null ? songs : Collections.emptyList();
            if (bind == null) return;

            boolean hasSongs = !librarySongs.isEmpty();
            bind.artistPagePlayButton.setEnabled(hasSongs);
            bind.artistPageShuffleButton.setEnabled(hasSongs);
            bind.artistPageRepeatButton.setEnabled(hasSongs);

            // Album songCount can be null on some servers; refresh once library loads.
            if (artistPageViewModel.getAlbumList() != null) {
                refreshMetadata(artistPageViewModel.getAlbumList().getValue());
            }

            bind.artistPagePlayButton.setOnClickListener(v -> {
                if (librarySongs.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.artist_error_retrieving_tracks), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isThisArtistCurrent(librarySongs)) {
                    MediaManager.togglePlayPause(mediaBrowserListenableFuture);
                    activity.setBottomSheetInPeek(true);
                    return;
                }
                MediaManager.startQueue(mediaBrowserListenableFuture, librarySongs, 0);
                activity.setBottomSheetInPeek(true);
            });

            bind.artistPageShuffleButton.setOnClickListener(v -> {
                if (librarySongs.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.artist_error_retrieving_tracks), Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Child> shuffled = new ArrayList<>(librarySongs);
                Collections.shuffle(shuffled);
                MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
                activity.setBottomSheetInPeek(true);
            });

            bind.artistPageRepeatButton.setOnClickListener(v -> {
                if (librarySongs.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.artist_error_retrieving_tracks), Toast.LENGTH_SHORT).show();
                    return;
                }
                MediaManager.startQueue(mediaBrowserListenableFuture, librarySongs, 0);
                MediaManager.setRepeatMode(mediaBrowserListenableFuture, Player.REPEAT_MODE_ALL);
                activity.setBottomSheetInPeek(true);
            });

            refreshPlayButtonState();
        });

        CassetteNowPlaying.live().observe(getViewLifecycleOwner(), state -> {
            if (bind == null || state == null) return;
            if (songHorizontalAdapter != null) {
                songHorizontalAdapter.setNowPlaying(state.mediaId, state.isPlaying);
            }
            refreshPlayButtonState();
        });
    }

    private void refreshPlayButtonState() {
        if (bind == null) return;
        boolean playingThisArtist = CassetteNowPlaying.current().isPlaying && isThisArtistCurrent(librarySongs);
        bind.artistPagePlayButton.setImageResource(
                playingThisArtist ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private boolean isThisArtistCurrent(List<Child> songs) {
        String mediaId = CassetteNowPlaying.current().mediaId;
        if (mediaId == null || songs == null) return false;
        for (Child song : songs) {
            if (mediaId.equals(song.getId())) return true;
        }
        return false;
    }

    private void initAlbumsView() {
        bind.albumsRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.albumsRecyclerView.setHasFixedSize(true);

        albumAdapter = new AlbumAdapter(this);
        bind.albumsRecyclerView.setAdapter(albumAdapter);

        if (artistPageViewModel.getAlbumList() == null) return;
        artistPageViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;
            if (albums == null || albums.isEmpty()) {
                bind.artistPageAlbumsSector.setVisibility(View.GONE);
                return;
            }
            bind.artistPageAlbumsSector.setVisibility(View.VISIBLE);
            albumAdapter.setItems(albums);
        });
    }

    private void initPopularView() {
        bind.mostStreamedSongRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        String artistName = artistPageViewModel.getArtist() != null
                ? artistPageViewModel.getArtist().getName()
                : null;
        // Cover art; no artist line under each row (page already is that artist).
        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null, artistName);
        bind.mostStreamedSongRecyclerView.setAdapter(songHorizontalAdapter);

        bind.popularShowMoreButton.setOnClickListener(v -> {
            popularExpanded = true;
            renderPopularList();
        });

        // cassette: Popular is the artist's library songs, ranked by cloud
        // play counts (POST /api/sync/popular-rank) — same order as web/iOS.
        // Hold the sector until ranking lands so hub counts never flash then
        // re-rank. Offline falls back to hub playCount with the same sort.
        if (artistPageViewModel.getArtistLibrarySongs() == null) return;
        artistPageViewModel.getArtistLibrarySongs().observe(getViewLifecycleOwner(), songs -> {
            librarySongs = songs != null ? songs : Collections.emptyList();
            if (bind == null) return;
            if (librarySongs.isEmpty()) {
                popularSongsFull = Collections.emptyList();
                bind.artistPageTopSongsSector.setVisibility(View.GONE);
                return;
            }
            // Hold until ranked — mirrors web holding songs for fresh ranks.
            bind.artistPageTopSongsSector.setVisibility(View.GONE);
            final int generation = ++popularRankGeneration;
            final List<Child> snapshot = new ArrayList<>(librarySongs);
            POPULAR_RANK_EXECUTOR.execute(() -> {
                List<Child> ranked = rankPopularCloudOrLocal(snapshot);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (bind == null || generation != popularRankGeneration) return;
                    popularSongsFull = ranked;
                    if (popularSongsFull.isEmpty()) {
                        bind.artistPageTopSongsSector.setVisibility(View.GONE);
                        return;
                    }
                    bind.artistPageTopSongsSector.setVisibility(View.VISIBLE);
                    renderPopularList();
                });
            });
        });
    }

    /**
     * Cloud union counts via popular-rank; offline / unpaired → hub playCount
     * with the same comparator. Always capped at the shared teaser limit.
     */
    private static List<Child> rankPopularCloudOrLocal(List<Child> songs) {
        if (songs == null || songs.isEmpty()) return Collections.emptyList();
        Map<String, Child> byLocalId = new HashMap<>();
        List<CassetteSyncApi.PopularRankItem> inputs = new ArrayList<>(songs.size());
        for (Child song : songs) {
            String artist = song.getArtist() != null ? song.getArtist() : "";
            String title = song.getTitle() != null ? song.getTitle() : "";
            int duration = song.getDuration() != null ? song.getDuration() : 0;
            String localId = CassetteLocalId.compute(artist, title, duration);
            byLocalId.put(localId, song);
            inputs.add(new CassetteSyncApi.PopularRankItem(localId, title, 0));
        }

        String bearer = Preferences.getCassetteBearerToken();
        if (bearer != null) {
            try {
                List<CassetteSyncApi.PopularRankItem> ranked =
                        CassetteSyncApi.rankPopular(bearer, inputs);
                List<Child> out = new ArrayList<>(ranked.size());
                for (CassetteSyncApi.PopularRankItem item : ranked) {
                    Child song = byLocalId.get(item.cassetteLocalId);
                    if (song != null) out.add(song);
                }
                if (!out.isEmpty()) {
                    return out.size() > POPULAR_TEASER_LIMIT
                            ? new ArrayList<>(out.subList(0, POPULAR_TEASER_LIMIT))
                            : out;
                }
            } catch (Exception ignored) {
                // Fall through to local tally.
            }
        }
        return rankPopularLocal(songs);
    }

    /** Hub playCount ↓, title A→Z — offline fallback only. */
    private static List<Child> rankPopularLocal(List<Child> songs) {
        List<Child> ranked = new ArrayList<>(songs);
        ranked.sort((a, b) -> {
            long pa = a.getPlayCount() != null ? a.getPlayCount() : 0L;
            long pb = b.getPlayCount() != null ? b.getPlayCount() : 0L;
            int byPlay = Long.compare(pb, pa);
            if (byPlay != 0) return byPlay;
            String ta = a.getTitle() != null ? a.getTitle() : "";
            String tb = b.getTitle() != null ? b.getTitle() : "";
            return ta.compareToIgnoreCase(tb);
        });
        return ranked.size() > POPULAR_TEASER_LIMIT
                ? new ArrayList<>(ranked.subList(0, POPULAR_TEASER_LIMIT))
                : ranked;
    }

    private void renderPopularList() {
        if (bind == null || songHorizontalAdapter == null) return;

        int limit = popularExpanded
                ? popularSongsFull.size()
                : Math.min(popularSongsFull.size(), POPULAR_COLLAPSED_LIMIT);
        songHorizontalAdapter.setItems(popularSongsFull.subList(0, limit));

        boolean showMore = !popularExpanded && popularSongsFull.size() > POPULAR_COLLAPSED_LIMIT;
        bind.popularShowMoreButton.setVisibility(showMore ? View.VISIBLE : View.GONE);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(
                requireContext(),
                new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))
        ).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(
                mediaBrowserListenableFuture,
                bundle.getParcelableArrayList(Constants.TRACKS_OBJECT),
                bundle.getInt(Constants.ITEM_POSITION)
        );
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
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
}
