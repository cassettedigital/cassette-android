package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.ArtistBottomSheetViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.common.util.concurrent.ListenableFuture;

@UnstableApi
public class ArtistBottomSheetDialog extends BottomSheetDialogFragment implements View.OnClickListener {
    private static final String TAG = "AlbumBottomSheetDialog";

    private ArtistBottomSheetViewModel artistBottomSheetViewModel;
    private ArtistID3 artist;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_artist_dialog, container, false);

        artist = this.requireArguments().getParcelable(Constants.ARTIST_OBJECT);

        artistBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(ArtistBottomSheetViewModel.class);
        artistBottomSheetViewModel.setArtist(artist);

        init(view);

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

    // TODO Utilizzare il viewmodel come tramite ed evitare le chiamate dirette
    private void init(View view) {
        ImageView coverArtist = view.findViewById(R.id.artist_cover_image_view);
        CustomGlideRequest.Builder
                .from(requireContext(), artistBottomSheetViewModel.getArtist().getCoverArtId(), CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(coverArtist);

        TextView nameArtist = view.findViewById(R.id.song_title_text_view);
        nameArtist.setText(artistBottomSheetViewModel.getArtist().getName());
        nameArtist.setSelected(true);

        // cassette: no heart (BUG-947); everything here plays the artist's songs on this phone
        view.findViewById(R.id.button_favorite).setVisibility(View.GONE);
        view.findViewById(R.id.play_artist_text_view).setOnClickListener(v -> withSongs(songs -> {
            MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
            ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
        }));
        view.findViewById(R.id.play_random_text_view).setOnClickListener(v -> withSongs(songs -> {
            java.util.Collections.shuffle(songs);
            MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
            ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
        }));
        view.findViewById(R.id.play_next_text_view).setOnClickListener(v -> withSongs(songs ->
                MediaManager.enqueue(mediaBrowserListenableFuture, songs, true)));
        view.findViewById(R.id.add_to_queue_text_view).setOnClickListener(v -> withSongs(songs ->
                MediaManager.enqueue(mediaBrowserListenableFuture, songs, false)));
    }

    /** The artist's songs on this phone, record by record, then [use] on the main thread; the sheet closes. */
    private void withSongs(java.util.function.Consumer<java.util.List<com.cappielloantonio.tempo.subsonic.models.Child>> use) {
        String id = artist != null ? artist.getId() : null;
        if (id == null) return;
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            java.util.List<com.cappielloantonio.tempo.subsonic.models.Child> songs = new java.util.ArrayList<>(
                    com.cappielloantonio.tempo.database.AppDatabase.getInstance().downloadDao().getArtistSync(id));
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (songs.isEmpty()) {
                    Toast.makeText(app, R.string.cassette_artist_none_here, Toast.LENGTH_SHORT).show();
                } else {
                    use.accept(songs);
                }
                if (isAdded()) dismissBottomSheet();
            });
        }, "cassette-artist-menu").start();
    }

    @Override
    public void onClick(View v) {
        dismissBottomSheet();
    }

    private void dismissBottomSheet() {
        dismiss();
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }
}