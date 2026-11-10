package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cappielloantonio.tempo.subsonic.models.Child
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
@Entity(tableName = "download")
class Download(@PrimaryKey override val id: String) : Child(id) {
    @ColumnInfo(name = "playlist_id")
    var playlistId: String? = null

    @ColumnInfo(name = "playlist_name")
    var playlistName: String? = null

    @ColumnInfo(name = "download_state", defaultValue = "1")
    var downloadState: Int = 0

    @ColumnInfo(name = "download_uri", defaultValue = "")
    var downloadUri: String? = null

    // cassette: the cloud's stable track identity (32-char hex) + optional
    // MusicBrainz recording id, carried from the sync intent that delivered
    // this file. The Subsonic id (this row's PK) is local to one hub library;
    // inventory reports and removals speak cassette_local_id.
    @ColumnInfo(name = "cassette_local_id")
    var cassetteLocalId: String? = null

    @ColumnInfo(name = "mbid")
    var mbid: String? = null

    // cassette: content fingerprint of this track's own file, as last applied
    // from the server's track-freshness manifest. cassetteLocalId is
    // deliberately content-blind (artist+title+duration), so a re-rip that
    // fixes an audio-quality issue without changing title/duration is
    // otherwise invisible — this is what lets the freshness sweep notice the
    // bytes changed and re-fetch.
    @ColumnInfo(name = "content_fingerprint")
    var contentFingerprint: String? = null

    // cassette (BUG-970): the song's album-artist tag as the computer reports it
    // (OpenSubsonic displayAlbumArtist). null = not asked yet (a row from before
    // this column; the sync fills it in); "" = asked, the computer has none. A
    // record is named for this tag when its songs agree on it, else for the artist
    // most of them are by, as the web does (CassetteAlbumGroupKey.effectiveAlbumArtist).
    @ColumnInfo(name = "album_artist")
    var albumArtist: String? = null

    constructor(child: Child) : this(child.id) {
        parentId = child.parentId
        isDir = child.isDir
        title = child.title
        album = child.album
        artist = child.artist
        track = child.track
        year = child.year
        genre = child.genre
        coverArtId = child.coverArtId
        size = child.size
        contentType = child.contentType
        suffix = child.suffix
        transcodedContentType = child.transcodedContentType
        transcodedSuffix = child.transcodedSuffix
        duration = child.duration
        bitrate = child.bitrate
        path = child.path
        isVideo = child.isVideo
        userRating = child.userRating
        averageRating = child.averageRating
        playCount = child.playCount
        discNumber = child.discNumber
        created = child.created
        starred = child.starred
        albumId = child.albumId
        artistId = child.artistId
        type = child.type
        bookmarkPosition = child.bookmarkPosition
        originalWidth = child.originalWidth
        originalHeight = child.originalHeight
        // Carry the stable MusicBrainz id when the source Child has one (Subsonic
        // getSong). Intent-delivered downloads set mbid from the transfer instead;
        // this covers the Subsonic-sourced path so cross-device plays resolve here.
        mbid = child.musicBrainzId
        albumArtist = (child as? Download)?.albumArtist ?: child.displayAlbumArtist?.trim()
    }
}

@Keep
data class DownloadStack(
    var id: String,
    var view: String?
)