package com.cappielloantonio.tempo.util;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.chooser.ChooserAction;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.ShareSongNoteBottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Cassette: send a song (CASSETTE_SHARING.md), the Android twin of the iPhone's
 * ShareSongSheet.swift. Share opens Android's own share sheet at once with the song's link,
 * and the friend's page says who it's from. A note is one action inside that sheet, Add a
 * note, so a raw share stays one tap and a note is there for whoever wants one: the note
 * sheet, then the share sheet again with the same link, now carrying the note.
 *
 * The link's code is made here, so the sheet opens without a round trip, and the share is
 * saved in the cloud as the sheet opens (POST /api/share/song). Android hands the link to
 * the picked app at once (there is no waiting on the save the way the iPhone can), so a save
 * that fails before anything is picked closes the sheet and says why, and one that fails
 * after says the link won't open. Whether a song can be shared at all (one of its videos
 * still plays) is asked before Share is offered ({@link Availability}): a song with no video
 * shows Share greyed out instead of failing afterwards.
 *
 * The sheet's preview is the album cover as this phone holds it (FileProvider + ClipData);
 * the app it goes to draws its own preview from the page, which carries the same cover.
 */
public final class CassetteShareSong {
    private static final String TAG = "CassetteShareSong";

    /** The share sheet is started for a result under this code, so a failed save can close it. */
    private static final int REQUEST_SHARE = 0x5e4d;
    static final String ACTION_PICKED = "digital.cassette.player.share.PICKED";
    static final String ACTION_ADD_NOTE = "digital.cassette.player.share.ADD_NOTE";
    static final String EXTRA_CODE = "code";

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** One save at a time, in order: a note's save never lands before the plain one it updates. */
    private static final ExecutorService SAVES = Executors.newSingleThreadExecutor();
    private static final ExecutorService WORK = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CassetteShareSong() {
    }

    // ------------------------------------------------------------------
    // What the sheets need to know about the song

    /** The song being shared, as the menu or Now Playing had it. */
    public static final class Subject {
        public final String songId;
        public final String title;
        public final String artist;
        @Nullable public final String coverArtId;
        final int duration;
        @Nullable final String mbid;
        /**
         * Seconds into the song when Share was pressed, when it is the one playing and the
         * moment is worth offering (past the first few seconds, before the last few).
         */
        @Nullable public final Integer elapsed;

        Subject(Child song, @Nullable Integer elapsed) {
            songId = song.getId();
            title = song.getTitle() != null ? song.getTitle() : "";
            artist = song.getArtist() != null ? song.getArtist() : "";
            coverArtId = song.getCoverArtId();
            duration = song.getDuration() != null ? song.getDuration() : 0;
            mbid = song.getMusicBrainzId();
            this.elapsed = elapsed;
        }
    }

    /** The ids the cloud knows a song by. Off the main thread (reads the downloads table). */
    private static String[] ids(Subject s) {
        Download own = AppDatabase.getInstance().downloadDao().getOne(s.songId);
        String local = own != null && own.getCassetteLocalId() != null
                ? own.getCassetteLocalId()
                : CassetteLocalId.compute(s.artist, s.title, s.duration);
        String mbid = s.mbid != null ? s.mbid : own != null ? own.getMbid() : null;
        return new String[]{local, mbid};
    }

    @Nullable
    private static String bearer() {
        String token = Preferences.getCassetteBearerToken();
        return token == null || token.isEmpty() ? null : token;
    }

    /** Share is offered only to a phone signed in to Cassette (the friend's page says who). */
    public static boolean isOffered() {
        return bearer() != null;
    }

    // ------------------------------------------------------------------
    // Whether a song can be sent

    /**
     * Whether each song can be shared right now, asked once and kept for a while (a video can
     * turn up later, or stop playing), so Now Playing and the song menu know before anyone
     * presses Share. Main thread only.
     */
    public static final class Availability {
        private static final long KEEP_FOR_MS = 30 * 60 * 1000L;
        private static final Map<String, Boolean> answers = new HashMap<>();
        private static final Map<String, Long> answeredAt = new HashMap<>();
        private static final Map<String, List<Answer>> asking = new HashMap<>();

        public interface Answer {
            /** Null when it couldn't be asked (offline, signed out): Share stays on. */
            void on(@Nullable Boolean shareable);
        }

        private Availability() {
        }

