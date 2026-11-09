package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentStationPageBinding;
import com.cappielloantonio.tempo.databinding.ItemRadioPlayBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.CassetteTileAdapter;
import com.cappielloantonio.tempo.util.CassetteNowPlaying;
import com.cappielloantonio.tempo.util.CassetteRadio;
import com.cappielloantonio.tempo.util.CassetteRadioLive;
import com.cappielloantonio.tempo.util.CassetteRadioSaved;
import com.cappielloantonio.tempo.util.CassetteType;
import com.cappielloantonio.tempo.util.Constants;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: a station's page (iPhone RadioStationVC, 535f7b63, 9e68a3bf, d31e451b). Its
 * picture, NAME, "Network · City", PLAY (STOP while it is on) and SAVE; ON AIR: the show,
 * the song on now, what came before and what's next, read every 30 seconds while the page
 * is up; then MORE LIKE *station*. A song we carry opens its record here; one we don't
 * opens its page on Cassette; a long press copies it. The "…" has the station's site and
 * Support, never an editor.
 */
@UnstableApi
public class StationPageFragment extends Fragment {
    public static final String SLUG = "cassette_station_slug";

    private FragmentStationPageBinding bind;
    private MainActivity activity;
    private ListenableFuture<MediaBrowser> browser;
    private CassetteRadio.Station station;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlaysAdapter plays = new PlaysAdapter();
    private final CassetteTileAdapter similar = new CassetteTileAdapter();
    private final Runnable tick = this::load;

