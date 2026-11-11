package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerQueueBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.adapter.PlayerSongQueueAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

@UnstableApi
public class PlayerQueueFragment extends Fragment implements ClickCallback {
    private static final String TAG = "PlayerQueueFragment";

    private InnerFragmentPlayerQueueBinding bind;

    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private PlayerSongQueueAdapter playerSongQueueAdapter;

    // cassette (BUG-980): the saved queue stays in its table while a station plays; UP NEXT lists it only while songs do
    private java.util.List<Child> savedQueue = new ArrayList<>();
    private boolean stationLive = false;
    private MediaBrowser queueBrowser;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerQueueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initQueueRecyclerView();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
    }

    @Override
    public void onResume() {
        super.onResume();
        setMediaBrowserListenableFuture();
        updateNowPlayingItem();
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        queueBrowser = null;
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                initQueueHead(mediaBrowser);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaBrowserListenableFuture() {
        playerSongQueueAdapter.setMediaBrowserListenableFuture(mediaBrowserListenableFuture);
    }

    private void initQueueRecyclerView() {
        bind.playerQueueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.playerQueueRecyclerView.setHasFixedSize(true);

        playerSongQueueAdapter = new PlayerSongQueueAdapter(this);
        bind.playerQueueRecyclerView.setAdapter(playerSongQueueAdapter);
        playerBottomSheetViewModel.getQueueSong().observe(getViewLifecycleOwner(), queue -> {
            if (queue != null) {
                savedQueue = queue.stream().map(item -> (Child) item).collect(Collectors.toList());
                showQueue();
            }
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            int originalPosition = -1;
            int fromPosition = -1;
            int toPosition = -1;

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (originalPosition == -1) {
                    originalPosition = viewHolder.getBindingAdapterPosition();
                }

                fromPosition = viewHolder.getBindingAdapterPosition();
                toPosition = target.getBindingAdapterPosition();

                /*
                 * Per spostare un elemento nella coda devo:
                 *    - Spostare graficamente la traccia da una posizione all'altra con Collections.swap()
                 *    - Spostare nel db la traccia, tramite QueueRepository
                 *    - Notificare il Service dell'avvenuto spostamento con MusicPlayerRemote.moveSong()
                 *
                 * In onMove prendo la posizione di inizio e fine, ma solo al rilascio dell'elemento procedo allo spostamento
                 * In questo modo evito che ad ogni cambio di posizione vada a riscrivere nel db
                 * Al rilascio dell'elemento chiamo il metodo clearView()
                 */

                Collections.swap(playerSongQueueAdapter.getItems(), fromPosition, toPosition);
                recyclerView.getAdapter().notifyItemMoved(fromPosition, toPosition);

                return false;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);

                if (originalPosition != -1 && fromPosition != -1 && toPosition != -1) {
                    MediaManager.swap(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), originalPosition, toPosition);
                }

                originalPosition = -1;
                fromPosition = -1;
                toPosition = -1;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                MediaManager.remove(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), viewHolder.getBindingAdapterPosition());
                viewHolder.getBindingAdapter().notifyDataSetChanged();
            }
        }).attachToRecyclerView(bind.playerQueueRecyclerView);
    }

    /**
     * cassette: the queue's head. UP NEXT and what started it (the record, when the
     * songs ahead are one record) in the record's colour; shuffle and repeat beside
     * it, ink when off and orange while on (live state).
     */
    private void initQueueHead(MediaBrowser mediaBrowser) {
        queueBrowser = mediaBrowser;
        bind.queueShuffle.setOnClickListener(v -> mediaBrowser.setShuffleModeEnabled(!mediaBrowser.getShuffleModeEnabled()));
        bind.queueKeepPlaying.setOnClickListener(v -> {
            // repeat wins: pressing keep playing while repeat is on turns repeat off (the car's ∞ too)
            com.cappielloantonio.tempo.util.CassetteKeepPlaying.press(mediaBrowser);
            paintHead(mediaBrowser);
            paintThen();
        });
        // THEN: the held pick as one card after the queue; Swap walks the cloud's records
        com.cappielloantonio.tempo.util.CassetteKeepPlaying.heading().observe(getViewLifecycleOwner(), words -> paintThen());
        com.cappielloantonio.tempo.util.CassetteKeepPlaying.pick().observe(getViewLifecycleOwner(), p -> paintThen());
        bind.queueThenSwap.setOnClickListener(v -> com.cappielloantonio.tempo.util.CassetteKeepPlaying.swap());
        bind.queueThenCard.setOnClickListener(v -> com.cappielloantonio.tempo.util.CassetteKeepPlaying.playNow());
        bind.queueRepeat.setOnClickListener(v -> {
            int next = mediaBrowser.getRepeatMode() == androidx.media3.common.Player.REPEAT_MODE_OFF
                    ? androidx.media3.common.Player.REPEAT_MODE_ALL
                    : mediaBrowser.getRepeatMode() == androidx.media3.common.Player.REPEAT_MODE_ALL
                    ? androidx.media3.common.Player.REPEAT_MODE_ONE
                    : androidx.media3.common.Player.REPEAT_MODE_OFF;
            mediaBrowser.setRepeatMode(next);
        });
        mediaBrowser.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onShuffleModeEnabledChanged(boolean on) { paintHead(mediaBrowser); }

            @Override
            public void onRepeatModeChanged(int mode) { paintHead(mediaBrowser); }

            @Override
            public void onMediaItemTransition(@androidx.annotation.Nullable androidx.media3.common.MediaItem item, int reason) {
                setStationLive(isStation(item));
                paintHead(mediaBrowser);
                playerSongQueueAdapter.notifyDataSetChanged();
            }

            @Override
            public void onTimelineChanged(@NonNull androidx.media3.common.Timeline timeline, int reason) {
                setStationLive(isStation(mediaBrowser.getCurrentMediaItem()));
            }
        });
        com.cappielloantonio.tempo.util.CoverTint.nowPlaying().observe(getViewLifecycleOwner(), t -> paintHead(mediaBrowser));
        setStationLive(isStation(mediaBrowser.getCurrentMediaItem()));
        paintHead(mediaBrowser);
    }

    private static boolean isStation(@androidx.annotation.Nullable androidx.media3.common.MediaItem item) {
        android.os.Bundle extras = item != null ? item.mediaMetadata.extras : null;
        return com.cappielloantonio.tempo.util.CassetteUpNext.isStation(extras != null ? extras.getString("type") : null);
    }

    private void setStationLive(boolean live) {
        if (stationLive == live) return;
        stationLive = live;
        showQueue();
    }

    /** UP NEXT as it reads now: the kept songs while songs play, one line while a station is live. */
    private void showQueue() {
        if (bind == null) return;
        playerSongQueueAdapter.setItems(com.cappielloantonio.tempo.util.CassetteUpNext.rows(savedQueue, stationLive));
        int head = stationLive ? View.GONE : View.VISIBLE;
        bind.queueShuffle.setVisibility(head);
        bind.queueRepeat.setVisibility(head);
        bind.queueKeepPlaying.setVisibility(head);
        bind.queueStationLine.setVisibility(stationLive ? View.VISIBLE : View.GONE);
        paintThen();
        if (queueBrowser != null) paintHead(queueBrowser);
    }

    private void paintThen() {
        if (bind == null) return;
        com.cappielloantonio.tempo.util.CassetteKeepPlaying.Pick p = com.cappielloantonio.tempo.util.CassetteKeepPlaying.pick().getValue();
        boolean show = p != null && com.cappielloantonio.tempo.util.CassetteKeepPlaying.isOn() && !stationLive;
        bind.queueThen.setVisibility(show ? View.VISIBLE : View.GONE);
        bind.queueThenCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String words = com.cappielloantonio.tempo.util.CassetteKeepPlaying.heading().getValue();
        int tint = com.cappielloantonio.tempo.util.CoverTint.nowPlaying().getValue() != null
                ? com.cappielloantonio.tempo.util.CoverTint.nowPlaying().getValue().text(requireContext())
                : androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_ink2);
        com.cappielloantonio.tempo.util.CassetteType.twoVoice(bind.queueThen, getString(R.string.cassette_then_shout), words, tint);
        int n = p.getSongs().size();
        bind.queueThenTitle.setText(p.getTitle() != null ? p.getTitle() : getResources().getQuantityString(R.plurals.cassette_song_count, n, n));
        bind.queueThenArtist.setText(p.getArtist());
        bind.queueThenArtist.setTextColor(tint);
        bind.queueThenSwap.setVisibility(p.getCanSwap() ? View.VISIBLE : View.GONE);
        com.cappielloantonio.tempo.glide.CustomGlideRequest.Builder.from(requireContext(), p.getCoverArtId(),
                com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album).build().into(bind.queueThenCover);
    }

    private void paintHead(MediaBrowser player) {
        if (bind == null) return;
        int ink = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_ink);
        int live = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_accent);
        bind.queueShuffle.setColorFilter(player.getShuffleModeEnabled() ? live : ink);
        int repeat = player.getRepeatMode();
        bind.queueRepeat.setColorFilter(repeat != androidx.media3.common.Player.REPEAT_MODE_OFF ? live : ink);
        bind.queueRepeat.setImageResource(repeat == androidx.media3.common.Player.REPEAT_MODE_ONE
                ? R.drawable.exo_icon_repeat_one : R.drawable.ic_lucide_repeat);
        // keep playing: orange while on, faint while repeat holds it waiting
        boolean keep = com.cappielloantonio.tempo.util.CassetteKeepPlaying.isOn();
        boolean waiting = keep && repeat != androidx.media3.common.Player.REPEAT_MODE_OFF;
        bind.queueKeepPlaying.setColorFilter(keep && !waiting ? live : ink);
        bind.queueKeepPlaying.setAlpha(waiting ? 0.4f : 1f);

        // the answer: the record, when what's ahead is all one record
        String album = null;
        java.util.List<Child> items = playerSongQueueAdapter.getItems();
        int at = player.getCurrentMediaItemIndex();
        if (items != null && at >= 0 && at < items.size()) {
            album = items.get(at).getAlbum();
            for (int i = at; i < items.size(); i++) {
                if (!java.util.Objects.equals(album, items.get(i).getAlbum())) { album = null; break; }
            }
        }
        com.cappielloantonio.tempo.util.CoverTint tint = com.cappielloantonio.tempo.util.CoverTint.nowPlaying().getValue();
        int color = (tint != null ? tint : com.cappielloantonio.tempo.util.CoverTint.NEUTRAL).text(requireContext());
        com.cappielloantonio.tempo.util.CassetteType.twoVoice(bind.queueHeading, getString(R.string.cassette_up_next), album, color);
    }

    private void updateNowPlayingItem() {
        playerSongQueueAdapter.notifyDataSetChanged();
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
    }
}