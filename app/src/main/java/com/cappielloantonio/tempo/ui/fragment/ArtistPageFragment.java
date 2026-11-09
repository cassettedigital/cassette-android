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
    private static final int POPULAR_TEASER_LIMIT = 5;
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
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        bind.animToolbar.setTitle("");
        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());

        // a solid bar with the artist's name fades in once the hero scrolls away
        bind.artistPageNestedScrollView.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
                    if (bind == null) return;
                    int bar = bind.animToolbar.getHeight();
                    int past = bind.artistInfo.getTop() + bind.artistNameLabel.getBottom() - bar;
                    float t = past <= 0 ? 1f : Math.max(0f, Math.min(1f, (y - past + bar) / (float) bar));
                    bind.artistBarBackdrop.setAlpha(t);
                    ArtistID3 a = artistPageViewModel.getArtist();
                    bind.animToolbar.setTitle(t >= 1f && a != null ? a.getName() : "");
                });
    }

    private com.cappielloantonio.tempo.util.CoverTint tint = com.cappielloantonio.tempo.util.CoverTint.NEUTRAL;
    private String leadCoverId;

    private void initHeader() {
        ArtistID3 artist = artistPageViewModel.getArtist();
        if (artist == null || bind == null) return;

        bind.artistNameLabel.setText(artist.getName() != null ? artist.getName().toUpperCase(java.util.Locale.getDefault()) : "");

        // the artist's own photo, when this phone holds one, goes full-bleed and
        // the name climbs into its faded foot (iPhone: Artist.hasOwnPhotoOnDisk)
        java.io.File photo = com.cappielloantonio.tempo.util.CassetteArtistArt.localFile(requireContext(), artist.getId());
        if (photo != null) {
            bind.artistPhotoFrame.setVisibility(View.VISIBLE);
            bind.artistFan.setVisibility(View.GONE);
            com.bumptech.glide.Glide.with(this).load(photo)
                    .signature(new com.bumptech.glide.signature.ObjectKey(photo.lastModified()))
                    .into(bind.artistPhoto);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bind.artistInfo.getLayoutParams();
            lp.topMargin = -Math.round(110 * getResources().getDisplayMetrics().density);
            bind.artistInfo.setLayoutParams(lp);
        } else {
            bind.artistPhotoFrame.setVisibility(View.GONE);
        }

        if (artistPageViewModel.getAlbumList() != null) {
            artistPageViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
                refreshMetadata(albums);
                lightFrom(albums, photo == null);
            });
        }
    }

    /** Lit by their newest record; with no photo of their own, their three newest covers fan out. */
    private void lightFrom(List<AlbumID3> albums, boolean fan) {
        if (bind == null) return;
        if (albums == null || albums.isEmpty()) {
            bind.artistFan.setVisibility(View.GONE);
            return;
        }
        AlbumID3 lead = albums.get(0);
        if (lead.getCoverArtId() != null && !lead.getCoverArtId().equals(leadCoverId)) {
            leadCoverId = lead.getCoverArtId();
            bind.artistGlow.setRecipe(com.cappielloantonio.tempo.ui.view.CoverGlowView.PAGE);
            bind.artistGlow.setCover(leadCoverId, CustomGlideRequest.ResourceType.Album);
            com.cappielloantonio.tempo.util.CoverTint.resolve(requireContext(), leadCoverId,
                    CustomGlideRequest.ResourceType.Album, this::applyTint);
        }
        if (!fan) return;
        bind.artistFan.setVisibility(View.VISIBLE);
        android.widget.ImageView[] slots = {bind.artistFanCenter, bind.artistFanLeft, bind.artistFanRight};
        for (int i = 0; i < slots.length; i++) {
            if (i < albums.size()) {
                slots[i].setVisibility(View.VISIBLE);
                CustomGlideRequest.Builder.from(requireContext(), albums.get(i).getCoverArtId(),
                        CustomGlideRequest.ResourceType.Album).build().into(slots[i]);
            } else {
                slots[i].setVisibility(View.GONE);
            }
        }
    }

    private void applyTint(com.cappielloantonio.tempo.util.CoverTint next) {
        if (bind == null) return;
        tint = next;
        if (songHorizontalAdapter != null) songHorizontalAdapter.setTint(next.text(requireContext()));
    }

    private void refreshMetadata(List<AlbumID3> albums) {
        if (bind == null) return;
        int albumCount = albums != null ? albums.size() : 0;
        int songCount = librarySongs != null ? librarySongs.size() : 0;
        List<String> parts = new ArrayList<>();
        if (albumCount > 0) parts.add(getResources().getQuantityString(R.plurals.cassette_album_count, albumCount, albumCount));
        if (songCount > 0) parts.add(getResources().getQuantityString(R.plurals.cassette_song_count, songCount, songCount));
        bind.artistMetadataLabel.setText(android.text.TextUtils.join(" · ", parts));
        bind.artistMetadataLabel.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void initMusicButton() {
        if (artistPageViewModel.getArtistLibrarySongs() == null) return;

        artistPageViewModel.getArtistLibrarySongs().observe(getViewLifecycleOwner(), songs -> {
            librarySongs = songs != null ? songs : Collections.emptyList();
            if (bind == null) return;

            boolean hasSongs = !librarySongs.isEmpty();
            bind.artistPagePlayButton.setEnabled(hasSongs);
            bind.artistPageShuffleButton.setEnabled(hasSongs);
            if (artistPageViewModel.getAlbumList() != null) {
                refreshMetadata(artistPageViewModel.getAlbumList().getValue());
            }

            bind.artistPagePlayButton.setOnClickListener(v -> {
                if (librarySongs.isEmpty()) return;
                if (isThisArtistCurrent(librarySongs)) {
                    MediaManager.togglePlayPause(mediaBrowserListenableFuture);
                    activity.setBottomSheetInPeek(true);
                    return;
                }
                MediaManager.startQueue(mediaBrowserListenableFuture, librarySongs, 0);
                activity.setBottomSheetInPeek(true);
            });

            bind.artistPageShuffleButton.setOnClickListener(v -> {
                if (librarySongs.isEmpty()) return;
                List<Child> shuffled = new ArrayList<>(librarySongs);
                Collections.shuffle(shuffled);
                MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
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
        bind.artistPagePlayButton.setText(playingThisArtist ? R.string.cassette_pause : R.string.cassette_play);
        bind.artistPagePlayButton.setIconResource(playingThisArtist ? R.drawable.cs_pause_glyph : R.drawable.cs_play_glyph);
    }

    private boolean isThisArtistCurrent(List<Child> songs) {
        String mediaId = CassetteNowPlaying.current().mediaId;
        if (mediaId == null || songs == null) return false;
        for (Child song : songs) {
            if (mediaId.equals(song.getId())) return true;
        }
        return false;
    }

    private com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter albumTiles;

    /** ALBUMS: their records on this phone, newest first, the year under each, one-tap play. */
    private void initAlbumsView() {
        bind.albumsRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        albumTiles = new com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter(
                new com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter.Listener() {
                    @Override
                    public void onOpen(AlbumID3 album) {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                        activity.navController.navigate(R.id.albumPageFragment, bundle);
                    }

                    @Override
                    public void onPlay(AlbumID3 album) {
                        MediaManager.playAlbum(mediaBrowserListenableFuture, album.getId(), false);
                        activity.setBottomSheetInPeek(true);
                    }
                }, com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter.Subtitle.YEAR);
        bind.albumsRecyclerView.setAdapter(albumTiles);

        if (artistPageViewModel.getAlbumList() == null) return;
        artistPageViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;
            if (albums == null || albums.isEmpty()) {
                bind.artistPageAlbumsSector.setVisibility(View.GONE);
                return;
            }
            bind.artistPageAlbumsSector.setVisibility(View.VISIBLE);
            albumTiles.setItems(albums);
        });
    }

    /**
     * MOST PLAYED leads the page: only songs that have actually been played, top
     * five, ranked by the cloud's counts across every device (popular-rank), with
     * the rank in the record's colour and the album under each title.
     */
    private void initPopularView() {
        bind.mostStreamedSongRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        String artistName = artistPageViewModel.getArtist() != null ? artistPageViewModel.getArtist().getName() : null;
        songHorizontalAdapter = new SongHorizontalAdapter(this, true, true, null, artistName);
        songHorizontalAdapter.setRankMode(true);
        songHorizontalAdapter.setTint(tint.text(requireContext()));
        bind.mostStreamedSongRecyclerView.setAdapter(songHorizontalAdapter);

        if (artistPageViewModel.getArtistLibrarySongs() == null) return;
        artistPageViewModel.getArtistLibrarySongs().observe(getViewLifecycleOwner(), songs -> {
            librarySongs = songs != null ? songs : Collections.emptyList();
            if (bind == null) return;
            bind.artistPageTopSongsSector.setVisibility(View.GONE);
            if (librarySongs.isEmpty()) return;
            final int generation = ++popularRankGeneration;
            final List<Child> snapshot = new ArrayList<>(librarySongs);
            POPULAR_RANK_EXECUTOR.execute(() -> {
                List<Child> ranked = rankPopularCloudOrLocal(snapshot);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (bind == null || generation != popularRankGeneration) return;
                    popularSongsFull = ranked;
                    bind.artistPageTopSongsSector.setVisibility(ranked.isEmpty() ? View.GONE : View.VISIBLE);
                    songHorizontalAdapter.setItems(ranked);
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
                    if (item.playCount <= 0) continue; // only songs actually played
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
        List<Child> ranked = new ArrayList<>();
        for (Child song : songs) {
            if (song.getPlayCount() != null && song.getPlayCount() > 0) ranked.add(song);
        }
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
