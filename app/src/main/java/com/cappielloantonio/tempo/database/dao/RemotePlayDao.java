package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.RemotePlay;

import java.util.List;

/**
 * Cassette play spine — Phase B: other-surface plays pulled from
 * /api/sync/plays. REPLACE on conflict keeps the latest remote play per track.
 */
@Dao
public interface RemotePlayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<RemotePlay> plays);

    @Query("SELECT * FROM cassette_remote_play ORDER BY played_at DESC LIMIT :limit")
    List<RemotePlay> getRecent(int limit);

    // Keep the table bounded — remote plays only feed "recently played".
    @Query("DELETE FROM cassette_remote_play WHERE played_at < :cutoff")
    void pruneOlderThan(long cutoff);
}
