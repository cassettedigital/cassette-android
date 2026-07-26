package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.List;
import java.util.Objects;

public class ArtistPageViewModel extends AndroidViewModel {
    private final AlbumRepository albumRepository;

    private ArtistID3 artist;
    private LiveData<List<AlbumID3>> albumList;
    private LiveData<List<Child>> librarySongs;

    public ArtistPageViewModel(@NonNull Application application) {
        super(application);

        albumRepository = new AlbumRepository();
    }

    public LiveData<List<AlbumID3>> getAlbumList() {
        return albumList;
    }

    /**
     * Every track across the artist's albums. Powers play / shuffle / repeat,
     * and Popular (ranked play-count → title, iOS ArtistDetailVC parity).
     * getTopSongs is not used — the hub often returns an empty list for it.
     */
    public LiveData<List<Child>> getArtistLibrarySongs() {
        return librarySongs;
    }

    public ArtistID3 getArtist() {
        return artist;
    }

    public void setArtist(ArtistID3 artist) {
        if (artist == null) return;
        if (this.artist != null && Objects.equals(this.artist.getId(), artist.getId())
                && albumList != null) {
            this.artist = artist;
            return;
        }

        this.artist = artist;
        albumList = albumRepository.getArtistAlbums(artist.getId());
        librarySongs = albumRepository.getArtistLibrarySongs(artist.getId());
    }
}