        /** The answer already in hand, or null when it hasn't been asked lately. */
        @Nullable
        public static Boolean known(String songId) {
            Long at = answeredAt.get(songId);
            if (at == null || System.currentTimeMillis() - at > KEEP_FOR_MS) return null;
            return answers.get(songId);
        }

        /** Ask the cloud, once per song at a time. */
        public static void check(Child song, @Nullable Answer answer) {
            String songId = song.getId();
            Boolean known = known(songId);
            if (known != null) {
                if (answer != null) answer.on(known);
                return;
            }
            String token = bearer();
            if (token == null) {
                if (answer != null) answer.on(null);
                return;
            }
            List<Answer> waiting = asking.get(songId);
            if (waiting != null) {
                if (answer != null) waiting.add(answer);
                return;
            }
            waiting = new ArrayList<>();
            if (answer != null) waiting.add(answer);
            asking.put(songId, waiting);
            Subject subject = new Subject(song, null);
            WORK.execute(() -> {
                Boolean result;
                try {
                    String[] ids = ids(subject);
                    result = CassetteSyncApi.checkSongShare(token, subject.songId, ids[0], ids[1]);
                } catch (Exception e) {
                    Log.w(TAG, "share check failed", e);
                    result = null;
                }
                Boolean done = result;
                MAIN.post(() -> {
                    if (done != null) remember(songId, done);
                    List<Answer> answering = asking.remove(songId);
                    if (answering != null) for (Answer a : answering) a.on(done);
                });
            });
        }

        /** A save came back saying the song has no video: Share greys out for it from now on. */
        static void markUnshareable(String songId) {
            remember(songId, false);
        }

