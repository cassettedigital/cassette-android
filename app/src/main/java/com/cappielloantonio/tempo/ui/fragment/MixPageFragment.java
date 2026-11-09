package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentPlaylistPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.ui.view.CoverGlowView;
import com.cappielloantonio.tempo.util.CassetteHome;
import com.cappielloantonio.tempo.util.CassetteNowPlaying;
import com.cappielloantonio.tempo.util.Constants;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * cassette: a made-for-you playlist's page (iPhone MixDetailVC, 1f27effa): its
 * picture up top lighting the page, the name in caps, its subtitle in the serif,
 * count and length, PLAY and SHUFFLE pills, then its songs on this phone.
 * The playlist page's own room, read from the cloud's Home (CassetteHome.mix).
 */
@UnstableApi
public class MixPageFragment extends Fragment implements ClickCallback {
    public static final String ARG_MIX_ID = "cassette_mix_id";

    private FragmentPlaylistPageBinding bind;
    private MainActivity activity;
    private SongHorizontalAdapter adapter;
    private List<Child> songs = Collections.emptyList();
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentPlaylistPageBinding.inflate(inflater, container, false);

        CassetteHome.DrawnMix mix = CassetteHome.mix(requireArguments().getString(ARG_MIX_ID));
        activity.setSupportActionBar(bind.animToolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        bind.animToolbar.setTitle("");
        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        if (mix == null) return bind.getRoot();

        songs = new ArrayList<>(mix.getSongs());
        bind.playlistNameLabel.setText(mix.getTitle().toUpperCase(Locale.getDefault()));
        bind.playlistDescriptionLabel.setText(mix.getSubtitle());
        bind.playlistDescriptionLabel.setVisibility(mix.getSubtitle() == null || mix.getSubtitle().isEmpty() ? View.GONE : View.VISIBLE);

        String cover = mix.getCoverUrl() != null ? mix.getCoverUrl() : (songs.isEmpty() ? null : songs.get(0).getCoverArtId());
        CustomGlideRequest.ResourceType type = mix.getCoverUrl() != null ? CustomGlideRequest.ResourceType.Playlist : CustomGlideRequest.ResourceType.Album;
        CustomGlideRequest.Builder.from(requireContext(), cover, type).build().into(bind.playlistCoverImageView);
        bind.playlistGlow.setRecipe(CoverGlowView.PAGE);
        bind.playlistGlow.setCover(songs.isEmpty() ? cover : songs.get(0).getCoverArtId(), CustomGlideRequest.ResourceType.Album);

        long seconds = 0;
        for (Child song : songs) if (song.getDuration() != null) seconds += song.getDuration();
        List<String> parts = new ArrayList<>();
        parts.add(getResources().getQuantityString(R.plurals.cassette_song_count, songs.size(), songs.size()));
        if (seconds > 0) parts.add(getString(R.string.cassette_minutes, Math.max(1, Math.round(seconds / 60f))));
        bind.playlistMetaLabel.setText(android.text.TextUtils.join(" · ", parts));

        bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SongHorizontalAdapter(this, true, false, null);
        bind.songRecyclerView.setAdapter(adapter);
        adapter.setItems(songs);

        bind.playlistPagePlayButton.setOnClickListener(v -> {
            MediaManager.startQueue(mediaBrowserListenableFuture, new ArrayList<>(songs), 0);
            activity.setBottomSheetInPeek(true);
        });
        bind.playlistPageShuffleButton.setOnClickListener(v -> {
            List<Child> shuffled = new ArrayList<>(songs);
            Collections.shuffle(shuffled);
            MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
            activity.setBottomSheetInPeek(true);
        });
        CassetteNowPlaying.live().observe(getViewLifecycleOwner(), state -> {
            if (state != null && adapter != null) adapter.setNowPlaying(state.mediaId, state.isPlaying);
        });
        return bind.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(),
                new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    @Override
    public void onStop() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
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
