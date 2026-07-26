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

    public LiveData<List<Child>> getAlbumSongLiveList() {
        return albumRepository.getAlbumTracks(albumId);
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