        private static void remember(String songId, boolean shareable) {
            answers.put(songId, shareable);
            answeredAt.put(songId, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------
    // Saving the share behind the sheet

    enum Outcome {SAVED, NO_VIDEO, SIGNED_OUT, FAILED}

    /** One share being saved, started as the share sheet opens. Settles on the main thread. */
    private static final class Save {
        @Nullable Outcome outcome;
        private final List<Runnable> waiting = new ArrayList<>();

        void whenSettled(Runnable then) {
            if (outcome != null) then.run();
            else waiting.add(then);
        }

        static Save start(Subject s, String code, @Nullable String note,
                          @Nullable Integer startSeconds) {
            Save save = new Save();
            SAVES.execute(() -> {
                Outcome outcome;
                String token = bearer();
                if (token == null) {
                    outcome = Outcome.SIGNED_OUT;
                } else {
                    try {
                        String[] ids = ids(s);
                        CassetteSyncApi.SongShareResult answer = CassetteSyncApi.createSongShare(
                                token, code, s.songId, ids[0], ids[1], note, startSeconds);
                        outcome = answer.ok ? Outcome.SAVED
                                : "no_video".equals(answer.reason) ? Outcome.NO_VIDEO
                                : "signed_out".equals(answer.reason) ? Outcome.SIGNED_OUT
                                : Outcome.FAILED;
                    } catch (Exception e) {
                        Log.w(TAG, "share save failed", e);
                        outcome = Outcome.FAILED;
                    }
                }
                Outcome settled = outcome;
                MAIN.post(() -> {
                    save.outcome = settled;
                    for (Runnable then : save.waiting) then.run();
                    save.waiting.clear();
                });
            });
            return save;
        }
    }

    /** The share whose sheet is up, or whose note is being written. */
    private static final class Pending {
        final Subject subject;
        final String code;
        final Save save;
        final WeakReference<FragmentActivity> activity;
        /** The share sheet is on screen. */
        boolean sheetUp;
        /** The link went to an app. */
        boolean picked;

        Pending(Subject subject, String code, Save save, FragmentActivity activity) {
            this.subject = subject;
            this.code = code;
            this.save = save;
            this.activity = new WeakReference<>(activity);
        }
    }

    @Nullable
    private static Pending pending;

    // ------------------------------------------------------------------
    // Presentation

    /**
     * Share a song from the song menu or Now Playing: Android's share sheet at once. Main
     * thread. {@code elapsed}: seconds into it when it is the song playing, else null.
     */
    public static void share(FragmentActivity activity, Child song, @Nullable Integer elapsed) {
        Subject subject = new Subject(song, elapsed);
        if (Boolean.FALSE.equals(Availability.known(subject.songId))) {
            showProblem(activity, Outcome.NO_VIDEO, subject.songId, false);
            return;
        }
        open(activity, subject, makeCode(), null, null, true);
    }

    /** The note sheet's Send: the share sheet again with the same link, now carrying the note. */
    public static void sendWithNote(FragmentActivity activity, String code, @Nullable String note,
                                    @Nullable Integer startSeconds) {
        Pending p = pending;
        if (p == null || !p.code.equals(code)) return;
        open(activity, p.subject, code, note, startSeconds, false);
    }

    /** The song the note sheet is for, or null when that share is gone (the app restarted). */
    @Nullable
    public static Subject noteSubject(String code) {
        Pending p = pending;
        return p != null && p.code.equals(code) ? p.subject : null;
    }

    private static void open(FragmentActivity activity, Subject subject, String code,
                             @Nullable String note, @Nullable Integer startSeconds,
                             boolean offerNote) {
        Context app = activity.getApplicationContext();
        Pending p = new Pending(subject, code, Save.start(subject, code, note, startSeconds), activity);
        pending = p;

        // a save that fails before anything is picked closes the sheet and says why, so
        // nothing goes out
        p.save.whenSettled(() -> {
            FragmentActivity a = p.activity.get();
            if (p.save.outcome == Outcome.SAVED || p.picked || !p.sheetUp || a == null) return;
            p.sheetUp = false;
            a.finishActivity(REQUEST_SHARE);
            showProblem(a, p.save.outcome, subject.songId, false);
        });

        WORK.execute(() -> {
            Uri cover = coverUri(app, subject);
            MAIN.post(() -> {
                FragmentActivity a = p.activity.get();
                if (a == null || a.isFinishing() || pending != p) return;
                if (p.save.outcome != null && p.save.outcome != Outcome.SAVED) {
                    showProblem(a, p.save.outcome, subject.songId, false);
                    return;
                }
                p.sheetUp = true;
                // the sheet is down once this app comes back from behind it (a new observer is
                // first caught up to RESUMED, so only a resume after a pause counts)
                a.getLifecycle().addObserver(new LifecycleEventObserver() {
                    private boolean covered;

                    @Override
                    public void onStateChanged(androidx.lifecycle.LifecycleOwner owner,
                                               Lifecycle.Event event) {
                        if (event == Lifecycle.Event.ON_PAUSE) {
                            covered = true;
                        } else if (event == Lifecycle.Event.ON_DESTROY
                                || (event == Lifecycle.Event.ON_RESUME && covered)) {
                            p.sheetUp = false;
                            owner.getLifecycle().removeObserver(this);
                        }
                    }
                });
                a.startActivityForResult(chooser(a, p, cover, offerNote), REQUEST_SHARE);
            });
        });
    }

    private static Intent chooser(Context context, Pending p, @Nullable Uri cover,
                                  boolean offerNote) {
        String title = context.getString(R.string.share_song_link_title, p.subject.title,
                p.subject.artist);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, link(p.code))
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TITLE, title);
        if (cover != null) {
            send.setClipData(ClipData.newRawUri(null, cover));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent picked = PendingIntent.getBroadcast(context, 0,
                new Intent(context, Receiver.class).setAction(ACTION_PICKED)
                        .putExtra(EXTRA_CODE, p.code),
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        Intent chooser = Intent.createChooser(send, null, picked.getIntentSender());

        if (offerNote) {
            String addNote = context.getString(R.string.share_song_add_note);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent onAddNote = PendingIntent.getBroadcast(context, 1,
                        new Intent(context, Receiver.class).setAction(ACTION_ADD_NOTE)
                                .putExtra(EXTRA_CODE, p.code),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                ChooserAction action = new ChooserAction.Builder(
                        Icon.createWithResource(context, R.drawable.ic_edit_note), addNote,
                        onAddNote).build();
                chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, new ChooserAction[]{action});
            } else {
                // before Android 14 the sheet has no actions of its own: Add a note is the first
                // place it offers, opening this app's note sheet
                Intent note = new Intent(context, AddNoteActivity.class).putExtra(EXTRA_CODE, p.code);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{
                        new LabeledIntent(note, context.getPackageName(), addNote,
                                R.drawable.ic_edit_note)});
            }
        }
        return chooser;
    }

