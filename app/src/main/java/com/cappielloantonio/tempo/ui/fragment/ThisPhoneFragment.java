package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.databinding.FragmentThisPhoneBinding;
import com.cappielloantonio.tempo.databinding.ItemKeptOffTileBinding;
import com.cappielloantonio.tempo.databinding.ItemPhoneAlbumBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.CassetteSyncEngine;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.CassetteAlbumGroupKey;
import com.cappielloantonio.tempo.util.CassetteOnPhone;
import com.cappielloantonio.tempo.util.CassetteRecords;
import com.cappielloantonio.tempo.util.CassetteSyncApi;
import com.cappielloantonio.tempo.util.CassetteType;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * cassette: This phone, from Settings (iPhone ThisPhoneView, e080d4c0). What is here and
 * what it weighs, biggest first, because the reason to open it is room. KEPT OFF lists the
 * records removed from this phone (from a fresh read of this phone on the cloud); a tap puts
 * one back, and the phone reports what it holds so the cloud queues it again. Long-press a
 * record in BIGGEST to remove it ("It stays on your computer.").
 */
@UnstableApi
public class ThisPhoneFragment extends Fragment {
    private FragmentThisPhoneBinding bind;
    private MainActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlbumsAdapter albums = new AlbumsAdapter();
    private final KeptOffAdapter keptOff = new KeptOffAdapter();
    /** Put-backs confirmed since the last read of this phone: hidden until a fresh read speaks for them. */
    private final Set<String> putBack = new HashSet<>();
    private final Set<String> puttingBack = new HashSet<>();

    /** One record on this phone, and what it weighs. */
    static final class PhoneAlbum {
        String id, name, artist, cover;
        int songs;
        long bytes;
    }

