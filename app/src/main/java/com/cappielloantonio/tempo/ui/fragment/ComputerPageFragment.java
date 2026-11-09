package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentComputerPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.service.CassetteSyncEngine;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.CassetteRecordTileAdapter;
import com.cappielloantonio.tempo.util.CassetteComputer;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.CassetteType;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.NumberFormat;
import java.util.List;

/**
 * cassette: Your computer, from Settings (iPhone MacDeviceView, e080d4c0, 88355575). What
 * the phone can truthfully say about the computer that holds the music: its name, its state
 * in one word (never a guessed "in sync"), how big the library is, how much of it is on this
 * phone (counted by the cloud on both sides), the account's sync scope (read only), when it
 * last synced, Resync Library, and the records newly in the library that are on this phone.
 */
@UnstableApi
public class ComputerPageFragment extends Fragment {
    private FragmentComputerPageBinding bind;
    private MainActivity activity;
    private ListenableFuture<MediaBrowser> browser;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CassetteRecordTileAdapter fresh;
    @Nullable private Integer onPhoneAlbums;
    private int keptOff;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentComputerPageBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.setSupportActionBar(bind.computerToolbar);
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        bind.computerToolbar.setTitle("");
        bind.computerToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        bind.computerScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (bind == null) return;
            // the bar fills in as the name's top passes under it
            int bar = bind.computerToolbar.getHeight();
            int nameTop = bind.computerName.getTop() + ((View) bind.computerName.getParent()).getTop();
            float t = bar <= 0 ? 1f : Math.max(0f, Math.min(1f, (y - (nameTop - 2 * bar)) / (float) bar));
            bind.computerBarBackdrop.setAlpha(t);
            bind.computerToolbar.setTitle(t >= 1f ? CassetteComputer.name() : "");
        });

        fresh = new CassetteRecordTileAdapter(new CassetteRecordTileAdapter.Listener() {
            @Override
            public void onOpen(AlbumID3 album) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                activity.navController.navigate(R.id.albumPageFragment, bundle);
            }

            @Override
            public void onPlay(AlbumID3 album) {
                MediaManager.playAlbum(browser, album.getId(), false);
                activity.setBottomSheetInPeek(true);
            }
        }, CassetteRecordTileAdapter.Subtitle.ARTIST);
        bind.computerNewRecords.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.computerNewRecords.setAdapter(fresh);

        bind.computerResync.setOnClickListener(v -> {
            CassetteSyncEngine.kick(requireContext());
            Toast.makeText(requireContext(), R.string.cassette_resync_started, Toast.LENGTH_SHORT).show();
        });

        paint();
        CassetteComputer.refresh(summary -> {
            if (bind != null) {
                paint();
                loadNew();
            }
        });
        loadDevice();
        loadNew();
    }

    /** Name, state, totals, and the card's rows as far as they are known. */
    private void paint() {
        if (bind == null) return;
        bind.computerName.setText(CassetteComputer.name());
        bind.computerKicker.setCompoundDrawablesRelativeWithIntrinsicBounds(
                CassetteComputer.isLaptop() ? R.drawable.ic_lucide_laptop : R.drawable.ic_lucide_monitor, 0, 0, 0);
        // no word until the heartbeat has been read
        String state = CassetteComputer.state();
        bind.computerState.setVisibility(state != null ? View.VISIBLE : View.GONE);
        bind.computerState.setText(state);
        CassetteComputer.Summary s = CassetteComputer.last();
        boolean known = s != null && s.getRegistered();
        bind.computerMeta.setVisibility(known ? View.VISIBLE : View.GONE);
        if (known) {
            NumberFormat n = NumberFormat.getInstance();
            bind.computerMeta.setText(getResources().getQuantityString(R.plurals.cassette_album_count, s.getAlbums(), s.getAlbums())
                    .replace(String.valueOf(s.getAlbums()), n.format(s.getAlbums()))
                    + " · " + getResources().getQuantityString(R.plurals.cassette_song_count, s.getTracks(), s.getTracks())
                    .replace(String.valueOf(s.getTracks()), n.format(s.getTracks())));
        }
        String ago = CassetteComputer.syncedAgo();
        bind.computerSyncedRow.setVisibility(ago != null ? View.VISIBLE : View.GONE);
        bind.computerSynced.setText(ago);
        paintCoverage();
    }

    /** "All 163 albums on this phone" / "160 of 163": the cloud's count on both sides, less the ones kept off. */
    private void paintCoverage() {
        if (bind == null) return;
        CassetteComputer.Summary s = CassetteComputer.last();
        if (s == null || !s.getRegistered() || s.getAlbums() <= 0 || onPhoneAlbums == null) {
            bind.computerCoverage.setVisibility(View.GONE);
            return;
        }
        int of = Math.max(1, s.getAlbums() - keptOff);
        int have = Math.min(onPhoneAlbums, of);
        NumberFormat n = NumberFormat.getInstance();
        bind.computerCoverageLine.setText(have >= of
                ? getString(R.string.cassette_all_on_phone, n.format(of))
                : getString(R.string.cassette_some_on_phone, n.format(have), n.format(of)));
        bind.computerCoverageMeter.setProgress(Math.round(1000f * have / of));
        bind.computerCoverage.setVisibility(View.VISIBLE);
    }

    /** This phone as the cloud sees it: the sync scope and how many of the library's albums are here. */
    private void loadDevice() {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null) return;
        new Thread(() -> {
            String scope = null;
            Integer count = null;
            int off = 0;
            try {
                String json = CassetteSyncApi.getThisDevice(bearer);
                if (json != null) {
                    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                    scope = o.has("scope") && !o.get("scope").isJsonNull() ? o.get("scope").getAsString() : "everything";
                    if (o.has("onPhoneAlbums") && o.get("onPhoneAlbums").isJsonArray()) count = o.getAsJsonArray("onPhoneAlbums").size();
                    if (o.has("keptOffAlbums") && o.get("keptOffAlbums").isJsonArray()) off = o.getAsJsonArray("keptOffAlbums").size();
                }
            } catch (Exception e) {
                android.util.Log.w("ComputerPage", "device read failed", e);
            }
            final String sc = scope;
            final Integer c = count;
            final int k = off;
            main.post(() -> {
                if (bind == null) return;
                onPhoneAlbums = c;
                keptOff = k;
                bind.computerSyncsRow.setVisibility(sc != null ? View.VISIBLE : View.GONE);
                if (sc != null) bind.computerSyncs.setText("selective".equals(sc) ? R.string.cassette_syncs_selective : R.string.cassette_syncs_everything);
                paintCoverage();
            });
        }, "cassette-computer-device").start();
    }

    /** NEW IN YOUR LIBRARY: the computer's newest records that are on this phone; the first three make the fan. */
    private void loadNew() {
        new Thread(() -> {
            List<AlbumID3> albums = CassetteComputer.newOnPhone(12);
            main.post(() -> {
                if (bind == null) return;
                fresh.setItems(albums);
                bind.computerNew.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
                CassetteType.twoVoice(bind.computerNewHeading, getString(R.string.cassette_new_in_library), getString(R.string.cassette_lately),
                        ContextCompat.getColor(requireContext(), R.color.cs_ink2));
                com.google.android.material.imageview.ShapeableImageView[] fan = {bind.computerFanLeft, bind.computerFanMiddle, bind.computerFanRight};
                // the newest in the middle, lifted
                int[] order = {1, 0, 2};
                for (int i = 0; i < 3; i++) {
                    AlbumID3 a = order[i] < albums.size() ? albums.get(order[i]) : null;
                    fan[i].setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
                    if (a != null) CustomGlideRequest.Builder.from(requireContext(), a.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                            .build().into(fan[i]);
                }
                bind.computerFan.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }, "cassette-computer-new").start();
    }

    @Override
    public void onStart() {
        super.onStart();
        browser = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
        activity.setBottomNavigationBarVisibility(true);
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
