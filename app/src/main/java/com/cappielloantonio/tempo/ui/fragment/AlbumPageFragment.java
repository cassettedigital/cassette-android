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
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.CassetteNowPlaying;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.AlbumPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentAlbumPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        albumPageViewModel = new ViewModelProvider(requireActivity()).get(AlbumPageViewModel.class);

        init();
        initAppBar();
        initAlbumInfoTextButton();
        initAlbumNotes();
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
        if (item.getItemId() == R.id.action_download_album) {
            albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                DownloadUtil.getDownloadTracker(requireContext()).download(MappingUtil.mapDownloads(songs), songs.stream().map(Download::new).collect(Collectors.toList()));
            });
            return true;
        }

        return false;
    }

    private void init() {
        albumPageViewModel.setAlbum(getViewLifecycleOwner(), requireArguments().getParcelable(Constants.ALBUM_OBJECT));
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        // cassette: no app-name title — the action bar defaults to the app label
        // when the toolbar has none; this page's chrome is back/overflow only.
        bind.animToolbar.setTitle("");

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(false);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);

        }

        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null) {
                // cassette: no toolbar title — the cover + centered title own
                // the page (iOS reference); toolbar is just back/overflow chrome.

                bind.albumNameLabel.setText(album.getName());
                bind.albumArtistLabel.setText(album.getArtist());
                // cassette: no metadata line on the album header. iOS goes cover
                // -> album name -> artist -> controls, full stop; AlbumDetailVC
                // never feeds the header an info line. An earlier read of the iOS
                // header's CAPABILITY (it can render one) was mistaken for what
                // the album screen SHOWS (nothing), and this is the correction.
                // The count/duration string is still set for the collapsible
                // detail pane, which stays hidden.
                bind.albumSongCountDurationTextview.setText(getString(R.string.album_page_tracks_count_and_duration, album.getSongCount(), album.getDuration() != null ? album.getDuration() / 60 : 0));
                bind.albumGenresTextview.setText(album.getGenre());

                if (album.getReleaseDate() != null && album.getOriginalReleaseDate() != null) {
                    bind.albumReleaseYearsTextview.setVisibility(View.VISIBLE);

                    if (album.getReleaseDate() == null || album.getOriginalReleaseDate() == null) {
                        bind.albumReleaseYearsTextview.setText(getString(R.string.album_page_release_date_label, album.getReleaseDate() != null ? album.getReleaseDate().getFormattedDate() : album.getOriginalReleaseDate().getFormattedDate()));
                    }

                    if (album.getReleaseDate() != null && album.getOriginalReleaseDate() != null) {
                        if (Objects.equals(album.getReleaseDate().getYear(), album.getOriginalReleaseDate().getYear()) && Objects.equals(album.getReleaseDate().getMonth(), album.getOriginalReleaseDate().getMonth()) && Objects.equals(album.getReleaseDate().getDay(), album.getOriginalReleaseDate().getDay())) {
                            bind.albumReleaseYearsTextview.setText(getString(R.string.album_page_release_date_label, album.getReleaseDate().getFormattedDate()));
                        } else {
                            bind.albumReleaseYearsTextview.setText(getString(R.string.album_page_release_dates_label, album.getReleaseDate().getFormattedDate(), album.getOriginalReleaseDate().getFormattedDate()));
                        }
                    }
                }
            }
        });

        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());

        Objects.requireNonNull(bind.animToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
    }

    private void initAlbumInfoTextButton() {
        bind.albumArtistLabel.setOnClickListener(v -> albumPageViewModel.getArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                activity.navController.navigate(R.id.action_albumPageFragment_to_artistPageFragment, bundle);
            } else
                Toast.makeText(requireContext(), getString(R.string.album_error_retrieving_artist), Toast.LENGTH_SHORT).show();
        }));
    }

    private void initAlbumNotes() {
        albumPageViewModel.getAlbumInfo().observe(getViewLifecycleOwner(), albumInfo -> {
            if (albumInfo != null) {
                if (bind != null) bind.albumNotesTextview.setVisibility(View.VISIBLE);
                if (bind != null)
                    bind.albumNotesTextview.setText(MusicUtil.forceReadableString(albumInfo.getNotes()));

                // cassette: no Last.fm link. Tempo turned the notes into a
                // tap-through to last.fm; that provider is prohibited here (not
                // as a source, not as a citation, not as a link). The notes text
                // itself is empty on our agents-off hub, so nothing is lost.
            } else {
                if (bind != null) bind.albumNotesTextview.setVisibility(View.GONE);
            }
        });
    }

    private void initMusicButton() {
        albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            if (bind != null && !songs.isEmpty()) {
                bind.albumPagePlayButton.setOnClickListener(v -> {
                    // cassette: if this album is the one sounding, the button is
                    // a pause, not a restart. Tapping play on a record that is
                    // already playing used to throw you back to track one.
                    if (isThisAlbumCurrent(songs)) {
                        MediaManager.togglePlayPause(mediaBrowserListenableFuture);
                        activity.setBottomSheetInPeek(true);
                        return;
                    }
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    activity.setBottomSheetInPeek(true);
                });

                bind.albumPageShuffleButton.setOnClickListener(v -> {
                    Collections.shuffle(songs);
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    activity.setBottomSheetInPeek(true);
                });

                bind.albumPageRepeatButton.setOnClickListener(v -> {
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    MediaManager.setRepeatMode(mediaBrowserListenableFuture, Player.REPEAT_MODE_ALL);
                    activity.setBottomSheetInPeek(true);
                });
            }

            if (bind != null && songs.isEmpty()) {
                bind.albumPagePlayButton.setEnabled(false);
                bind.albumPageShuffleButton.setEnabled(false);
                bind.albumPageRepeatButton.setEnabled(false);
            }
        });
    }

    private void initBackCover() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null) {
                CustomGlideRequest.Builder.from(requireContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album).build().into(bind.albumCoverImageView);
            }
        });
    }

    private void initSongsView() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null) {
                bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                bind.songRecyclerView.setHasFixedSize(true);

                songHorizontalAdapter = new SongHorizontalAdapter(this, false, false, album);
                bind.songRecyclerView.setAdapter(songHorizontalAdapter);

                albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                    // cassette: hold the list. getAlbumSongLiveList() hands back
                    // a fresh LiveData each call, so its getValue() is null and
                    // anything asking "is this album playing" got a no.
                    albumSongs = songs != null ? songs : Collections.emptyList();
                    songHorizontalAdapter.setItems(songs);
                    refreshPlayButtonState();
                });
            }
        });

        // cassette: keep the tracklist and the big button honest about what is
        // sounding — the marker on the row, play/pause on the button.
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
        boolean playingThisAlbum = CassetteNowPlaying.current().isPlaying && isThisAlbumCurrent(albumSongs);
        bind.albumPagePlayButton.setImageResource(
                playingThisAlbum ? R.drawable.ic_pause : R.drawable.ic_play);
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