    /** One record kept off this phone. */
    static final class KeptOff {
        String groupKey, name, cover;
        List<String> targetIds = new ArrayList<>();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentThisPhoneBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.setSupportActionBar(bind.thisPhoneToolbar);
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        bind.thisPhoneToolbar.setTitle("");
        bind.thisPhoneToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        bind.thisPhoneScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
            if (bind == null) return;
            int past = bind.thisPhoneTitle.getBottom();
            float t = past <= 0 ? 1f : Math.max(0f, Math.min(1f, y / (float) past));
            bind.thisPhoneBarBackdrop.setAlpha(t);
            bind.thisPhoneToolbar.setTitle(t >= 1f ? getString(R.string.cassette_this_phone) : "");
        });

        int ink2 = ContextCompat.getColor(requireContext(), R.color.cs_ink2);
        CassetteType.twoVoice(bind.thisPhoneKeptOffHeading, getString(R.string.cassette_kept_off), getString(R.string.cassette_kept_off_answer), ink2);
        CassetteType.twoVoice(bind.thisPhoneBiggestHeading, getString(R.string.cassette_biggest), getString(R.string.cassette_biggest_answer), ink2);

        bind.thisPhoneAlbums.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.thisPhoneAlbums.setAdapter(albums);
        bind.thisPhoneKeptOffRecords.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.thisPhoneKeptOffRecords.setAdapter(keptOff);

        bind.thisPhoneQuality.setText(qualityLabel());
        long free = freeBytes();
        bind.thisPhoneFreeRow.setVisibility(free > 0 ? View.VISIBLE : View.GONE);
        bind.thisPhoneFree.setText(Formatter.formatShortFileSize(requireContext(), free));

        loadPhone();
        loadKeptOff();
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private String qualityLabel() {
        switch (Preferences.getCassetteDownloadQuality()) {
            case "lossless": return getString(R.string.settings_download_quality_lossless);
            case "efficient": return getString(R.string.settings_download_quality_efficient);
            default: return getString(R.string.settings_download_quality_high);
        }
    }

    /** The room left on the volume the music is kept on. */
    private long freeBytes() {
        try {
            java.io.File dir = DownloadUtil.getDownloadDirectory(requireContext());
            return dir != null ? new StatFs(dir.getPath()).getAvailableBytes() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // What is here

    private void loadPhone() {
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            // what each song's file really weighs, from the download store; the server's size when unknown
            Map<String, Long> stored = new HashMap<>();
            try (DownloadCursor cursor = DownloadUtil.getDownloadManager(app).getDownloadIndex().getDownloads()) {
                while (cursor.moveToNext()) {
                    androidx.media3.exoplayer.offline.Download d = cursor.getDownload();
                    stored.put(d.request.id, d.getBytesDownloaded());
                }
            } catch (Exception ignored) {
            }
            // records, not the computer's album ids: a song tagged apart is still its record (BUG-979)
            List<Download> held = AppDatabase.getInstance().downloadDao().getAllDownloadedSync();
            Map<String, String> fold = CassetteRecords.foldMap(held);
            LinkedHashMap<String, List<Download>> byAlbum = new LinkedHashMap<>();
            for (Download d : held) {
                String recordId = CassetteRecords.recordId(fold, d.getAlbumId());
                if (recordId == null) continue;
                List<Download> list = byAlbum.get(recordId);
                if (list == null) byAlbum.put(recordId, list = new ArrayList<>());
                list.add(d);
            }
            List<PhoneAlbum> out = new ArrayList<>();
            int songs = 0;
            long total = 0;
            for (Map.Entry<String, List<Download>> e : byAlbum.entrySet()) {
                PhoneAlbum a = new PhoneAlbum();
                Download first = CassetteRecords.lead(e.getValue(), e.getKey());
                a.id = e.getKey();
                a.name = first.getAlbum();
                a.cover = first.getCoverArtId();
                for (Download d : e.getValue()) {
                    Long b = stored.get(d.getId());
                    a.bytes += b != null && b > 0 ? b : (d.getSize() != null ? d.getSize() : 0);
                }
                a.artist = CassetteRecords.albumArtist(e.getValue());
                a.songs = e.getValue().size();
                songs += a.songs;
                total += a.bytes;
                out.add(a);
            }
            out.sort((x, y) -> Long.compare(y.bytes, x.bytes));
            final int songCount = songs;
            final long bytes = total;
            main.post(() -> {
                if (bind == null) return;
                bind.thisPhoneTitle.setText(counted(R.plurals.cassette_album_count, out.size()));
                bind.thisPhoneMeta.setText(counted(R.plurals.cassette_song_count, songCount)
                        + " · " + Formatter.formatShortFileSize(requireContext(), bytes));
                bind.thisPhoneBiggestHeading.setVisibility(out.isEmpty() ? View.GONE : View.VISIBLE);
                albums.set(out);
            });
        }, "cassette-this-phone").start();
    }

    /** "1,999 songs": the plural with the number grouped as the phone's language groups it. */
    private String counted(int plural, int n) {
        return getResources().getQuantityString(plural, n, n).replace(String.valueOf(n), NumberFormat.getInstance().format(n));
    }

    private void confirmRemove(PhoneAlbum album) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.cassette_remove_title, album.name))
                .setMessage(R.string.cassette_remove_message)
                .setPositiveButton(R.string.cassette_sign_out_remove, (d, w) ->
                        CassetteOnPhone.removeAlbum(requireContext(), album.id, () -> {
                            if (bind != null) {
                                loadPhone();
                                loadKeptOff();
                            }
                        }))
                .setNegativeButton(R.string.cassette_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // Kept off

    private void loadKeptOff() {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null) return;
        Set<String> settled = new HashSet<>(putBack);
        new Thread(() -> {
            List<KeptOff> out = new ArrayList<>();
            boolean read = false;
            try {
                String json = CassetteSyncApi.getThisDevice(bearer);
                read = true;
                JsonObject device = json != null ? JsonParser.parseString(json).getAsJsonObject() : null;
                JsonArray rows = device != null && device.has("keptOffAlbums") ? device.getAsJsonArray("keptOffAlbums") : new JsonArray();
                for (JsonElement el : rows) {
                    JsonObject o = el.getAsJsonObject();
                    KeptOff k = new KeptOff();
                    k.groupKey = str(o, "groupKey");
                    k.name = str(o, "albumName");
                    for (String key : new String[]{"coverOverrideUrl", "coverArtSmallUrl", "coverArtUrl"}) {
                        String url = str(o, key);
                        if (url != null && !url.isEmpty()) { k.cover = url; break; }
                    }
                    if (o.has("excludeTargetIds")) for (JsonElement id : o.getAsJsonArray("excludeTargetIds")) k.targetIds.add(id.getAsString());
                    if (k.groupKey != null) out.add(k);
                }
            } catch (Exception e) {
                android.util.Log.w("ThisPhone", "device read failed", e);
            }
            final boolean fresh = read;
            main.post(() -> {
                if (bind == null) return;
                if (fresh) putBack.removeAll(settled);
                List<KeptOff> shown = new ArrayList<>();
                for (KeptOff k : out) if (!putBack.contains(k.groupKey)) shown.add(k);
                keptOff.set(shown);
                bind.thisPhoneKeptOff.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }, "cassette-this-device").start();
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private void putBack(KeptOff tile) {
        String bearer = Preferences.getCassetteBearerToken();
        if (bearer == null || puttingBack.contains(tile.groupKey)) return;
        puttingBack.add(tile.groupKey);
        bind.thisPhonePutBackFailed.setVisibility(View.GONE);
        keptOff.notifyDataSetChanged();
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try {
                CassetteSyncApi.putBack(bearer, tile.targetIds);
                ok = true;
            } catch (Exception e) {
                android.util.Log.w("ThisPhone", "put back failed", e);
                ok = false;
            }
            if (ok) CassetteSyncEngine.reportInventoryNow(app);
            final boolean done = ok;
            main.post(() -> {
                puttingBack.remove(tile.groupKey);
                if (done) putBack.add(tile.groupKey);
                if (bind == null) return;
                if (done) keptOff.remove(tile);
                else keptOff.notifyDataSetChanged();
                bind.thisPhonePutBackFailed.setVisibility(done ? View.GONE : View.VISIBLE);
                bind.thisPhoneKeptOff.setVisibility(keptOff.getItemCount() == 0 ? View.GONE : View.VISIBLE);
            });
        }, "cassette-put-back").start();
    }

    // ------------------------------------------------------------------
    // Adapters

    private final class AlbumsAdapter extends RecyclerView.Adapter<AlbumsAdapter.Holder> {
        private List<PhoneAlbum> items = new ArrayList<>();

        void set(List<PhoneAlbum> next) {
            items = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemPhoneAlbumBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            PhoneAlbum a = items.get(position);
            h.bind.phoneAlbumName.setText(a.name);
            String songs = getResources().getQuantityString(R.plurals.cassette_song_count, a.songs, a.songs);
            h.bind.phoneAlbumLine.setText(a.artist == null || a.artist.isEmpty() ? songs : a.artist + " · " + songs);
            h.bind.phoneAlbumSize.setText(Formatter.formatShortFileSize(h.itemView.getContext(), a.bytes));
            CustomGlideRequest.Builder.from(h.itemView.getContext(), a.cover, CustomGlideRequest.ResourceType.Album)
                    .build().into(h.bind.phoneAlbumCover);
            h.itemView.setOnClickListener(v -> {
                AlbumID3 album = new AlbumID3();
                album.setId(a.id);
                album.setName(a.name);
                album.setArtist(a.artist);
                album.setCoverArtId(a.cover);
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                activity.navController.navigate(R.id.albumPageFragment, bundle);
            });
            h.itemView.setOnLongClickListener(v -> {
                confirmRemove(a);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemPhoneAlbumBinding bind;

            Holder(ItemPhoneAlbumBinding bind) {
                super(bind.getRoot());
                this.bind = bind;
            }
        }
    }

    private final class KeptOffAdapter extends RecyclerView.Adapter<KeptOffAdapter.Holder> {
        private List<KeptOff> items = new ArrayList<>();

        void set(List<KeptOff> next) {
            items = next;
            notifyDataSetChanged();
        }

        void remove(KeptOff tile) {
            int at = items.indexOf(tile);
            if (at < 0) return;
            items.remove(at);
            notifyItemRemoved(at);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemKeptOffTileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            KeptOff k = items.get(position);
            h.bind.keptOffName.setText(k.name);
            Glide.with(h.itemView).load(k.cover).into(h.bind.keptOffCover);
            boolean busy = puttingBack.contains(k.groupKey);
            h.bind.keptOffCover.setAlpha(busy ? 0.5f : 1f);
            h.itemView.setEnabled(!busy);
            h.itemView.setContentDescription(getString(R.string.cassette_put_back_a11y, k.name));
            h.itemView.setOnClickListener(v -> putBack(k));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemKeptOffTileBinding bind;

            Holder(ItemKeptOffTileBinding bind) {
                super(bind.getRoot());
                this.bind = bind;
            }
        }
    }
}
