package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.SearchView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentPlaylistPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.PlaylistPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

@UnstableApi
public class PlaylistPageFragment extends Fragment implements ClickCallback {
    private FragmentPlaylistPageBinding bind;
    private MainActivity activity;
    private PlaylistPageViewModel playlistPageViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.playlist_page_menu, menu);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentPlaylistPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        playlistPageViewModel = new ViewModelProvider(requireActivity()).get(PlaylistPageViewModel.class);

        init();
        initAppBar();
        initMusicButton();
        initBackCover();
        initSongsView();

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

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_playlist_play_next || id == R.id.action_playlist_add_to_queue) {
            MediaManager.enqueue(mediaBrowserListenableFuture, new java.util.ArrayList<>(playlistSongs), id == R.id.action_playlist_play_next);
            activity.setBottomSheetInPeek(true);
            return true;
        } else if (id == R.id.action_playlist_edit) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlistPageViewModel.getPlaylist());
            com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog dialog =
                    new com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog(new com.cappielloantonio.tempo.interfaces.PlaylistCallback() { });
            dialog.setArguments(bundle);
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        }
        return false;
    }

    private void init() {
        playlistPageViewModel.setPlaylist(requireArguments().getParcelable(Constants.PLAYLIST_OBJECT));
    }

    private java.util.List<com.cappielloantonio.tempo.subsonic.models.Child> playlistSongs = Collections.emptyList();
    private com.cappielloantonio.tempo.util.CoverTint tint = com.cappielloantonio.tempo.util.CoverTint.NEUTRAL;

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        bind.animToolbar.setTitle("");
        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        if (bind.animToolbar.getOverflowIcon() != null) {
            bind.animToolbar.getOverflowIcon().setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
        }

        com.cappielloantonio.tempo.subsonic.models.Playlist playlist = playlistPageViewModel.getPlaylist();
        bind.playlistNameLabel.setText(playlist.getName() != null ? playlist.getName().toUpperCase(java.util.Locale.getDefault()) : "");
        // your own words for it, in the serif; a saved mixtape's boilerplate is left out
        String description = playlist.getComment();
        boolean own = description != null && !description.trim().isEmpty()
                && !description.toLowerCase(java.util.Locale.ROOT).contains("mixtape");
        bind.playlistDescriptionLabel.setText(own ? description.trim() : "");
        bind.playlistDescriptionLabel.setVisibility(own ? View.VISIBLE : View.GONE);

        bind.playlistNestedScrollView.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
                    if (bind == null) return;
                    int bar = bind.animToolbar.getHeight();
                    int past = bind.playlistCoverImageView.getBottom() - bar;
                    float t = past <= 0 ? 1f : Math.max(0f, Math.min(1f, (y - past + bar) / (float) bar));
                    bind.playlistBarBackdrop.setAlpha(t);
                    bind.animToolbar.setTitle(t >= 1f ? playlist.getName() : "");
                });
    }

    private void initMusicButton() {
        playlistPageViewModel.getPlaylistSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;
            playlistSongs = songs != null ? songs : Collections.emptyList();
            boolean any = !playlistSongs.isEmpty();
            bind.playlistPagePlayButton.setEnabled(any);
            bind.playlistPageShuffleButton.setEnabled(any);
            bind.playlistEmpty.emptyStateTitle.setText(R.string.cassette_empty_playlist);
            bind.playlistEmpty.emptyStateTitle.setVisibility(any ? View.GONE : View.VISIBLE);
            refreshMeta();
            refreshPlayButtonState();

            bind.playlistPagePlayButton.setOnClickListener(v -> {
                if (isThisPlaylistCurrent()) {
                    MediaManager.togglePlayPause(mediaBrowserListenableFuture);
                } else {
                    MediaManager.startQueue(mediaBrowserListenableFuture, new java.util.ArrayList<>(playlistSongs), 0);
                    com.cappielloantonio.tempo.repository.PlaylistRepository.notePlayed(playlistPageViewModel.getPlaylist().getId());
                }
                activity.setBottomSheetInPeek(true);
            });

            bind.playlistPageShuffleButton.setOnClickListener(v -> {
                java.util.List<com.cappielloantonio.tempo.subsonic.models.Child> shuffled = new java.util.ArrayList<>(playlistSongs);
                Collections.shuffle(shuffled);
                MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
                com.cappielloantonio.tempo.repository.PlaylistRepository.notePlayed(playlistPageViewModel.getPlaylist().getId());
                activity.setBottomSheetInPeek(true);
            });
        });

        com.cappielloantonio.tempo.util.CassetteNowPlaying.live().observe(getViewLifecycleOwner(), state -> {
            if (bind == null || state == null) return;
            if (songHorizontalAdapter != null) songHorizontalAdapter.setNowPlaying(state.mediaId, state.isPlaying);
            refreshPlayButtonState();
        });
    }

    /** "9 SONGS · 38 MIN", counting the songs on this phone (the length is never 0:00). */
    private void refreshMeta() {
        if (bind == null) return;
        int count = playlistSongs.size();
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (count > 0) {
            parts.add(getResources().getQuantityString(R.plurals.cassette_song_count, count, count));
            long seconds = 0;
            for (com.cappielloantonio.tempo.subsonic.models.Child song : playlistSongs) if (song.getDuration() != null) seconds += song.getDuration();
            if (seconds > 0) parts.add(getString(R.string.cassette_minutes, Math.max(1, Math.round(seconds / 60f))));
        }
        bind.playlistMetaLabel.setText(android.text.TextUtils.join(" · ", parts));
        bind.playlistMetaLabel.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean isThisPlaylistCurrent() {
        String mediaId = com.cappielloantonio.tempo.util.CassetteNowPlaying.current().mediaId;
        if (mediaId == null) return false;
        for (com.cappielloantonio.tempo.subsonic.models.Child song : playlistSongs) {
            if (mediaId.equals(song.getId())) return true;
        }
        return false;
    }

    private void refreshPlayButtonState() {
        if (bind == null) return;
        boolean playing = com.cappielloantonio.tempo.util.CassetteNowPlaying.current().isPlaying && isThisPlaylistCurrent();
        bind.playlistPagePlayButton.setText(playing ? R.string.cassette_pause : R.string.cassette_play);
        bind.playlistPagePlayButton.setIconResource(playing ? R.drawable.cs_pause_glyph : R.drawable.cs_play_glyph);
    }

    /** The playlist's own cover (your pick, else its preset), lighting the page. Never a mosaic. */
    private void initBackCover() {
        String cover = playlistPageViewModel.getPlaylist() != null ? playlistPageViewModel.getPlaylist().getCoverArtId() : null;
        CustomGlideRequest.Builder.from(requireContext(), cover, CustomGlideRequest.ResourceType.Playlist)
                .build().into(bind.playlistCoverImageView);
        bind.playlistGlow.setRecipe(com.cappielloantonio.tempo.ui.view.CoverGlowView.PAGE);
        bind.playlistGlow.setCover(cover, CustomGlideRequest.ResourceType.Playlist);
        com.cappielloantonio.tempo.util.CoverTint.resolve(requireContext(), cover, CustomGlideRequest.ResourceType.Playlist, next -> {
            if (bind == null) return;
            tint = next;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                bind.playlistCoverImageView.setOutlineSpotShadowColor(next.shadow(0.4));
                bind.playlistCoverImageView.setOutlineAmbientShadowColor(next.shadow(0.4));
            }
        });
    }

    private void initSongsView() {
        bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.songRecyclerView.setHasFixedSize(true);

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null);
        bind.songRecyclerView.setAdapter(songHorizontalAdapter);

        playlistPageViewModel.getPlaylistSongLiveList().observe(getViewLifecycleOwner(), songs -> songHorizontalAdapter.setItems(songs));
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