    /** The album cover as this phone holds it, for the sheet's preview. Off the main thread. */
    @Nullable
    private static Uri coverUri(Context context, Subject s) {
        if (s.coverArtId == null) return null;
        try {
            File local = CassetteAlbumArt.localFile(context, s.coverArtId);
            Object model = local != null ? local
                    : CustomGlideRequest.createUrl(s.coverArtId, Preferences.getImageSize());
            Bitmap bitmap = Glide.with(context).asBitmap().load(model).submit(512, 512)
                    .get(800, TimeUnit.MILLISECONDS);
            File dir = new File(context.getCacheDir(), "share");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = new File(dir, "cover.jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            return FileProvider.getUriForFile(context, context.getPackageName() + ".share", file);
        } catch (Exception e) {
            // no preview of our own: the sheet still opens, with the link alone
            Log.w(TAG, "share cover unavailable", e);
            return null;
        }
    }

    static String link(String code) {
        return "https://cassette.digital/s/" + code;
    }

    /** The 8-character code in a share's link (/s/<code>), the shape the cloud makes for the web. */
    static String makeCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }

    // ------------------------------------------------------------------
    // What comes back from the sheet

    /** The link went to an app: wait for the save, which has almost always landed by now. */
    static void onPicked(String code) {
        Pending p = pending;
        if (p == null || !p.code.equals(code)) return;
        p.picked = true;
        p.save.whenSettled(() -> {
            if (p.save.outcome == Outcome.SAVED) {
                Drafts.clear(p.subject.songId);
                return;
            }
            FragmentActivity a = p.activity.get();
            if (a != null) showProblem(a, p.save.outcome, p.subject.songId, true);
        });
    }

    /** Add a note: the note sheet, over this app once the share sheet has gone. */
    static void onAddNote(String code) {
        Pending p = pending;
        if (p == null || !p.code.equals(code)) return;
        p.sheetUp = false;
        FragmentActivity a = p.activity.get();
        if (a == null) return;
        whenResumed(a, () -> {
            // never a note for a song that turned out to have nothing to send
            if (p.save.outcome != null && p.save.outcome != Outcome.SAVED) {
                showProblem(a, p.save.outcome, p.subject.songId, false);
                return;
            }
            if (a.getSupportFragmentManager().isStateSaved()) return;
            ShareSongNoteBottomSheetDialog.newInstance(code)
                    .show(a.getSupportFragmentManager(), "share-song-note");
        });
    }

    /** The chooser's callbacks: a target was picked, or Add a note (Android 14 and later). */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String code = intent.getStringExtra(EXTRA_CODE);
            if (code == null) return;
            if (ACTION_PICKED.equals(intent.getAction())) onPicked(code);
            else if (ACTION_ADD_NOTE.equals(intent.getAction())) onAddNote(code);
        }
    }

    /** Add a note as the share sheet's first target, before Android 14. Opens nothing itself. */
    public static final class AddNoteActivity extends Activity {
        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            String code = getIntent().getStringExtra(EXTRA_CODE);
            if (code != null) onAddNote(code);
            finish();
        }
    }

    private static void whenResumed(FragmentActivity activity, Runnable then) {
        if (activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            then.run();
            return;
        }
        activity.getLifecycle().addObserver(new LifecycleEventObserver() {
            @Override
            public void onStateChanged(androidx.lifecycle.LifecycleOwner owner,
                                       Lifecycle.Event event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    owner.getLifecycle().removeObserver(this);
                } else if (event == Lifecycle.Event.ON_RESUME) {
                    owner.getLifecycle().removeObserver(this);
                    then.run();
                }
            }
        });
    }

    private static void showProblem(FragmentActivity activity, @Nullable Outcome outcome,
                                    String songId, boolean sent) {
        int title;
        int message;
        if (outcome == Outcome.NO_VIDEO) {
            Availability.markUnshareable(songId);
            title = R.string.share_song_no_video_title;
            message = sent ? R.string.share_song_no_video_sent : R.string.share_song_no_video;
        } else if (outcome == Outcome.SIGNED_OUT) {
            title = R.string.share_song_signed_out_title;
            message = R.string.share_song_signed_out;
        } else {
            title = sent ? R.string.share_song_not_saved_title : R.string.share_song_failed_title;
            message = sent ? R.string.share_song_not_saved : R.string.share_song_failed;
        }
        whenResumed(activity, () -> new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    // ------------------------------------------------------------------
    // Drafts

    /** A note being written, kept per song until it is sent, so closing the sheet never loses it. */
    public static final class Drafts {
        private static final Map<String, String> notes = new HashMap<>();

        private Drafts() {
        }

        public static String note(String songId) {
            String note = notes.get(songId);
            return note != null ? note : "";
        }

        public static void keep(String note, String songId) {
            if (note.isEmpty()) notes.remove(songId);
            else notes.put(songId, note);
        }

        static void clear(String songId) {
            notes.remove(songId);
        }
    }
}
