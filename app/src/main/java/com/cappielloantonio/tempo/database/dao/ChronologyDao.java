package com.cappielloantonio.tempo.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.Chronology;

import java.util.List;

@Dao
public interface ChronologyDao {
    @Query("SELECT * FROM chronology WHERE server == :server GROUP BY id ORDER BY timestamp DESC LIMIT :count")
    LiveData<List<Chronology>> getLastPlayed(String server, int count);

    // cassette (BUG-83): the old "most played this week" query is gone. It ranked by COUNT(id)
    // over a table keyed by the song, so every count was one and the order meant nothing, and
    // no screen used it after One Home. Play counts live in the cloud's play history.

    /**
     * cassette: album ids in order of most recently played, derived from the
     * track play log. This is how the "Recent" shelf gets filled offline-first,
     * where the server-side "recent" album list is unreachable and the shelf
     * was posting an empty list instead.
     *
     * MAX(timestamp) per album, newest first. Synchronous - called off the
     * main thread.
     */
    @Query("SELECT album_id FROM chronology WHERE server == :server AND album_id IS NOT NULL GROUP BY album_id ORDER BY MAX(timestamp) DESC LIMIT :limit")
    List<String> getRecentlyPlayedAlbumIds(String server, int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Chronology chronologyObject);

    // cassette play spine: completed plays newer than the cloud high-water mark,
    // oldest-first and capped, for the /api/sync/plays batch flush. Synchronous
    // (List, not LiveData) — called only off the main thread.
    @Query("SELECT * FROM chronology WHERE server == :server AND timestamp > :since ORDER BY timestamp ASC LIMIT :limit")
    List<Chronology> getPlayedSince(String server, long since, int limit);

    // cassette play spine: latest local play per track, newest-first, for the
    // cross-device "recently played" union. Synchronous — called off the main thread.
    @Query("SELECT * FROM chronology WHERE server == :server GROUP BY id ORDER BY timestamp DESC LIMIT :limit")
    List<Chronology> getRecentSync(String server, int limit);
}