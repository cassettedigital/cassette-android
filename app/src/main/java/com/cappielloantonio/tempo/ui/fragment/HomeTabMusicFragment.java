package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.SnapHelper;
import androidx.viewpager2.widget.ViewPager2;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentHomeTabMusicBinding;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.helper.recyclerview.DotsIndicatorDecoration;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.interfaces.PlaylistCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.service.DownloaderManager;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Share;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.AlbumHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.DiscoverSongAdapter;
import com.cappielloantonio.tempo.ui.adapter.PlaylistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.ShareHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.SimilarTrackAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.YearAdapter;
import com.cappielloantonio.tempo.ui.dialog.HomeRearrangementDialog;
import com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.UIUtil;
import com.cappielloantonio.tempo.viewmodel.HomeViewModel;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@UnstableApi
public class HomeTabMusicFragment extends Fragment implements ClickCallback {
    private static final String TAG = "HomeFragment";

    private FragmentHomeTabMusicBinding bind;
    private MainActivity activity;
    private HomeViewModel homeViewModel;

    // cassette: only the live-shelf adapters remain. The stock Tempo shelves
    // (discover, similar, best-of, radio, top-songs, starred x3, new-releases,
    // flashback/year, most-played, shares) are removed - their queries are
    // pocketed in HomeViewModel and catalogued in docs/CASSETTE_ANDROID_FUTURE_SHELVES.md.
    private AlbumAdapter recentlyAddedAlbumAdapter;
    private AlbumAdapter recentlyPlayedAlbumAdapter;
    private ArtistAdapter recentlyPlayedArtistsAdapter;
    private com.cappielloantonio.tempo.ui.adapter.PlaylistTileAdapter playlistTileAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentHomeTabMusicBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        homeViewModel = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);

        init();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // cassette: home mirrors the iOS three-block shape — greeting, Recent
        // (recently played), Albums (recently added), playlists. The stock
        // Tempo sections that are not Cassette surfaces (starred/favorites,
        // discovery mixes, radio, best-of, flashback, top songs, shares,
        // most played, new releases) are simply never initialized; their
        // sectors default to gone in the layout. Re-enable = re-add a call.
        // cassette: no clock greeting (the iPhone cut it as sprawl on 10-20)
        bind.homeGreetingSector.setVisibility(View.GONE);
        initSyncBanner();
        initResumeCard();
        // cassette: no recently-played-TRACKS shelf. iOS Home is all album
        // cards (Resume -> Recent -> Albums), never a list of individual songs;
        // this Cassette-only track list was the module that read as wrong.
        initRecentPlayedAlbumView();
        initRecentAddedAlbumView();
        initRecentlyPlayedArtistsView();
        initPinnedPlaylistsView();
        loadCloudHome();

        // cassette: no Reorganize, and no reorder(). The Home order is fixed
        // (moving toward iOS's fixed shelf set), so the user-rearrangement
        // button is gone and reorder() is not called. reorder() wiped the
        // whole container and re-added only the stock sectors, dropping the
        // greeting, sync banner and resume card - BUG-080. Not calling it means
        // the layout's own order stands, which is what fresh installs already
        // used (reorder returned early with no saved order).
    }

    /** iOS-style header: time-of-day eyebrow above the Home title. */
    private void initGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int greeting = hour >= 5 && hour < 12
                ? R.string.home_greeting_morning
                : hour >= 12 && hour < 18
                ? R.string.home_greeting_afternoon
                : R.string.home_greeting_evening;
        bind.homeGreetingEyebrow.setText(greeting);
    }

    /**
     * cassette: sync banner — the in-app half of "something is happening".
     * Sits under the greeting while an account sync is moving and disappears
     * on its own when the wave finishes. A person who just paired a phone
     * should never have to guess whether their library is on the way.
     */
    private void initSyncBanner() {
        com.cappielloantonio.tempo.util.CassetteSyncStatus.live().observe(getViewLifecycleOwner(), status -> {
            boolean active = status != null && status.active;
            bind.homeSyncBanner.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) {
                bind.homeSyncTitle.setText(status.headline());
                bind.homeSyncDetail.setText(status.detail());
                bind.homeSyncProgress.setProgress(status.percent());
                // A parked queue is not "in progress" — stop the spinner so the
                // banner reads as waiting rather than working.
                boolean moving = !status.waitingForWifi && !status.lowOnSpace;
                bind.homeSyncSpinner.setVisibility(moving ? View.VISIBLE : View.GONE);
            }
            // cassette: the banner and the resume card share the top slot. When the
            // banner shows it covers the resume card (applyResumeCard hides it while
            // a sync is active); the card returns the moment the wave finishes.
            applyResumeCard();
        });
    }

    /**
     * cassette: Resume card — one tap back into the music. Two sources, most
     * faithful first: the play-history row (last COMPLETED track — Tempo only
     * logs chronology when a song plays through) and, when history is empty
     * (fresh pairing, mid-first-listen), the persisted queue's most recent
     * item — the song you were actually in the middle of.
     */
    private com.cappielloantonio.tempo.subsonic.models.Child resumeFromHistory;
    private long resumeFromHistoryTs;
    // cassette: held as the Queue row, not as a bare Child, because the saved
    // paused position lives on the queue row (playingChanged).
    private com.cappielloantonio.tempo.model.Queue resumeFromQueue;
    private long resumeFromQueueTs;
    // cassette play spine (Phase B): a play made on another surface, resolved local.
    private com.cappielloantonio.tempo.subsonic.models.Child resumeFromRemote;
    private long resumeFromRemoteTs;
    // cassette (One Home): the cloud's Resume, the record last played on any device.
    private com.cappielloantonio.tempo.subsonic.models.Child resumeFromCloud;

    private void initResumeCard() {
        homeViewModel.getLastPlayedSong().observe(getViewLifecycleOwner(), items -> {
            if (items == null || items.isEmpty()) {
                resumeFromHistory = null;
                resumeFromHistoryTs = 0;
            } else {
                resumeFromHistory = items.get(0);
                resumeFromHistoryTs = items.get(0).getTimestamp();
            }
            applyResumeCard();
        });

        // cassette play spine (Phase B): fold in the most recent play from another
        // surface (iOS/web/desktop), resolved to a locally-owned track.
        homeViewModel.getRemoteResume().observe(getViewLifecycleOwner(), remote -> {
            resumeFromRemote = remote != null ? remote.track : null;
            resumeFromRemoteTs = remote != null ? remote.playedAt : 0;
            applyResumeCard();
        });

        new com.cappielloantonio.tempo.repository.QueueRepository().getLiveQueue().observe(getViewLifecycleOwner(), queue -> {
            com.cappielloantonio.tempo.model.Queue latest = null;
            if (queue != null) {
                for (com.cappielloantonio.tempo.model.Queue item : queue) {
                    if (latest == null || item.getLastPlay() > latest.getLastPlay()) latest = item;
                }
            }
            resumeFromQueue = latest;
            resumeFromQueueTs = latest != null ? latest.getLastPlay() : 0;
            applyResumeCard();
        });
    }

    private void applyResumeCard() {
        if (bind == null) return;

        // cassette: the sync banner owns the top slot while a sync is moving, so
        // the resume card steps aside and the banner covers it. Re-run when the
        // sync status flips (see initSyncBanner) so the card returns when it ends.
        com.cappielloantonio.tempo.util.CassetteSyncStatus.Snapshot sync =
                com.cappielloantonio.tempo.util.CassetteSyncStatus.live().getValue();
        // cassette: Home holds still (iPhone 316d011a): the banner sits above
        // Resume while a sync moves; it no longer hides it.

        // Pick the newest actual play across this device (history) and other
        // surfaces (remote); the persisted queue is only a fallback when there is
        // no play history at all (fresh pairing / mid-first-listen).
        com.cappielloantonio.tempo.subsonic.models.Child track = null;
        long best = Long.MIN_VALUE;
        if (resumeFromHistory != null) {
            track = resumeFromHistory;
            best = resumeFromHistoryTs;
        }
        if (resumeFromRemote != null && resumeFromRemoteTs > best) {
            track = resumeFromRemote;
            best = resumeFromRemoteTs;
        }
        if (resumeFromCloud != null) track = resumeFromCloud;
        if (track == null) track = resumeFromQueue;

        if (track == null) {
            bind.homeResumeCard.setVisibility(View.GONE);
            return;
        }

        bind.homeResumeCard.setVisibility(View.VISIBLE);
        bind.homeResumeTitle.setText(track.getTitle());
        bind.homeResumeArtist.setText(track.getArtist());

        CustomGlideRequest.Builder
                .from(requireContext(), track.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(bind.homeResumeCover);

        final com.cappielloantonio.tempo.subsonic.models.Child target = track;
        final long savedPositionMs = resumeFromQueue != null
                && target.getId() != null
                && target.getId().equals(resumeFromQueue.getId())
                ? resumeFromQueue.getPlayingChanged()
                : 0L;

        // cassette: iOS splits these two. Tapping the card OPENS the record;
        // only the play button makes sound. Ours played on any tap, so an
        // exploratory press on the biggest thing on the screen started audio.
        bind.homeResumeCard.setOnClickListener(v -> openResumeContainer(target));
        bind.homeResumePlay.setOnClickListener(v -> {
            // cassette (BUG-943): carry on with the record, from this song, at the
            // saved position. It used to clear the queue and play this one song.
            MediaManager.resume(mediaBrowserListenableFuture, target, savedPositionMs);
            activity.setBottomSheetInPeek(true);
        });
    }

    /**
     * cassette: the resume card's subject is a container on iOS, so tapping it
     * lands on the album rather than on a single track. We carry the track's
     * album id, which the album page resolves on its own.
     */
    private void openResumeContainer(com.cappielloantonio.tempo.subsonic.models.Child track) {
        if (track.getAlbumId() == null) {
            // No container to open (a stray file): fall back to playing it.
            MediaManager.startQueue(mediaBrowserListenableFuture, track);
            activity.setBottomSheetInPeek(true);
            return;
        }

        com.cappielloantonio.tempo.subsonic.models.AlbumID3 album =
                new com.cappielloantonio.tempo.subsonic.models.AlbumID3();
        album.setId(track.getAlbumId());
        album.setName(track.getAlbum());
        album.setArtist(track.getArtist());
        album.setArtistId(track.getArtistId());
        album.setCoverArtId(track.getCoverArtId());

        Bundle bundle = new Bundle();
        bundle.putParcelable(Constants.ALBUM_OBJECT, album);
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    /**
     * cassette play spine (Phase B): "Recently played" tracks unioned across this
     * device and other surfaces (iOS/web/desktop, resolved to owned tracks). A
     * horizontal shelf that fills in when there's cross-device history to show.
     */

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
    }

    @Override
    public void onResume() {
        super.onResume();
        // cassette: pull plays made on other surfaces, then refresh the cross-device
        // shelves, so returning to the app shows the latest without a force-quit.
        // Before, onResume did nothing and the shelves only loaded on a fresh
        // fragment create — so a play made on iOS while this app was backgrounded
        // never appeared until the process was killed and relaunched.
        com.cappielloantonio.tempo.util.CassettePlayPull.pullAsync(this::reloadCrossDeviceRecents);
        loadCloudHome();
    }

    /**
     * cassette: re-query the cross-device shelves after a cloud-play pull. Each
     * call re-posts to a LiveData the shelves already observe (registered in
     * onViewCreated), so this refreshes the Resume card, the recently-played
     * artists, and the recently-played albums in place. Guarded on the view still
     * being alive — the pull completes on a background thread.
     */
    private void reloadCrossDeviceRecents() {
        if (bind == null) return;
        homeViewModel.getRemoteResume();
        homeViewModel.getRecentlyPlayedArtists();
        homeViewModel.refreshRecentlyPlayedAlbumList(getViewLifecycleOwner());
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
        // cassette: Recent / Albums / Artists headers share one chrome - title +
        // chevron, whole header tappable (iOS SectionHeaderView parity). Long-
        // press on the album titles still refreshes that shelf.
        View.OnClickListener openRecentAlbums = v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_RECENTLY_PLAYED, Constants.ALBUM_RECENTLY_PLAYED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        };
        View.OnClickListener openAddedAlbums = v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_RECENTLY_ADDED, Constants.ALBUM_RECENTLY_ADDED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        };
        View.OnClickListener openArtists = v ->
                activity.navController.navigate(R.id.action_homeFragment_to_artistCatalogueFragment);

        bind.recentlyPlayedAlbumsHeader.setOnClickListener(openRecentAlbums);
        bind.recentlyPlayedAlbumsTextViewRefreshable.setOnClickListener(openRecentAlbums);
        bind.recentlyPlayedAlbumsTextViewClickable.setOnClickListener(openRecentAlbums);

        bind.recentlyAddedAlbumsHeader.setOnClickListener(openAddedAlbums);
        bind.recentlyAddedAlbumsTextViewRefreshable.setOnClickListener(openAddedAlbums);
        bind.recentlyAddedAlbumsTextViewClickable.setOnClickListener(openAddedAlbums);

        bind.recentlyPlayedArtistsHeader.setOnClickListener(openArtists);
        bind.recentlyPlayedArtistsTextView.setOnClickListener(openArtists);
        bind.recentlyPlayedArtistsTextViewClickable.setOnClickListener(openArtists);

        bind.recentlyPlayedAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshRecentlyPlayedAlbumList(getViewLifecycleOwner());
            return true;
        });

        bind.recentlyAddedAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshMostRecentlyAddedAlbums(getViewLifecycleOwner());
            return true;
        });

        // cassette: pull-to-refresh — pull plays made on other surfaces, then
        // refresh the cross-device shelves in place and stop the spinner. Same
        // pull+reload the foreground path (onResume) runs, on an explicit gesture.
        bind.homeSwipeRefresh.setOnRefreshListener(() ->
                com.cappielloantonio.tempo.util.CassettePlayPull.pullAsync(() -> {
                    reloadCrossDeviceRecents();
                    loadCloudHome();
                    if (bind != null) bind.homeSwipeRefresh.setRefreshing(false);
                }));
    }

    // ---- One Home ----

    private void loadCloudHome() {
        if (bind == null) return;
        com.cappielloantonio.tempo.util.CassetteHome.load(requireContext(), this::drawCloudHome);
    }

    /**
     * cassette: the cloud's rows, in its order, from what this phone holds. When
     * the phone can draw any of them, they replace the stand-in rows (Recent,
     * Albums, Artists, Playlists), which stay only for a first launch offline.
     */
    private void drawCloudHome(java.util.List<com.cappielloantonio.tempo.util.CassetteHome.DrawnRow> rows) {
        if (bind == null || !isAdded()) return;
        android.widget.LinearLayout container = bind.homeCloudRows;
        container.removeAllViews();
        int drawn = 0;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int answerColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_accent_text);
        resumeFromCloud = null;
        for (com.cappielloantonio.tempo.util.CassetteHome.DrawnRow row : rows) {
            if (!row.isFilled()) continue;
            if ("resume".equals(row.getKind())) {
                resumeFromCloud = row.getSong();
                continue;
            }
            java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> tiles = tilesFor(row);
            if (tiles.isEmpty()) continue;
            com.cappielloantonio.tempo.databinding.ItemHomeRowBinding rowBind =
                    com.cappielloantonio.tempo.databinding.ItemHomeRowBinding.inflate(inflater, container, false);
            com.cappielloantonio.tempo.util.CassetteType.twoVoice(rowBind.homeRowHeading, row.getShout(), row.getAnswer(), answerColor);
            rowBind.homeRowTiles.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter adapter = new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter();
            rowBind.homeRowTiles.setAdapter(adapter);
            adapter.setItems(tiles);
            container.addView(rowBind.getRoot());
            drawn++;
        }
        boolean cloud = drawn > 0;
        container.setVisibility(cloud ? View.VISIBLE : View.GONE);
        if (cloud) {
            // the stand-in rows leave for good once the cloud's Home has drawn:
            // their own observers would otherwise show them again as data lands
            for (View standIn : new View[]{bind.homeRecentlyPlayedAlbumsSector, bind.homeRecentlyAddedAlbumsSector,
                    bind.homeRecentlyPlayedArtistsSector, bind.pinnedPlaylistsSector}) {
                if (standIn.getParent() instanceof ViewGroup) ((ViewGroup) standIn.getParent()).removeView(standIn);
            }
        }
        applyResumeCard();
    }

    private java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> tilesFor(
            com.cappielloantonio.tempo.util.CassetteHome.DrawnRow row) {
        java.util.List<com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile> tiles = new java.util.ArrayList<>();
        for (com.cappielloantonio.tempo.util.CassetteHome.DrawnMix mix : row.getMixes()) {
            int n = mix.getSongs().size();
            String cover = mix.getCoverUrl() != null ? mix.getCoverUrl() : mix.getSongs().get(0).getCoverArtId();
            tiles.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(
                    mix.getTitle(), getResources().getQuantityString(R.plurals.cassette_song_count, n, n), cover,
                    mix.getCoverUrl() != null ? CustomGlideRequest.ResourceType.Playlist : CustomGlideRequest.ResourceType.Album,
                    false,
                    () -> {
                        Bundle bundle = new Bundle();
                        bundle.putString(MixPageFragment.ARG_MIX_ID, mix.getId());
                        activity.navController.navigate(R.id.mixPageFragment, bundle);
                    },
                    () -> {
                        MediaManager.startQueue(mediaBrowserListenableFuture, new java.util.ArrayList<>(mix.getSongs()), 0);
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        for (com.cappielloantonio.tempo.subsonic.models.AlbumID3 album : row.getAlbums()) {
            tiles.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(
                    album.getName(), album.getArtist(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album, false,
                    () -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                        activity.navController.navigate(R.id.albumPageFragment, bundle);
                    },
                    () -> {
                        MediaManager.playAlbum(mediaBrowserListenableFuture, album.getId(), false);
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        for (com.cappielloantonio.tempo.subsonic.models.ArtistID3 artist : row.getArtists()) {
            tiles.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(
                    artist.getName(), null, artist.getCoverArtId(), CustomGlideRequest.ResourceType.Artist, true,
                    () -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                        activity.navController.navigate(R.id.artistPageFragment, bundle);
                    },
                    () -> {
                        MediaManager.playArtist(mediaBrowserListenableFuture, artist.getId());
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        for (com.cappielloantonio.tempo.util.CassetteRadio.Station station : row.getStations()) {
            Runnable tune = () -> {
                MediaManager.startRadio(mediaBrowserListenableFuture, station.toInternetRadioStation());
                activity.setBottomSheetInPeek(true);
            };
            tiles.add(com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile.station(
                    station, () -> StationPageFragment.open(this, station.getSlug()), tune));
        }
        for (com.cappielloantonio.tempo.subsonic.models.Playlist playlist : row.getPlaylists()) {
            int n = playlist.getSongCount();
            tiles.add(new com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter.Tile(
                    playlist.getName(), getResources().getQuantityString(R.plurals.cassette_song_count, n, n),
                    playlist.getCoverArtId(), CustomGlideRequest.ResourceType.Playlist, false,
                    () -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlist);
                        activity.navController.navigate(R.id.playlistPageFragment, bundle);
                    },
                    () -> new com.cappielloantonio.tempo.repository.PlaylistRepository().getPlaylistSongs(playlist.getId())
                            .observe(getViewLifecycleOwner(), songs -> {
                                if (songs == null || songs.isEmpty()) return;
                                MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                                com.cappielloantonio.tempo.repository.PlaylistRepository.notePlayed(playlist.getId());
                                activity.setBottomSheetInPeek(true);
                            })));
        }
        return tiles;
    }

    private void initRecentPlayedAlbumView() {
        if (homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_LAST_PLAYED)) return;

        bind.recentlyPlayedAlbumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.recentlyPlayedAlbumsRecyclerView.setHasFixedSize(true);

        recentlyPlayedAlbumAdapter = new AlbumAdapter(this);
        bind.recentlyPlayedAlbumsRecyclerView.setAdapter(recentlyPlayedAlbumAdapter);
        homeViewModel.getRecentlyPlayedAlbumList(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (albums == null) {
                if (bind != null) bind.homeRecentlyPlayedAlbumsSector.setVisibility(View.GONE);
            } else {
                if (bind != null)
                    bind.homeRecentlyPlayedAlbumsSector.setVisibility(!albums.isEmpty() ? View.VISIBLE : View.GONE);

                recentlyPlayedAlbumAdapter.setItems(albums);
            }
        });

        CustomLinearSnapHelper recentPlayedAlbumSnapHelper = new CustomLinearSnapHelper();
        recentPlayedAlbumSnapHelper.attachToRecyclerView(bind.recentlyPlayedAlbumsRecyclerView);
    }

    private void initRecentAddedAlbumView() {
        if (homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_RECENTLY_ADDED)) return;

        bind.recentlyAddedAlbumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.recentlyAddedAlbumsRecyclerView.setHasFixedSize(true);

        recentlyAddedAlbumAdapter = new AlbumAdapter(this);
        bind.recentlyAddedAlbumsRecyclerView.setAdapter(recentlyAddedAlbumAdapter);
        homeViewModel.getMostRecentlyAddedAlbums(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (albums == null) {
                if (bind != null) bind.homeRecentlyAddedAlbumsSector.setVisibility(View.GONE);
            } else {
                if (bind != null)
                    bind.homeRecentlyAddedAlbumsSector.setVisibility(!albums.isEmpty() ? View.VISIBLE : View.GONE);

                recentlyAddedAlbumAdapter.setItems(albums);
            }
        });

        CustomLinearSnapHelper recentAddedAlbumSnapHelper = new CustomLinearSnapHelper();
        recentAddedAlbumSnapHelper.attachToRecyclerView(bind.recentlyAddedAlbumsRecyclerView);
    }

    /**
     * cassette: the Artists shelf (iOS slot below Albums) - recently played
     * artists as circles, from the same play spine as Recent. Hidden when the
     * play history has no resolvable artists yet.
     */
    private void initRecentlyPlayedArtistsView() {
        bind.recentlyPlayedArtistsRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.recentlyPlayedArtistsRecyclerView.setHasFixedSize(true);

        recentlyPlayedArtistsAdapter = new ArtistAdapter(this, false, false);
        bind.recentlyPlayedArtistsRecyclerView.setAdapter(recentlyPlayedArtistsAdapter);
        homeViewModel.getRecentlyPlayedArtists().observe(getViewLifecycleOwner(), artists -> {
            if (bind == null) return;
            if (artists == null || artists.isEmpty()) {
                bind.homeRecentlyPlayedArtistsSector.setVisibility(View.GONE);
            } else {
                bind.homeRecentlyPlayedArtistsSector.setVisibility(View.VISIBLE);
                recentlyPlayedArtistsAdapter.setItems(artists);
            }
        });

        CustomLinearSnapHelper artistSnapHelper = new CustomLinearSnapHelper();
        artistSnapHelper.attachToRecyclerView(bind.recentlyPlayedArtistsRecyclerView);
    }

    private void initPinnedPlaylistsView() {
        if (homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_PINNED_PLAYLISTS)) return;

        // cassette: iOS shows playlists on Home as 160pt square tiles in a
        // horizontal shelf. Ours were vertical list rows with kebab menus - the
        // one shelf that should read as artwork read as admin instead.
        bind.pinnedPlaylistsRecyclerView.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.pinnedPlaylistsRecyclerView.setHasFixedSize(true);

        playlistTileAdapter = new com.cappielloantonio.tempo.ui.adapter.PlaylistTileAdapter(this);
        bind.pinnedPlaylistsRecyclerView.setAdapter(playlistTileAdapter);
        homeViewModel.getPinnedPlaylists(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), playlists -> {
            if (playlists == null) {
                if (bind != null) bind.pinnedPlaylistsSector.setVisibility(View.GONE);
            } else {
                if (bind != null)
                    bind.pinnedPlaylistsSector.setVisibility(!playlists.isEmpty() ? View.VISIBLE : View.GONE);

                playlistTileAdapter.setItems(playlists);
            }
        });
    }

    private void setSlideViewOffset(ViewPager2 viewPager, float pageOffset, float pageMargin) {
        viewPager.setPageTransformer((page, position) -> {
            float myOffset = position * -(2 * pageOffset + pageMargin);
            if (viewPager.getOrientation() == ViewPager2.ORIENTATION_HORIZONTAL) {
                if (ViewCompat.getLayoutDirection(viewPager) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                    page.setTranslationX(-myOffset);
                } else {
                    page.setTranslationX(myOffset);
                }
            } else {
                page.setTranslationY(myOffset);
            }
        });
    }

    private void refreshPlaylistView() {
        final Handler handler = new Handler();

        final Runnable runnable = () -> {
            if (getView() != null && bind != null && homeViewModel != null)
                homeViewModel.getPinnedPlaylists(getViewLifecycleOwner());
        };

        handler.postDelayed(runnable, 100);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        if (bundle.containsKey(Constants.MEDIA_MIX)) {
            MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelable(Constants.TRACK_OBJECT));
            activity.setBottomSheetInPeek(true);

            if (mediaBrowserListenableFuture != null) {
                homeViewModel.getMediaInstantMix(getViewLifecycleOwner(), bundle.getParcelable(Constants.TRACK_OBJECT)).observe(getViewLifecycleOwner(), songs -> {
                    MusicUtil.ratingFilter(songs);

                    if (songs != null && !songs.isEmpty()) {
                        MediaManager.enqueue(mediaBrowserListenableFuture, songs, true);
                    }
                });
            }
        } else if (bundle.containsKey(Constants.MEDIA_CHRONOLOGY)) {
            List<Child> media = bundle.getParcelableArrayList(Constants.TRACKS_OBJECT);
            MediaManager.startQueue(mediaBrowserListenableFuture, media, bundle.getInt(Constants.ITEM_POSITION));
            activity.setBottomSheetInPeek(true);
        } else {
            MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
            activity.setBottomSheetInPeek(true);
        }
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
        if (bundle.containsKey(Constants.MEDIA_MIX) && bundle.getBoolean(Constants.MEDIA_MIX)) {
            Snackbar.make(requireView(), R.string.artist_adapter_radio_station_starting, Snackbar.LENGTH_LONG)
                    .setAnchorView(activity.bind.playerBottomSheet)
                    .show();

            if (mediaBrowserListenableFuture != null) {
                homeViewModel.getArtistInstantMix(getViewLifecycleOwner(), bundle.getParcelable(Constants.ARTIST_OBJECT)).observe(getViewLifecycleOwner(), songs -> {
                    MusicUtil.ratingFilter(songs);

                    if (!songs.isEmpty()) {
                        MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                        activity.setBottomSheetInPeek(true);
                    }
                });
            }
        } else if (bundle.containsKey(Constants.MEDIA_BEST_OF) && bundle.getBoolean(Constants.MEDIA_BEST_OF)) {
            if (mediaBrowserListenableFuture != null) {
                homeViewModel.getArtistBestOf(getViewLifecycleOwner(), bundle.getParcelable(Constants.ARTIST_OBJECT)).observe(getViewLifecycleOwner(), songs -> {
                    MusicUtil.ratingFilter(songs);

                    if (!songs.isEmpty()) {
                        MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                        activity.setBottomSheetInPeek(true);
                    }
                });
            }
        } else {
            Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
        }
    }

    @Override
    public void onArtistLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
    }

    @Override
    public void onYearClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songListPageFragment, bundle);
    }

    @Override
    public void onShareClick(Bundle bundle) {
        Share share = bundle.getParcelable(Constants.SHARE_OBJECT);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(share.getUrl())).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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
                refreshPlaylistView();
            }
        });

        dialog.setArguments(bundle);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    public void onShareLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.shareBottomSheetDialog, bundle);
    }
}
