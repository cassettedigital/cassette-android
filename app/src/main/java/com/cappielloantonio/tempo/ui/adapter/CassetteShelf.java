package com.cappielloantonio.tempo.ui.adapter;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ViewRecordShelfBinding;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.CassetteMoods;
import com.cappielloantonio.tempo.util.CassetteType;
import com.cappielloantonio.tempo.util.Constants;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/**
 * cassette: the record shelf under a page (view_record_shelf.xml): MORE LIKE on album,
 * artist and playlist pages. One place for its heading, its tiles, what a tile does
 * (open the record; the disc plays it and the screen stays put) and filling it off the
 * main thread, so the three pages cannot drift apart.
 */
@UnstableApi
public final class CassetteShelf {
    public interface Load {
        List<AlbumID3> load(Context app);
    }

    public interface Browser {
        ListenableFuture<MediaBrowser> get();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CassetteShelf() {
    }

    /**
     * MORE LIKE *answer*. Fills off the main thread and stays hidden while it has nothing;
     * asks the cloud for fresh song tags when due and fills again when they land.
     */
    public static void moreLike(Fragment fragment, ViewRecordShelfBinding shelf, String answer, int answerColor,
                                Browser browser, Load load) {
        heading(fragment, shelf, answer, answerColor);
        CassetteRecordTileAdapter adapter = adapter(fragment, shelf, browser);
        Context app = fragment.requireContext().getApplicationContext();
        Runnable fill = () -> new Thread(() -> {
            List<AlbumID3> albums = load.load(app);
            MAIN.post(() -> {
                if (fragment.getView() == null) return;
                adapter.setItems(albums);
                shelf.getRoot().setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }, "cassette-more-like").start();
        fill.run();
        CassetteMoods.refreshIfDue(app, fill);
    }

    /** The heading again, in the page's colour once its cover has been read. */
    public static void heading(Fragment fragment, ViewRecordShelfBinding shelf, String answer, int answerColor) {
        CassetteType.twoVoice(shelf.shelfHeading, fragment.getString(R.string.cassette_more_like), answer, answerColor);
    }

    private static CassetteRecordTileAdapter adapter(Fragment fragment, ViewRecordShelfBinding shelf, Browser browser) {
        if (shelf.shelfRecords.getAdapter() instanceof CassetteRecordTileAdapter) {
            return (CassetteRecordTileAdapter) shelf.shelfRecords.getAdapter();
        }
        CassetteRecordTileAdapter adapter = new CassetteRecordTileAdapter(new CassetteRecordTileAdapter.Listener() {
            @Override
            public void onOpen(AlbumID3 album) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                NavHostFragment.findNavController(fragment).navigate(R.id.albumPageFragment, bundle);
            }

            @Override
            public void onPlay(AlbumID3 album) {
                MediaManager.playAlbum(browser.get(), album.getId(), false);
                if (fragment.getActivity() instanceof MainActivity) {
                    ((MainActivity) fragment.getActivity()).setBottomSheetInPeek(true);
                }
            }
        }, CassetteRecordTileAdapter.Subtitle.ARTIST);
        shelf.shelfRecords.setLayoutManager(new LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.HORIZONTAL, false));
        shelf.shelfRecords.setAdapter(adapter);
        return adapter;
    }
}
