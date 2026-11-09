package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
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
import com.cappielloantonio.tempo.databinding.FragmentAlbumPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.CassetteNowPlaying;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.AlbumPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@UnstableApi
public class AlbumPageFragment extends Fragment implements ClickCallback {
    private FragmentAlbumPageBinding bind;
    private MainActivity activity;
    private AlbumPageViewModel albumPageViewModel;
    private SongHorizontalAdapter songHorizontalAdapter;

    // cassette: this album's tracks, held so the play control can tell whether
    // the record in front of you is the one sounding.
    private List<Child> albumSongs = Collections.emptyList();
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.album_page_menu, menu);
        // cassette: keep the "..." glyph in the title colour so it matches the
        // back arrow across the transparent toolbar, rather than the toolbar's
        // default menu-icon tint.
        MenuItem options = menu.findItem(R.id.action_album_options);
        if (options != null && options.getIcon() != null) {
            options.getIcon().setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentAlbumPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        albumPageViewModel = new ViewModelProvider(requireActivity()).get(AlbumPageViewModel.class);

        init();
        initAppBar();
        initHeader();
        initArtistLink();
        initMusicButton();
        initSongsView();
        initMoreBy();

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
        if (item.getItemId() == R.id.action_album_options) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, requireArguments().getParcelable(Constants.ALBUM_OBJECT));
            Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
            return true;
        }

        return false;
    }

    private void init() {
        albumPageViewModel.setAlbum(getViewLifecycleOwner(), requireArguments().getParcelable(Constants.ALBUM_OBJECT));
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        bind.animToolbar.setTitle("");

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        bind.animToolbar.setTitle("");

        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());

        // cassette (BUG-945): once the header has scrolled away, a solid bar with
        // the record's name fades in behind back and more, so the songs never
        // slide under a bare arrow.
        bind.fragmentAlbumPageNestedScrollView.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
                    if (bind == null) return;
                    int bar = bind.animToolbar.getHeight();
                    int pastCover = bind.albumCoverImageView.getBottom() - bar;
                    float t = pastCover <= 0 ? 1f : Math.max(0f, Math.min(1f, (y - pastCover + bar) / (float) bar));
                    bind.albumBarBackdrop.setAlpha(t);
                    boolean showTitle = t >= 1f;
                    com.cappielloantonio.tempo.subsonic.models.AlbumID3 a = albumPageViewModel.getAlbum().getValue();
                    bind.animToolbar.setTitle(showTitle && a != null ? a.getName() : "");
                });
    }

    /** cassette: the lit header: title, artist in the record's colour, the meta line, the glow. */
    private void initHeader() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind == null || album == null) return;
            bind.albumNameLabel.setText(album.getName() != null ? album.getName().toUpperCase(java.util.Locale.getDefault()) : "");
            bind.albumArtistLabel.setText(album.getArtist());

            CustomGlideRequest.Builder.from(requireContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album).build().into(bind.albumCoverImageView);
            bind.albumGlow.setRecipe(com.cappielloantonio.tempo.ui.view.CoverGlowView.PAGE);
            bind.albumGlow.setCover(album.getCoverArtId(), CustomGlideRequest.ResourceType.Album);

            // the record's colour: neutral until read, never orange
            applyTint(com.cappielloantonio.tempo.util.CoverTint.NEUTRAL);
            com.cappielloantonio.tempo.util.CoverTint.resolve(requireContext(), album.getCoverArtId(),
                    CustomGlideRequest.ResourceType.Album, this::applyTint);
            refreshMeta();
        });
    }

    private com.cappielloantonio.tempo.util.CoverTint tint = com.cappielloantonio.tempo.util.CoverTint.NEUTRAL;

    private void applyTint(com.cappielloantonio.tempo.util.CoverTint next) {
        if (bind == null) return;
        tint = next;
        int text = next.text(requireContext());
        bind.albumArtistLabel.setTextColor(text);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            bind.albumCoverImageView.setOutlineSpotShadowColor(next.shadow(0.4));
            bind.albumCoverImageView.setOutlineAmbientShadowColor(next.shadow(0.4));
        }
        if (songHorizontalAdapter != null) songHorizontalAdapter.setTint(text);
        refreshMoreByHeading();
    }

    /** "1997 · 12 SONGS · 53 MIN", counting what is on this phone. */
    private void refreshMeta() {
        if (bind == null) return;
        com.cappielloantonio.tempo.subsonic.models.AlbumID3 album = albumPageViewModel.getAlbum().getValue();
        List<String> parts = new java.util.ArrayList<>();
        if (album != null && album.getYear() > 0) parts.add(String.valueOf(album.getYear()));
        int count = albumSongs.size();
        if (count > 0) {
            parts.add(getResources().getQuantityString(R.plurals.cassette_song_count, count, count));
            long seconds = 0;
            for (Child song : albumSongs) if (song.getDuration() != null) seconds += song.getDuration();
            if (seconds > 0) parts.add(getString(R.string.cassette_minutes, Math.max(1, Math.round(seconds / 60f))));
        }
        bind.albumMetaLabel.setText(android.text.TextUtils.join(" · ", parts));
        bind.albumMetaLabel.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);

        // when part of the record is still on the computer, say so once, quietly
        Integer total = album != null ? album.getSongCount() : null;
        if (total != null && count > 0 && total > count) {
            bind.albumOnPhoneLine.setText(getString(R.string.cassette_songs_on_phone, count, total));
            bind.albumOnPhoneLine.setVisibility(View.VISIBLE);
        } else {
            bind.albumOnPhoneLine.setVisibility(View.GONE);
        }
    }

    private void initArtistLink() {
        bind.albumArtistLabel.setOnClickListener(v -> albumPageViewModel.getArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                activity.navController.navigate(R.id.action_albumPageFragment_to_artistPageFragment, bundle);
            } else
                Toast.makeText(requireContext(), getString(R.string.album_error_retrieving_artist), Toast.LENGTH_SHORT).show();
        }));
    }

    private void initMusicButton() {
        albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;
            boolean any = songs != null && !songs.isEmpty();
            bind.albumPagePlayButton.setEnabled(any);
            bind.albumPageShuffleButton.setEnabled(any);
            if (!any) return;
            bind.albumPagePlayButton.setOnClickListener(v -> {
                if (isThisAlbumCurrent(songs)) {
                    MediaManager.togglePlayPause(mediaBrowserListenableFuture);
                    activity.setBottomSheetInPeek(true);
                    return;
                }
                MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                activity.setBottomSheetInPeek(true);
            });

            bind.albumPageShuffleButton.setOnClickListener(v -> {
                List<Child> shuffled = new java.util.ArrayList<>(songs);
                Collections.shuffle(shuffled);
                MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
                activity.setBottomSheetInPeek(true);
            });
        });
    }

    private void initSongsView() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null && songHorizontalAdapter == null) {
                bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                bind.songRecyclerView.setHasFixedSize(true);

                songHorizontalAdapter = new SongHorizontalAdapter(this, false, false, album);
                songHorizontalAdapter.setTint(tint.text(requireContext()));
                bind.songRecyclerView.setAdapter(songHorizontalAdapter);

                albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                    albumSongs = songs != null ? songs : Collections.emptyList();
                    songHorizontalAdapter.setItems(songs);
                    refreshPlayButtonState();
                    refreshMeta();
                });
            }
        });

        CassetteNowPlaying.live().observe(getViewLifecycleOwner(), state -> {
            if (bind == null || state == null) return;
            if (songHorizontalAdapter != null) {
                songHorizontalAdapter.setNowPlaying(state.mediaId, state.isPlaying);
            }
            refreshPlayButtonState();
        });
    }

    private com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter moreByAdapter;

    /** cassette: MORE BY *artist*: their other records on this phone, each playable in one tap. */
    private void initMoreBy() {
        moreByAdapter = new com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter(
                new com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter.Listener() {
                    @Override
                    public void onOpen(com.cappielloantonio.tempo.subsonic.models.AlbumID3 album) {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                        activity.navController.navigate(R.id.albumPageFragment, bundle);
                    }

                    @Override
                    public void onPlay(com.cappielloantonio.tempo.subsonic.models.AlbumID3 album) {
                        MediaManager.playAlbum(mediaBrowserListenableFuture, album.getId(), false);
                        activity.setBottomSheetInPeek(true);
                    }
                }, com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter.Subtitle.YEAR);
        bind.moreByRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.moreByRecyclerView.setAdapter(moreByAdapter);

        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (album == null) return;
            albumPageViewModel.getMoreByArtist().observe(getViewLifecycleOwner(), others -> {
                if (bind == null) return;
                moreByAdapter.setItems(others);
                bind.moreBySector.setVisibility(others == null || others.isEmpty() ? View.GONE : View.VISIBLE);
                refreshMoreByHeading();
            });
        });
    }

    private void refreshMoreByHeading() {
        if (bind == null) return;
        com.cappielloantonio.tempo.subsonic.models.AlbumID3 album = albumPageViewModel.getAlbum().getValue();
        if (album == null) return;
        com.cappielloantonio.tempo.util.CassetteType.twoVoice(bind.moreByHeading,
                getString(R.string.cassette_more_by), album.getArtist(), tint.text(requireContext()));
    }

    private void refreshPlayButtonState() {
        if (bind == null) return;
        boolean playingThisAlbum = CassetteNowPlaying.current().isPlaying && isThisAlbumCurrent(albumSongs);
        bind.albumPagePlayButton.setText(playingThisAlbum ? R.string.cassette_pause : R.string.cassette_play);
        bind.albumPagePlayButton.setIconResource(playingThisAlbum ? R.drawable.cs_pause_glyph : R.drawable.cs_play_glyph);
    }

    /** cassette: is the track currently loaded one of this album's? */
    private boolean isThisAlbumCurrent(List<Child> songs) {
        String mediaId = CassetteNowPlaying.current().mediaId;
        if (mediaId == null || songs == null) return false;
        for (Child song : songs) {
            if (mediaId.equals(song.getId())) return true;
        }
        return false;
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