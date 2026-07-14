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

    @Query("SELECT * FROM chronology WHERE timestamp >= :endDate AND timestamp < :startDate AND server == :server GROUP BY id ORDER BY COUNT(id) DESC LIMIT 20")
    LiveData<List<Chronology>> getAllFrom(long startDate, long endDate, String server);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Chronology chronologyObject);

    // cassette play spine: completed plays newer than the cloud high-water mark,
    // oldest-first and capped, for the /api/sync/plays batch flush. Synchronous
    // (List, not LiveData) — called only off the main thread.
    @Query("SELECT * FROM chronology WHERE server == :server AND timestamp > :since ORDER BY timestamp ASC LIMIT :limit")
    List<Chronology> getPlayedSince(String server, long since, int limit);
}