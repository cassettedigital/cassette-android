package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.RepeatModeUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager2.widget.ViewPager2;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerControllerBinding;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.PlaylistChooserDialog;
import com.cappielloantonio.tempo.ui.dialog.RatingDialog;
import com.cappielloantonio.tempo.ui.dialog.TrackInfoDialog;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.SongBottomSheetDialog;
import com.cappielloantonio.tempo.ui.fragment.pager.PlayerControllerHorizontalPager;
import com.cappielloantonio.tempo.util.CassetteShareSong;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.elevation.SurfaceColors;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;

@UnstableApi
public class PlayerControllerFragment extends Fragment {
    private static final String TAG = "PlayerCoverFragment";

    private InnerFragmentPlayerControllerBinding bind;
    private ViewPager2 playerMediaCoverViewPager;
    private android.widget.ImageView playerBlurredBackground;
    private ImageButton buttonFavorite;
    private TextView playerMediaTitleLabel;
    private TextView playerArtistNameLabel;
    private Button playbackSpeedButton;
    private ToggleButton skipSilenceToggleButton;
    private Chip playerMediaExtension;
    private TextView playerMediaBitrate;
    private ConstraintLayout playerQuickActionView;
    private ImageButton playerOpenQueueButton;
    private ImageButton playerTrackInfo;
    @Nullable
    private ImageButton playerMoreButton;

    private MainActivity activity;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;

    // cassette: the room. The cover's tiny pre-blurred copy fills the sheet over
    // the cover's hue, under glass of the page's own paper (thinnest around the
    // cover), the iPhone's recipe (AmbientCoverBackdrop, bac20c78).
    private static final com.cappielloantonio.tempo.ui.view.CoverGlowView.Recipe ROOM =
            new com.cappielloantonio.tempo.ui.view.CoverGlowView.Recipe(2.4f, -0.05f, 0f, 0.75f,
                    java.util.Arrays.asList(
                            new kotlin.Triple<>(R.color.cs_bg, 0.42f, 0f),
                            new kotlin.Triple<>(R.color.cs_bg, 0.22f, 0.3f),
                            new kotlin.Triple<>(R.color.cs_bg, 0.55f, 0.62f),
                            new kotlin.Triple<>(R.color.cs_bg, 0.8f, 1f)),
                    false, 0f);
    @Nullable
    private com.cappielloantonio.tempo.ui.view.CoverGlowView playerGlow;
    @Nullable
    private View playerRoom;
    private String litCoverId;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = InnerFragmentPlayerControllerBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        init();
        initQuickActionView();
        initCoverLyricsSlideView();
        initMediaListenable();
        initMediaLabelButton();
        initArtistLabelButton();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
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

