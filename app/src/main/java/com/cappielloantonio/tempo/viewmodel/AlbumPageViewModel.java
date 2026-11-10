package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.AlbumInfo;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.List;

public class AlbumPageViewModel extends AndroidViewModel {
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private String albumId;
    private final MutableLiveData<AlbumID3> album = new MutableLiveData<>(null);

    public AlbumPageViewModel(@NonNull Application application) {
        super(application);

        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
    }

    private String songsFor;
    private LiveData<List<Child>> songs;

    /**
     * cassette: the album's songs. Offline-first (the default) lists only the
     * songs on this phone, read from the phone's own store, so the page works
     * away from the computer and never offers a song that can't play (R1 in
     * the 2026-11-09 audit). One LiveData per album, shared by the page.
     */
    public LiveData<List<Child>> getAlbumSongLiveList() {
        if (songs != null && albumId != null && albumId.equals(songsFor)) return songs;
        songsFor = albumId;
        if (com.cappielloantonio.tempo.util.Preferences.isOfflineFirst()) {
            MutableLiveData<List<Child>> live = new MutableLiveData<>();
            final String id = albumId;
            // the whole record, even a song the computer filed under another album id (BUG-979)
            new Thread(() -> live.postValue(new java.util.ArrayList<>(
                    com.cappielloantonio.tempo.util.CassetteRecords.songsOf(id))),
                    "cassette-album-songs").start();
            songs = live;
        } else {
            songs = albumRepository.getAlbumTracks(albumId);
        }
        return songs;
    }

    /** cassette: the artist's other records on this phone, newest first (MORE BY). */
    public LiveData<List<AlbumID3>> getMoreByArtist() {
        MutableLiveData<List<AlbumID3>> live = new MutableLiveData<>();
        AlbumID3 current = album.getValue();
        final String artistId = current != null ? current.getArtistId() : null;
        final String thisId = albumId;
        if (artistId == null) {
            live.setValue(new java.util.ArrayList<>());
            return live;
        }
        com.cappielloantonio.tempo.util.CassetteOfflineLibrary.albums(all -> {
            List<AlbumID3> out = new java.util.ArrayList<>();
            for (AlbumID3 a : all) {
                if (artistId.equals(a.getArtistId()) && !a.getId().equals(thisId)) out.add(a);
            }
            out.sort((a, b) -> Integer.compare(b.getYear(), a.getYear()));
            live.setValue(out);
        });
        return live;
    }

    public MutableLiveData<AlbumID3> getAlbum() {
        return album;
    }

    public void setAlbum(LifecycleOwner owner, AlbumID3 album) {
        this.albumId = album.getId();
        this.album.postValue(album);

        albumRepository.getAlbum(album.getId()).observe(owner, albums -> {
            if (albums != null) this.album.setValue(albums);
        });
    }

    public LiveData<ArtistID3> getArtist() {
        // cassette: getArtist(id) expects an *artist* id. Passing albumId made
        // the header artist tap resolve nothing and toast every time.
        AlbumID3 current = album.getValue();
        String artistId = current != null ? current.getArtistId() : null;
        if (artistId == null || artistId.isEmpty()) {
            MutableLiveData<ArtistID3> empty = new MutableLiveData<>();
            empty.setValue(null);
            return empty;
        }
        return artistRepository.getArtistInfo(artistId);
    }

    public LiveData<AlbumInfo> getAlbumInfo() {
        return albumRepository.getAlbumInfo(albumId);
    }
}
