package com.cappielloantonio.tempo.util;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: the one on-phone rule for everything that plays or queues.
 *
 * Play never streams: while the account is offline-first (the default, and the
 * only mode the shipped build allows) a song plays only when its file is fully
 * on this phone. Every play path asks here, so the queue the player holds, the
 * queue saved for the queue page and mini player, and the song a tap starts on
 * all agree (BUG-939, BUG-942). The Android twin of iOS's OneTapPlay.
 */
public final class CassetteOnPhone {
    private CassetteOnPhone() {}

    /** True when this song can play: on the phone, or the account streams. */
    public static boolean playable(Child song) {
        if (song == null || song.getId() == null) return false;
        if (!Preferences.isOfflineFirst()) return true;
        return DownloadUtil.getDownloadTracker(App.getContext()).isCompleted(song.getId());
    }

    /** The songs of this list that can play, in order. */
    public static List<Child> keep(List<Child> songs) {
        List<Child> kept = new ArrayList<>();
        if (songs == null) return kept;
        for (Child song : songs) {
            if (playable(song)) kept.add(song);
        }
        return kept;
    }

    /**
     * Where a tap on row {@code index} of the full list lands in the kept list:
     * that song when it can play, else the next one that can. -1 when nothing
     * from that row on can play.
     */
    public static int startIn(List<Child> songs, int index) {
        if (songs == null) return -1;
        int kept = 0;
        for (int i = 0; i < songs.size(); i++) {
            boolean ok = playable(songs.get(i));
            if (i >= index && ok) return kept;
            if (ok) kept++;
        }
        return -1;
    }
}