    private void init() {
        playerMediaCoverViewPager = bind.getRoot().findViewById(R.id.player_media_cover_view_pager);
        playerGlow = bind.getRoot().findViewById(R.id.player_glow);
        playerRoom = bind.getRoot().findViewById(R.id.player_room);
        if (playerGlow != null) playerGlow.setRecipe(ROOM);
        buttonFavorite = bind.getRoot().findViewById(R.id.button_favorite);
        playerMediaTitleLabel = bind.getRoot().findViewById(R.id.player_media_title_label);
        playerArtistNameLabel = bind.getRoot().findViewById(R.id.player_artist_name_label);
        playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        skipSilenceToggleButton = bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button);
        playerMediaExtension = bind.getRoot().findViewById(R.id.player_media_extension);
        playerMediaBitrate = bind.getRoot().findViewById(R.id.player_media_bitrate);
        playerQuickActionView = bind.getRoot().findViewById(R.id.player_quick_action_view);
        playerOpenQueueButton = bind.getRoot().findViewById(R.id.player_open_queue_button);
        playerTrackInfo = bind.getRoot().findViewById(R.id.player_info_track);
        // portrait only: the landscape layout has no ⋮
        playerMoreButton = bind.getRoot().findViewById(R.id.player_more_button);
    }

    private void initQuickActionView() {
        // cassette: flat de-saturated container (was SurfaceColors elevation-8,
        // which washes orange-brown from the tonal tint). Matches the mini-player.
        playerQuickActionView.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        playerOpenQueueButton.setOnClickListener(view -> {
            PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) requireActivity().getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
            if (playerBottomSheetFragment != null) {
                playerBottomSheetFragment.goToQueuePage();
            }
        });
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();

                bind.nowPlayingMediaControllerView.setPlayer(mediaBrowser);

                setMediaControllerListener(mediaBrowser);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaControllerListener(MediaBrowser mediaBrowser) {
        setMediaControllerUI(mediaBrowser);
        setMetadata(mediaBrowser.getMediaMetadata());
        setMediaInfo(mediaBrowser.getMediaMetadata());
        updateControlAccents(mediaBrowser);
        liftRoom(mediaBrowser.isPlaying());

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setMediaInfo(mediaMetadata);
                updateControlAccents(mediaBrowser);
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                updateControlAccents(mediaBrowser);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                liftRoom(isPlaying);
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                updateControlAccents(mediaBrowser);
            }
        });
    }

    // cassette: media3's PlayerControlView shows shuffle/repeat state only by
    // baked opacity on its own drawables. We override those with flat Lucide
    // vectors, so the engaged state would be invisible. Reflect it the iOS way
    // instead: accent (colorPrimary) when the toggle is on, cream
    // (colorOnSurface) when off — resolved from the theme so it follows
    // whichever of the 7 skins the account is on. media3 swaps the drawable on
    // state changes, but ImageView re-applies its colour filter last (in
    // updateDrawable), so a setColorFilter survives the swap where a tint list
    // gets clobbered by AppCompat's app:tint. The app:tint on these two buttons
    // is removed in XML so nothing competes.
    private void updateControlAccents(Player player) {
        if (bind == null) return;
        View root = bind.getRoot();
        int active = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary);
        int inactive = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface);

        ImageButton shuffle = root.findViewById(R.id.exo_shuffle);
        if (shuffle != null) {
            shuffle.setColorFilter(player.getShuffleModeEnabled() ? active : inactive, PorterDuff.Mode.SRC_IN);
        }

        ImageButton repeat = root.findViewById(R.id.exo_repeat_toggle);
        if (repeat != null) {
            repeat.setColorFilter(player.getRepeatMode() != Player.REPEAT_MODE_OFF ? active : inactive, PorterDuff.Mode.SRC_IN);
        }
    }

    /** Playing lifts the glow (0.75); paused settles it (0.62). */
    private void liftRoom(boolean playing) {
        if (playerGlow == null) return;
        playerGlow.animate().alpha(playing ? 1f : 0.83f).setDuration(600).start();
    }

    private void setMetadata(MediaMetadata mediaMetadata) {
        // the player's caps title; the artist in the serif, in the record's colour
        String title = mediaMetadata.title != null ? String.valueOf(mediaMetadata.title) : "";
        playerMediaTitleLabel.setText(title.toUpperCase(java.util.Locale.getDefault()));
        playerArtistNameLabel.setText(String.valueOf(mediaMetadata.artist));

        String coverArtId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("coverArtId") : null;
        if (playerBlurredBackground != null && playerBlurredBackground.getVisibility() == android.view.View.VISIBLE && coverArtId != null) {
            com.cappielloantonio.tempo.glide.CustomGlideRequest.Builder
                    .from(requireContext(), coverArtId, com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album)
                    .build()
                    .into(playerBlurredBackground);
        }
        lightRoom(coverArtId);

        playerMediaTitleLabel.setSelected(true);
        playerArtistNameLabel.setSelected(true);

        playerMediaTitleLabel.setVisibility(mediaMetadata.title != null && !Objects.equals(mediaMetadata.title, "") ? View.VISIBLE : View.GONE);
        playerArtistNameLabel.setVisibility(mediaMetadata.artist != null && !Objects.equals(mediaMetadata.artist, "") ? View.VISIBLE : View.GONE);
    }

    /** The record lights the room, the artist line and the scrubber; never orange. */
    private void lightRoom(@Nullable String coverArtId) {
        if (bind == null || Objects.equals(coverArtId, litCoverId)) return;
        litCoverId = coverArtId;
        applyRecordTint(com.cappielloantonio.tempo.util.CoverTint.NEUTRAL);
        if (playerGlow != null) playerGlow.setCover(coverArtId, com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album);
        if (coverArtId == null) return;
        com.cappielloantonio.tempo.util.CoverTint.resolve(requireContext(), coverArtId,
                com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album, tint -> {
                    if (Objects.equals(coverArtId, litCoverId)) applyRecordTint(tint);
                });
    }

    private void applyRecordTint(com.cappielloantonio.tempo.util.CoverTint tint) {
        if (bind == null) return;
        int text = tint.text(requireContext());
        playerArtistNameLabel.setTextColor(text);
        if (playerRoom != null) playerRoom.setBackgroundColor(tint.room());
        androidx.media3.ui.DefaultTimeBar bar = bind.getRoot().findViewById(R.id.exo_progress);
        if (bar != null) {
            bar.setPlayedColor(text);
            bar.setScrubberColor(text);
        }
        com.cappielloantonio.tempo.util.CoverTint.setNowPlaying(tint);
    }

    private void setMediaInfo(MediaMetadata mediaMetadata) {
        if (mediaMetadata.extras != null) {
            String extension = mediaMetadata.extras.getString("suffix", "Unknown format");
            String bitrate = mediaMetadata.extras.getInt("bitrate", 0) != 0 ? mediaMetadata.extras.getInt("bitrate", 0) + "kbps" : "Original";

            playerMediaExtension.setText(extension);

            if (bitrate.equals("Original")) {
                playerMediaBitrate.setVisibility(View.GONE);
            } else {
                playerMediaBitrate.setVisibility(View.VISIBLE);
                playerMediaBitrate.setText(bitrate);
            }
        }

        boolean isTranscodingExtension = !MusicUtil.getTranscodingFormatPreference().equals("raw");
        boolean isTranscodingBitrate = !MusicUtil.getBitratePreference().equals("0");

        if (isTranscodingExtension || isTranscodingBitrate) {
            playerMediaExtension.setText("Transcoding");
            playerMediaBitrate.setText("requested");
        }

        playerTrackInfo.setOnClickListener(view -> {
            TrackInfoDialog dialog = new TrackInfoDialog(mediaMetadata);
            dialog.show(activity.getSupportFragmentManager(), null);
        });
    }

    private void setMediaControllerUI(MediaBrowser mediaBrowser) {
        initPlaybackSpeedButton(mediaBrowser);

        if (mediaBrowser.getMediaMetadata().extras != null) {
            switch (mediaBrowser.getMediaMetadata().extras.getString("type", Constants.MEDIA_TYPE_MUSIC)) {
                case Constants.MEDIA_TYPE_PODCAST:
                    bind.getRoot().setShowShuffleButton(false);
                    bind.getRoot().setShowRewindButton(true);
                    bind.getRoot().setShowPreviousButton(false);
                    bind.getRoot().setShowNextButton(false);
                    bind.getRoot().setShowFastForwardButton(true);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    if (playerMoreButton != null) playerMoreButton.setVisibility(View.INVISIBLE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_RADIO:
                    bind.getRoot().setShowShuffleButton(false);
                    bind.getRoot().setShowRewindButton(false);
                    bind.getRoot().setShowPreviousButton(false);
                    bind.getRoot().setShowNextButton(false);
                    bind.getRoot().setShowFastForwardButton(false);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    if (playerMoreButton != null) playerMoreButton.setVisibility(View.INVISIBLE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_MUSIC:
                default:
                    bind.getRoot().setShowShuffleButton(true);
                    bind.getRoot().setShowRewindButton(false);
                    bind.getRoot().setShowPreviousButton(true);
                    bind.getRoot().setShowNextButton(true);
                    bind.getRoot().setShowFastForwardButton(false);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL | RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    if (playerMoreButton != null) playerMoreButton.setVisibility(View.VISIBLE);
                    resetPlaybackParameters(mediaBrowser);
                    break;
            }
        }
    }

    private void initCoverLyricsSlideView() {
        playerMediaCoverViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        playerMediaCoverViewPager.setAdapter(new PlayerControllerHorizontalPager(this));
        // cassette: lyrics are switched off, as on the iPhone (Cassette.lyricsEnabled
        // = false there); the cover no longer swipes sideways to a lyrics page.
        playerMediaCoverViewPager.setUserInputEnabled(false);

        playerMediaCoverViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) requireActivity().getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");

                if (position == 0) {
                    activity.setBottomSheetDraggableState(true);

                    if (playerBottomSheetFragment != null) {
                        playerBottomSheetFragment.setPlayerControllerVerticalPagerDraggableState(true);
                    }
                } else if (position == 1) {
                    activity.setBottomSheetDraggableState(false);

                    if (playerBottomSheetFragment != null) {
                        playerBottomSheetFragment.setPlayerControllerVerticalPagerDraggableState(false);
                    }
                }
            }
        });
    }

    private void initMediaListenable() {
        playerBottomSheetViewModel.getLiveMedia().observe(getViewLifecycleOwner(), media -> {
            if (media != null) {
                // cassette: heart → add-to-playlist (favorites removed, iOS parity)
                buttonFavorite.setOnClickListener(v -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.TRACK_OBJECT, media);

                    PlaylistChooserDialog dialog = new PlaylistChooserDialog();
                    dialog.setArguments(bundle);
                    dialog.show(requireActivity().getSupportFragmentManager(), null);
                });

                // cassette: ⋮ opens the song's menu over the player, Share first. Whether the
                // song can be shared is asked as it comes up, so the menu already knows.
                if (playerMoreButton != null) {
                    playerMoreButton.setOnClickListener(v -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Constants.TRACK_OBJECT, media);
                        bundle.putBoolean(SongBottomSheetDialog.ARG_FROM_NOW_PLAYING, true);
                        NavHostFragment.findNavController(this).navigate(R.id.songBottomSheetDialog, bundle);
                    });
                }
                if (CassetteShareSong.isOffered()) CassetteShareSong.Availability.check(media, null);

                if (getActivity() != null) {
                    playerBottomSheetViewModel.refreshMediaInfo(requireActivity(), media);
                }
            }
        });
    }

    private void initMediaLabelButton() {
        playerBottomSheetViewModel.getLiveAlbum().observe(getViewLifecycleOwner(), album -> {
            if (album != null) {
                playerMediaTitleLabel.setOnClickListener(view -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                    NavHostFragment.findNavController(this).navigate(R.id.albumPageFragment, bundle);
                    activity.collapseBottomSheetDelayed();
                });
            }
        });
    }

    private void initArtistLabelButton() {
        playerBottomSheetViewModel.getLiveArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                playerArtistNameLabel.setOnClickListener(view -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                    NavHostFragment.findNavController(this).navigate(R.id.artistPageFragment, bundle);
                    activity.collapseBottomSheetDelayed();
                });
            }
        });
    }

    private void initPlaybackSpeedButton(MediaBrowser mediaBrowser) {
        playbackSpeedButton.setOnClickListener(view -> {
            float currentSpeed = Preferences.getPlaybackSpeed();

            if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_080) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_100));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_100));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_100);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_100) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_125));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_125));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_125);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_125) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_150));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_150));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_150);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_150) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_175));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_175));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_175);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_175) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_200));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_200));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_200);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_200) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_080));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_080));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_080);
            }
        });

        skipSilenceToggleButton.setOnClickListener(view -> {
            Preferences.setSkipSilenceMode(!skipSilenceToggleButton.isChecked());
        });
    }

    public void goToControllerPage() {
        playerMediaCoverViewPager.setCurrentItem(0, false);
    }

    public void goToLyricsPage() {
        playerMediaCoverViewPager.setCurrentItem(1, true);
    }

    private void setPlaybackParameters(MediaBrowser mediaBrowser) {
        Button playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        float currentSpeed = Preferences.getPlaybackSpeed();
        boolean skipSilence = Preferences.isSkipSilenceMode();

        mediaBrowser.setPlaybackParameters(new PlaybackParameters(currentSpeed));
        playbackSpeedButton.setText(getString(R.string.player_playback_speed, currentSpeed));

        // TODO Skippare il silenzio
        skipSilenceToggleButton.setChecked(skipSilence);
    }

    private void resetPlaybackParameters(MediaBrowser mediaBrowser) {
        mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_100));
        // TODO Resettare lo skip del silenzio
    }
}