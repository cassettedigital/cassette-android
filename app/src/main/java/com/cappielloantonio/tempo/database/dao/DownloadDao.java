package com.cappielloantonio.tempo.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.Download;

import java.util.List;

@Dao
public interface DownloadDao {
    @Query("SELECT * FROM download WHERE download_state = 1 ORDER BY artist, album, disc_number, track ASC")
    LiveData<List<Download>> getAll();

    @Query("SELECT * FROM download WHERE id = :id")
    Download getOne(String id);

    // cassette: synchronous reads for the sync engine (runs on its own
    // thread; the completed set feeds device-inventory reports).
    @Query("SELECT * FROM download WHERE download_state = 1")
    List<Download> getAllDownloadedSync();

    @Query("SELECT * FROM download WHERE cassette_local_id = :cassetteLocalId LIMIT 1")
    Download getByCassetteLocalId(String cassetteLocalId);

    // Resolve by the stable MusicBrainz id — the cross-device key that survives
    // the fingerprint drift between phones and the hub.
    @Query("SELECT * FROM download WHERE mbid = :mbid LIMIT 1")
    Download getByMbid(String mbid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Download download);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Download> downloads);

    // cassette (BUG-897): every fingerprint stored before the satellite rule was adopted, not traced to the copy
    // this phone holds; the freshness sweep forgets them once and traces each copy again.
    @Query("UPDATE download SET content_fingerprint = NULL")
    void forgetAllContentFingerprints();

    @Query("UPDATE download SET download_state = 1 WHERE id = :id")
    void update(String id);

    @Query("DELETE FROM download WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM download")
    void deleteAll();
}