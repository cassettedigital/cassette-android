package com.cappielloantonio.tempo.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * cassette: one place that knows which track is sounding and whether it is
 * actually playing.
 *
 * The app knew this only inside the player screen. An album page could not tell
 * you that the album in front of you was the one playing: its big button said
 * "play" while the music came out of the speaker, and tapping it restarted the
 * record from track one. The track rows said nothing at all.
 *
 * Fed from MainActivity's existing player listener, which is the one place
 * already holding a MediaBrowser for the whole session.
 */
public final class CassetteNowPlaying {

    public static final class State {
        /** Subsonic id of the current track, or null when nothing is loaded. */
        public final String mediaId;
        public final boolean isPlaying;

        State(String mediaId, boolean isPlaying) {
            this.mediaId = mediaId;
            this.isPlaying = isPlaying;
        }

        public boolean isCurrent(String id) {
            return id != null && id.equals(mediaId);
        }
    }

    private static final State IDLE = new State(null, false);
    private static final MutableLiveData<State> LIVE = new MutableLiveData<>(IDLE);

    private CassetteNowPlaying() {
    }

    public static LiveData<State> live() {
        return LIVE;
    }

    public static State current() {
        State state = LIVE.getValue();
        return state != null ? state : IDLE;
    }

    public static void publish(String mediaId, boolean isPlaying) {
        State previous = current();
        if (previous.isPlaying == isPlaying
                && (previous.mediaId == null ? mediaId == null : previous.mediaId.equals(mediaId))) {
            return; // no change worth waking observers for
        }
        LIVE.postValue(new State(mediaId, isPlaying));
    }
}
