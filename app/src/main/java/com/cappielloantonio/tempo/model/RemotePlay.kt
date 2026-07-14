package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cassette play spine — Phase B (read path).
 *
 * A play made on ANOTHER surface (iOS / web / desktop), pulled down from
 * /api/sync/plays. Deliberately kept in its own table, separate from
 * `chronology` (this device's own plays): rows here are NEVER written back to
 * the cloud, so the spine is echo-proof by construction.
 *
 * One row per track (latest remote play), keyed by cassette_local_id. The
 * endpoint returns ids only — no title/artist — so display metadata is resolved
 * at read time from the local library/downloads (union-at-read).
 */
@Keep
@Entity(tableName = "cassette_remote_play")
class RemotePlay(
    @PrimaryKey
    @ColumnInfo(name = "cassette_local_id")
    val cassetteLocalId: String,

    @ColumnInfo(name = "played_at")
    var playedAt: Long,

    @ColumnInfo(name = "source_device")
    var sourceDevice: String?,

    @ColumnInfo(name = "mbid")
    var mbid: String?,
)
