package com.cappielloantonio.tempo.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.service.DownloaderManager;

/**
 * cassette: one place that knows whether a sync is running and how far in it
 * is, so the phone can say so.
 *
 * Before this, the only outward sign that an account sync was moving was
 * media3's stock foreground notification, which reads "Downloading" with an
 * indeterminate bar — no album, no count, no end. A person who had just
 * paired a phone had no way to tell a working sync from a stuck one.
 *
 * Albums come from the sync engine (the intents it is working through) and
 * tracks from the download tracker. Album counts are per-process: a restart
 * mid-sync resets "done" to zero and the total shrinks to what is left, so
 * the numbers never claim more history than they have. Track counts survive
 * restarts because the download index does.
 */
public final class CassetteSyncStatus {

    public static final class Snapshot {
        /** True while the engine has albums it has not finished. */
        public final boolean active;
        public final int albumsDone;
        public final int albumsTotal;
        public final int tracksDone;
        public final int tracksTotal;
        /** Queue parked because the phone is off Wi-Fi (downloads are Wi-Fi only). */
        public final boolean waitingForWifi;
        /** Queue parked because the phone is nearly full. */
        public final boolean lowOnSpace;

        Snapshot(boolean active, int albumsDone, int albumsTotal, int tracksDone,
                 int tracksTotal, boolean waitingForWifi, boolean lowOnSpace) {
            this.active = active;
            this.albumsDone = albumsDone;
            this.albumsTotal = albumsTotal;
            this.tracksDone = tracksDone;
            this.tracksTotal = tracksTotal;
            this.waitingForWifi = waitingForWifi;
            this.lowOnSpace = lowOnSpace;
        }

        /** One line for the banner and the notification: what is happening now. */
        public String headline() {
            if (lowOnSpace) return "Sync paused - phone is low on space";
            if (waitingForWifi) return "Waiting for Wi-Fi";
            return "Syncing your library";
        }

        /** The detail line. Albums lead because that is how people think about it. */
        public String detail() {
            StringBuilder sb = new StringBuilder();
            if (albumsTotal > 0) {
                sb.append(albumsDone).append(" of ").append(albumsTotal).append(" albums");
            }
            if (tracksTotal > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(tracksDone).append(" of ").append(tracksTotal).append(" tracks");
            }
            return sb.toString();
        }

        /** 0-100 across the whole wave, tracks first (they move more smoothly). */
        public int percent() {
            if (tracksTotal > 0) return Math.min(100, (int) (100L * tracksDone / tracksTotal));
            if (albumsTotal > 0) return Math.min(100, (int) (100L * albumsDone / albumsTotal));
            return 0;
        }
    }

    private static final Snapshot IDLE = new Snapshot(false, 0, 0, 0, 0, false, false);

    private static final MutableLiveData<Snapshot> LIVE = new MutableLiveData<>(IDLE);

    /** Albums the engine still has to finish, as of its last pass. */
    private static volatile int albumsRemaining = 0;
    /** Albums the engine has finished since this process started. */
    private static volatile int albumsDoneThisRun = 0;
    private static volatile boolean waitingForWifi = false;
    private static volatile boolean lowOnSpace = false;

    private CassetteSyncStatus() {
    }

    public static LiveData<Snapshot> live() {
        return LIVE;
    }

    public static Snapshot current() {
        Snapshot snapshot = LIVE.getValue();
        return snapshot != null ? snapshot : IDLE;
    }

    /** Engine: how many albums this pass found still outstanding. */
    public static void notePass(int remaining) {
        albumsRemaining = Math.max(0, remaining);
        if (albumsRemaining == 0) albumsDoneThisRun = 0; // wave over, next one starts clean
        publish();
    }

    /**
     * Engine: one album finished. Remaining drops as done rises so the total
     * holds steady through a pass — the next pass re-derives remaining from
     * the server and the two stay in agreement.
     */
    public static void noteAlbumComplete() {
        albumsDoneThisRun++;
        if (albumsRemaining > 0) albumsRemaining--;
        publish();
    }

    /** Engine: the queue is parked, and why. */
    public static void noteBlocked(boolean offWifi, boolean noSpace) {
        waitingForWifi = offWifi;
        lowOnSpace = noSpace;
        publish();
    }

    /** Anything that changes download state: recompute and notify observers. */
    public static void publish() {
        int[] tracks = DownloaderManager.trackTally();
        int done = albumsDoneThisRun;
        int total = albumsRemaining + albumsDoneThisRun;
        boolean active = albumsRemaining > 0;
        LIVE.postValue(new Snapshot(active, done, total, tracks[0], tracks[1],
                waitingForWifi, lowOnSpace));
    }
}
