package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.databinding.FragmentGenrePageBinding;
import com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumCatalogueAdapter;
import com.cappielloantonio.tempo.util.CassetteGenres;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * cassette: one genre (Library > Genres, Search's GENRES grid) or one of today's shelves (All
 * shelves, iPhone ShelfVC: its playlist above its records). Its
 * records on this phone in the Library's grid, with PLAY and SHUFFLE over
 * every song of it here. The genre is the file's own, else the cloud's.
 */
@UnstableApi
public class GenrePageFragment extends Fragment implements ClickCallback {
    public static final String GENRE_NAME = "cassette_genre_name";
    public static final String SHELF_ID = "cassette_shelf_id";
    @Nullable private String shelfId;

    private FragmentGenrePageBinding bind;
    private MainActivity activity;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private AlbumCatalogueAdapter adapter;
    private final List<Child> songs = new ArrayList<>();
    private String name;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentGenrePageBinding.inflate(inflater, container, false);
        name = getArguments() != null ? getArguments().getString(GENRE_NAME, "") : "";
        shelfId = getArguments() != null ? getArguments().getString(SHELF_ID) : null;
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.setSupportActionBar(bind.genrePageToolbar);
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        bind.genrePageToolbar.setTitle("");
        bind.genrePageToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        bind.genrePageScroll.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> {
                    if (bind == null) return;
                    int past = bind.genrePageTitle.getBottom();
                    float t = past <= 0 ? 1f : Math.max(0f, Math.min(1f, y / (float) past));
                    bind.genrePageBarBackdrop.setAlpha(t);
                    bind.genrePageToolbar.setTitle(t >= 1f ? name : "");
                });

        bind.genrePageTitle.setText(name);
        bind.genrePageMeta.setText("");
        adapter = new AlbumCatalogueAdapter(this, true);
        bind.genrePageAlbums.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        bind.genrePageAlbums.addItemDecoration(new GridItemDecoration(3, Math.round(16 * getResources().getDisplayMetrics().density), false));
        bind.genrePageAlbums.setAdapter(adapter);

        bind.genrePagePlay.setOnClickListener(v -> play(false));
        bind.genrePageShuffle.setOnClickListener(v -> play(true));
        load();
    }

    private void load() {
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            List<AlbumID3> albums = new ArrayList<>();
            com.cappielloantonio.tempo.util.CassetteHome.DrawnShelf shelf = null;
            if (shelfId != null) {
                shelf = com.cappielloantonio.tempo.util.CassetteHome.shelf(shelfId);
                if (shelf == null) {
                    com.cappielloantonio.tempo.util.CassetteHome.shelvesNow(app);
                    shelf = com.cappielloantonio.tempo.util.CassetteHome.shelf(shelfId);
                }
                if (shelf != null) albums = shelf.getAlbums();
            } else for (CassetteGenres.Genre g : CassetteGenres.genres(app)) {
                if (g.getName().equalsIgnoreCase(name)) { albums = g.getAlbums(); break; }
            }
            final com.cappielloantonio.tempo.util.CassetteHome.DrawnShelf drawnShelf = shelf;
            List<Child> found = new ArrayList<>();
            List<com.cappielloantonio.tempo.model.Download> held = AppDatabase.getInstance().downloadDao().getAllDownloadedSync();
            for (AlbumID3 album : albums) {
                found.addAll(com.cappielloantonio.tempo.util.CassetteRecords.songsOf(held, album.getId()));
            }
            List<AlbumID3> shown = albums;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (bind == null) return;
                songs.clear();
                songs.addAll(found);
                if (drawnShelf != null) paintShelf(drawnShelf);
                adapter.setItems(shown);
                bind.genrePageMeta.setText(getResources().getQuantityString(R.plurals.cassette_album_count, shown.size(), shown.size())
                        + " · " + getResources().getQuantityString(R.plurals.cassette_song_count, songs.size(), songs.size()));
                bind.genrePagePlay.setEnabled(!songs.isEmpty());
                bind.genrePageShuffle.setEnabled(!songs.isEmpty());
            });
        }, "cassette-genre-page").start();
    }

    /** A shelf: its name in two voices, and its playlist (when it has one) above its records. */
    private void paintShelf(com.cappielloantonio.tempo.util.CassetteHome.DrawnShelf shelf) {
        name = shelf.getShout();
        bind.genrePageTitle.setAllCaps(false);
        bind.genrePageTitle.setText(com.cappielloantonio.tempo.util.CassetteType.twoVoiceText(requireContext(),
                shelf.getShout(), shelf.getAnswer(),
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cs_accent_text), 26));
        com.cappielloantonio.tempo.util.CassetteHome.DrawnMix mix = shelf.getMix();
        bind.genrePageMix.getRoot().setVisibility(mix != null ? View.VISIBLE : View.GONE);
        if (mix == null) return;
        int n = mix.getSongs().size();
        bind.genrePageMix.playlistTitleTextView.setText(mix.getTitle());
        bind.genrePageMix.playlistSubtitleTextView.setText(getResources().getQuantityString(R.plurals.cassette_song_count, n, n));
        bind.genrePageMix.playlistMoreButton.setVisibility(View.GONE);
        boolean drawn = mix.getCoverUrl() != null;
        com.cappielloantonio.tempo.glide.CustomGlideRequest.Builder.from(requireContext(),
                drawn ? mix.getCoverUrl() : (n > 0 ? mix.getSongs().get(0).getCoverArtId() : null),
                drawn ? com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Playlist
                        : com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album)
                .build().into(bind.genrePageMix.playlistCoverImageView);
        int side = Math.round(20 * getResources().getDisplayMetrics().density);
        bind.genrePageMix.getRoot().setPadding(side, bind.genrePageMix.getRoot().getPaddingTop(), side, bind.genrePageMix.getRoot().getPaddingBottom());
        bind.genrePageMix.getRoot().setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(MixPageFragment.ARG_MIX_ID, mix.getId());
            activity.navController.navigate(R.id.mixPageFragment, bundle);
        });
    }

    private void play(boolean shuffle) {
        if (songs.isEmpty()) return;
        List<Child> queue = new ArrayList<>(songs);
        if (shuffle) Collections.shuffle(queue);
        MediaManager.startQueue(mediaBrowserListenableFuture, queue, 0);
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(),
                new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
        activity.setBottomNavigationBarVisibility(true);
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
    public void onAlbumClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }
}
