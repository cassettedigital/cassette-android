package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog;

import android.content.ComponentName;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.fragment.NavHostFragment;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.PlaylistChooserDialog;
import com.cappielloantonio.tempo.ui.dialog.RatingDialog;
import com.cappielloantonio.tempo.util.CassetteShareSong;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.SongBottomSheetViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.common.util.concurrent.ListenableFuture;

@UnstableApi
public class SongBottomSheetDialog extends BottomSheetDialogFragment implements View.OnClickListener {
    /** cassette: opened from Now Playing's ⋮, over the expanded player. */
    public static final String ARG_FROM_NOW_PLAYING = "cassette_from_now_playing";

    private SongBottomSheetViewModel songBottomSheetViewModel;
    private Child song;
    private boolean fromNowPlaying;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_song_dialog, container, false);

        song = requireArguments().getParcelable(Constants.TRACK_OBJECT);
        fromNowPlaying = requireArguments().getBoolean(ARG_FROM_NOW_PLAYING, false);

        songBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(SongBottomSheetViewModel.class);
        songBottomSheetViewModel.setSong(song);

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

    private void init(View view) {
        ImageView coverSong = view.findViewById(R.id.song_cover_image_view);
        CustomGlideRequest.Builder
                .from(requireContext(), songBottomSheetViewModel.getSong().getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(coverSong);

        TextView titleSong = view.findViewById(R.id.song_title_text_view);
        titleSong.setText(songBottomSheetViewModel.getSong().getTitle());

        titleSong.setSelected(true);

        TextView artistSong = view.findViewById(R.id.song_artist_text_view);
        artistSong.setText(songBottomSheetViewModel.getSong().getArtist());

        ToggleButton favoriteToggle = view.findViewById(R.id.button_favorite);
        favoriteToggle.setChecked(songBottomSheetViewModel.getSong().getStarred() != null);
        favoriteToggle.setOnClickListener(v -> {
            songBottomSheetViewModel.setFavorite(requireContext());
        });
        favoriteToggle.setOnLongClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);

            RatingDialog dialog = new RatingDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
            return true;
        });

        TextView playRadio = view.findViewById(R.id.play_radio_text_view);
        playRadio.setOnClickListener(v -> {
            MediaManager.startQueue(mediaBrowserListenableFuture, song);
            showMiniPlayer();

            songBottomSheetViewModel.getInstantMix(getViewLifecycleOwner(), song).observe(getViewLifecycleOwner(), songs -> {
                MusicUtil.ratingFilter(songs);

                if (songs == null) {
                    dismissBottomSheet();
                    return;
                }

                if (!songs.isEmpty()) {
                    MediaManager.enqueue(mediaBrowserListenableFuture, songs, true);
                    dismissBottomSheet();
                }
            });
        });

        TextView playNext = view.findViewById(R.id.play_next_text_view);
        playNext.setOnClickListener(v -> {
            MediaManager.enqueue(mediaBrowserListenableFuture, song, true);
            showMiniPlayer();
            dismissBottomSheet();
        });

        TextView addToQueue = view.findViewById(R.id.add_to_queue_text_view);
        addToQueue.setOnClickListener(v -> {
            MediaManager.enqueue(mediaBrowserListenableFuture, song, false);
            showMiniPlayer();
            dismissBottomSheet();
        });

        TextView rate = view.findViewById(R.id.rate_text_view);
        rate.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);

            RatingDialog dialog = new RatingDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
        });

        // cassette: manual download is streaming-mode only (see the album
        // sheet). Hidden in offline-first, where sync already owns the file.
        TextView download = view.findViewById(R.id.download_text_view);
        TextView remove = view.findViewById(R.id.remove_text_view);
        if (!com.cappielloantonio.tempo.util.Preferences.isServerMode()) {
            download.setVisibility(View.GONE);
            remove.setVisibility(View.GONE);
        } else {
            download.setOnClickListener(v -> {
                DownloadUtil.getDownloadTracker(requireContext()).download(
                        MappingUtil.mapDownload(song),
                        new Download(song)
                );
                dismissBottomSheet();
            });

            remove.setOnClickListener(v -> {
                DownloadUtil.getDownloadTracker(requireContext()).remove(
                        MappingUtil.mapDownload(song),
                        new Download(song)
                );
                dismissBottomSheet();
            });

            initDownloadUI(download, remove);
        }

        TextView addToPlaylist = view.findViewById(R.id.add_to_playlist_text_view);
        addToPlaylist.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);

            PlaylistChooserDialog dialog = new PlaylistChooserDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
        });

        TextView goToAlbum = view.findViewById(R.id.go_to_album_text_view);
        goToAlbum.setOnClickListener(v -> songBottomSheetViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (album != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                NavHostFragment.findNavController(this).navigate(R.id.albumPageFragment, bundle);
                if (fromNowPlaying) ((MainActivity) requireActivity()).collapseBottomSheetDelayed();
            } else
                Toast.makeText(requireContext(), getString(R.string.song_bottom_sheet_error_retrieving_album), Toast.LENGTH_SHORT).show();

            dismissBottomSheet();
        }));

        goToAlbum.setVisibility(songBottomSheetViewModel.getSong().getAlbumId() != null ? View.VISIBLE : View.GONE);

        TextView goToArtist = view.findViewById(R.id.go_to_artist_text_view);
        goToArtist.setOnClickListener(v -> songBottomSheetViewModel.getArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                NavHostFragment.findNavController(this).navigate(R.id.artistPageFragment, bundle);
                if (fromNowPlaying) ((MainActivity) requireActivity()).collapseBottomSheetDelayed();
            } else
                Toast.makeText(requireContext(), getString(R.string.song_bottom_sheet_error_retrieving_artist), Toast.LENGTH_SHORT).show();

            dismissBottomSheet();
        }));

        goToArtist.setVisibility(songBottomSheetViewModel.getSong().getArtistId() != null ? View.VISIBLE : View.GONE);

        // cassette: Share sends the song's Cassette link through Android's own share sheet
        // (CassetteShareSong), in place of Tempo's hub share. Greyed out with the reason for a
        // song with no video, known before anyone presses it.
        TextView share = view.findViewById(R.id.share_text_view);
        if (CassetteShareSong.isOffered()) {
            share.setVisibility(View.VISIBLE);
            share.setOnClickListener(v -> {
                FragmentActivity activity = requireActivity();
                Integer elapsed = elapsedIfPlaying();
                dismissBottomSheet();
                CassetteShareSong.share(activity, song, elapsed);
            });
            Boolean known = CassetteShareSong.Availability.known(song.getId());
            showShareRow(share, known == null || known);
            if (known == null) {
                CassetteShareSong.Availability.check(song, shareable -> {
                    if (getView() != null) showShareRow(share, shareable == null || shareable);
                });
            }
        } else {
            share.setVisibility(View.GONE);
        }
    }

    private void showShareRow(TextView share, boolean shareable) {
        share.setEnabled(shareable);
        share.setAlpha(shareable ? 1f : 0.45f);
        if (shareable) {
            share.setText(R.string.song_bottom_sheet_share);
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.song_bottom_sheet_share));
        int start = text.length();
        text.append('\n').append(getString(R.string.share_song_unavailable));
        text.setSpan(new RelativeSizeSpan(0.72f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(requireContext().getColor(R.color.subtitleTextColor)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        share.setText(text);
    }

    /** Seconds into the song when it is the one playing and the moment is worth offering. */
    @Nullable
    private Integer elapsedIfPlaying() {
        try {
            if (mediaBrowserListenableFuture == null || !mediaBrowserListenableFuture.isDone()) return null;
            MediaBrowser browser = mediaBrowserListenableFuture.get();
            MediaItem playing = browser.getCurrentMediaItem();
            if (playing == null || !song.getId().equals(playing.mediaId)) return null;
            int at = (int) (browser.getCurrentPosition() / 1000);
            int duration = song.getDuration() != null ? song.getDuration() : 0;
            return at >= 3 && (duration == 0 || at <= duration - 5) ? at : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The mini player comes up after queueing, unless this menu sits over the open player. */
    private void showMiniPlayer() {
        if (!fromNowPlaying) ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
    }

    @Override
    public void onClick(View v) {
        dismissBottomSheet();
    }

    private void dismissBottomSheet() {
        dismiss();
    }

    private void initDownloadUI(TextView download, TextView remove) {
        if (DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(song.getId())) {
            remove.setVisibility(View.VISIBLE);
        } else {
            download.setVisibility(View.VISIBLE);
            remove.setVisibility(View.GONE);
        }
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }
}