    /** Open a station's page from anywhere. */
    public static void open(Fragment from, String slug) {
        Bundle bundle = new Bundle();
        bundle.putString(SLUG, slug);
        androidx.navigation.fragment.NavHostFragment.findNavController(from).navigate(R.id.stationPageFragment, bundle);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentStationPageBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String slug = getArguments() != null ? getArguments().getString(SLUG) : null;
        station = CassetteRadio.bySlug(requireContext(), slug);
        if (station == null) {
            activity.navController.navigateUp();
            return;
        }

        activity.setSupportActionBar(bind.stationToolbar);
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        bind.stationToolbar.setTitle("");
        bind.stationToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        bind.stationToolbar.getMenu().add(0, 1, 0, R.string.player_more).setIcon(R.drawable.ic_more_vert)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (bind.stationToolbar.getMenu().findItem(1).getIcon() != null) {
            bind.stationToolbar.getMenu().findItem(1).getIcon().setTint(ContextCompat.getColor(requireContext(), R.color.cs_ink));
        }
        bind.stationToolbar.setOnMenuItemClickListener(item -> {
            showMenu(bind.stationToolbar);
            return true;
        });
        bind.stationScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (bind == null) return;
            int past = bind.stationName.getBottom();
            float t = past <= 0 ? 1f : Math.max(0f, Math.min(1f, y / (float) past));
            bind.stationBarBackdrop.setAlpha(t);
            bind.stationToolbar.setTitle(t >= 1f ? station.getName() : "");
        });

        // the picture, or the name as word art on its own colour
        bind.stationName.setText(station.getName());
        bind.stationKicker.setText(station.getLabel());
        if (station.getImage() != null) {
            bind.stationWordArt.setVisibility(View.GONE);
            CustomGlideRequest.Builder.from(requireContext(), station.getImage(), CustomGlideRequest.ResourceType.Radio)
                    .build().into(bind.stationArt);
            bind.stationGlow.setRecipe(com.cappielloantonio.tempo.ui.view.CoverGlowView.PAGE);
            bind.stationGlow.setCover(station.getImage(), CustomGlideRequest.ResourceType.Radio);
        } else {
            bind.stationArt.setImageDrawable(new android.graphics.drawable.ColorDrawable(station.accentColor()));
            bind.stationWordArt.setVisibility(View.VISIBLE);
            bind.stationWordArt.setText(station.getName());
        }

        bind.stationPlay.setOnClickListener(v -> {
            if (onAir() && CassetteNowPlaying.current().isPlaying) {
                MediaManager.togglePlayPause(browser);
            } else {
                MediaManager.startRadio(browser, station.toInternetRadioStation());
                activity.setBottomSheetInPeek(true);
            }
        });
        CassetteNowPlaying.live().observe(getViewLifecycleOwner(), state -> paintPlay());
        bind.stationSave.setOnClickListener(v -> toggleSave());
        CassetteRadioSaved.saved(requireContext()).observe(getViewLifecycleOwner(), saved -> paintSave());

        bind.stationPlays.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.stationPlays.setAdapter(plays);
        bind.stationSimilarTiles.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.stationSimilarTiles.setAdapter(similar);
        CassetteType.twoVoice(bind.stationSimilarHeading, getString(R.string.cassette_more_like), station.getName(),
                ContextCompat.getColor(requireContext(), R.color.cs_ink2));
    }

    private boolean onAir() {
        return CassetteNowPlaying.current().isCurrent("cassette-radio-" + station.getSlug());
    }

    private void paintPlay() {
        if (bind == null) return;
        boolean playing = onAir() && CassetteNowPlaying.current().isPlaying;
        bind.stationPlay.setText(playing ? R.string.cassette_stop : R.string.cassette_play);
        bind.stationPlay.setIconResource(playing ? R.drawable.cs_pause_glyph : R.drawable.cs_play_glyph);
    }

    private void paintSave() {
        if (bind == null) return;
        boolean saved = CassetteRadioSaved.isSaved(requireContext(), station.getSlug());
        bind.stationSave.setText(saved ? R.string.cassette_saved : R.string.cassette_save);
        bind.stationSave.setIconResource(saved ? R.drawable.ic_done : R.drawable.ic_add);
    }

    private void toggleSave() {
        boolean save = !CassetteRadioSaved.isSaved(requireContext(), station.getSlug());
        android.content.Context app = requireContext().getApplicationContext();
        CassetteRadioSaved.set(requireContext(), station.getSlug(), save, ok -> {
            if (!ok) Toast.makeText(app, R.string.cassette_save_failed, Toast.LENGTH_SHORT).show();
        });
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(new android.view.ContextThemeWrapper(requireContext(), R.style.Cassette_NeutralTintContext), anchor, android.view.Gravity.END);
        if (station.site() != null) popup.getMenu().add(0, 1, 0, getString(R.string.cassette_go_to_site, station.site()));
        if (station.getSupportUrl() != null) popup.getMenu().add(0, 2, 1, getString(R.string.cassette_support_station, station.getName()));
        popup.setOnMenuItemClickListener(item -> {
            String url = item.getItemId() == 1 ? station.getHomepage() : station.getSupportUrl();
            if (url != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        });
        popup.show();
    }

    // ------------------------------------------------------------------
    // On air

    private void load() {
        String slug = station.getSlug();
        new Thread(() -> {
            CassetteRadio.Page page = CassetteRadio.page(slug);
            main.post(() -> {
                if (bind == null) return;
                if (page != null) draw(page);
                main.removeCallbacks(tick);
                if (isResumed()) main.postDelayed(tick, 30_000);
            });
        }, "cassette-station-page").start();
    }

    private void draw(CassetteRadio.Page page) {
        CassetteRadioLive.Live live = page.getLive();
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(requireContext());
        String when = live != null ? CassetteRadioLive.whenLine(live, System.currentTimeMillis(), t -> clock.format(new java.util.Date(t))) : null;

        boolean show = live != null && live.getShow() != null;
        bind.stationShow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bind.stationShowName.setText(live.getShow());
            bind.stationShowWhen.setText(when);
            bind.stationShowWhen.setVisibility(when != null ? View.VISIBLE : View.GONE);
            Glide.with(this).load(live.getArtwork() != null && page.getNow() == null ? live.getArtwork() : station.getImage())
                    .into(bind.stationShowImage);
        }
        List<CassetteRadio.Play> rows = new ArrayList<>();
        if (page.getNow() != null) rows.add(page.getNow());
        rows.addAll(page.getRecent());
        plays.set(rows, page.getNow() != null);
        // the next show (or song) when there is no show row to carry it
        String next = live != null ? live.getNext() : null;
        bind.stationNext.setVisibility(!show && next != null ? View.VISIBLE : View.GONE);
        if (next != null) bind.stationNext.setText(getString(R.string.cassette_next_line, next));
        bind.stationOnAir.setVisibility(show || !rows.isEmpty() ? View.VISIBLE : View.GONE);

        List<CassetteTileAdapter.Tile> tiles = new ArrayList<>();
        for (String s : page.getSimilar()) {
            CassetteRadio.Station other = CassetteRadio.bySlug(requireContext(), s);
            if (other == null) continue;
            tiles.add(CassetteTileAdapter.Tile.station(other,
                    () -> open(this, other.getSlug()),
                    () -> {
                        MediaManager.startRadio(browser, other.toInternetRadioStation());
                        activity.setBottomSheetInPeek(true);
                    }));
        }
        similar.setItems(tiles);
        bind.stationSimilar.setVisibility(tiles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A song the station played: its record here when we carry it, else its page on Cassette. */
    private void openSong(CassetteRadio.Play play) {
        new Thread(() -> {
            AlbumID3 album = CassetteRadio.onPhone(play.getAlbumTitle(), play.getAlbumArtist() != null ? play.getAlbumArtist() : play.getArtist());
            main.post(() -> {
                if (bind == null) return;
                if (album != null) {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                    activity.navController.navigate(R.id.albumPageFragment, bundle);
                } else if (play.getAlbumSlug() != null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://cassette.digital/album/" + Uri.encode(play.getAlbumSlug()))));
                }
            });
        }, "cassette-station-song").start();
    }

    private void copySong(CassetteRadio.Play play) {
        android.content.ClipboardManager clip = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        String text = play.getArtist().isEmpty() ? play.getTitle() : play.getTitle() + " · " + play.getArtist();
        clip.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), text));
        Toast.makeText(requireContext(), R.string.cassette_copied, Toast.LENGTH_SHORT).show();
    }

    private String ago(@Nullable Long at) {
        if (at == null) return "";
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (min < 1) return getString(R.string.cassette_just_now);
        if (min < 60) return getResources().getQuantityString(R.plurals.cassette_minutes_ago, (int) min, (int) min);
        int hr = (int) (min / 60);
        return getResources().getQuantityString(R.plurals.cassette_hours_ago, hr, hr);
    }

    private final class PlaysAdapter extends RecyclerView.Adapter<PlaysAdapter.Holder> {
        private List<CassetteRadio.Play> items = new ArrayList<>();
        private boolean firstIsNow;

        void set(List<CassetteRadio.Play> next, boolean nowFirst) {
            items = next;
            firstIsNow = nowFirst;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemRadioPlayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            CassetteRadio.Play p = items.get(position);
            boolean now = position == 0 && firstIsNow;
            h.bind.playTitle.setText(p.getTitle());
            h.bind.playArtist.setText(p.getArtist());
            h.bind.playWhen.setText(now ? getString(R.string.cassette_now) : ago(p.getPlayedAt()));
            h.bind.playWhen.setTextColor(ContextCompat.getColor(h.itemView.getContext(), now ? R.color.cs_accent : R.color.cs_ink2));
            Glide.with(h.itemView).load(p.getCover()).into(h.bind.playCover);
            h.itemView.setOnClickListener(v -> openSong(p));
            h.itemView.setOnLongClickListener(v -> {
                copySong(p);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemRadioPlayBinding bind;

            Holder(ItemRadioPlayBinding bind) {
                super(bind.getRoot());
                this.bind = bind;
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        browser = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
        activity.setBottomNavigationBarVisibility(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (station != null) load();
    }

    @Override
    public void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    public void onStop() {
        MediaBrowser.releaseFuture(browser);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }
}
