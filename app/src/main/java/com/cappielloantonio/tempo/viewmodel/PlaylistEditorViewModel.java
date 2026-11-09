package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.repository.SharingRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.Share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PlaylistEditorViewModel extends AndroidViewModel {
    private static final String TAG = "PlaylistEditorViewModel";

    private final PlaylistRepository playlistRepository;
    private final SharingRepository sharingRepository;

    private Child toAdd;
    private Playlist toEdit;

    private MutableLiveData<List<Child>> songLiveList = new MutableLiveData<>();
    // cassette (BUG-936): each row's cloud item, by the row's own object, so a
    // save sends only the removes and moves made. The same song can sit in a
    // playlist twice, hence identity rather than song id.
    private final Map<Child, String> itemIdOf = new IdentityHashMap<>();
    private final List<String> loadedItemIds = new ArrayList<>();

    public PlaylistEditorViewModel(@NonNull Application application) {
        super(application);

        playlistRepository = new PlaylistRepository();
        sharingRepository = new SharingRepository();
    }

    public void createPlaylist(String name) {
        playlistRepository.createPlaylist(null, name, new ArrayList(Collections.singletonList(toAdd.getId())));
    }

    public void updatePlaylist(String name) {
        String newName = name != null && !name.equals(toEdit.getName()) ? name : null;
        List<String> kept = new ArrayList<>();
        List<Child> songs = songLiveList.getValue();
        if (songs != null) {
            for (Child song : songs) {
                String itemId = itemIdOf.get(song);
                if (itemId != null) kept.add(itemId);
            }
        }
        List<String> removed = new ArrayList<>(loadedItemIds);
        removed.removeAll(kept);
        playlistRepository.savePlaylistEdits(toEdit.getId(), newName, kept, removed);
    }

    public void deletePlaylist() {
        if (toEdit != null) playlistRepository.deletePlaylist(toEdit.getId());
    }

    public Child getSongToAdd() {
        return toAdd;
    }

    public void setSongToAdd(Child song) {
        this.toAdd = song;
    }

    public Playlist getPlaylistToEdit() {
        return toEdit;
    }

    public void setPlaylistToEdit(Playlist playlist) {
        this.toEdit = playlist;

        itemIdOf.clear();
        loadedItemIds.clear();
        if (playlist != null) {
            MediatorLiveData<List<Child>> songs = new MediatorLiveData<>();
            songs.addSource(playlistRepository.getPlaylistEditorRows(toEdit.getId()), rows -> {
                itemIdOf.clear();
                loadedItemIds.clear();
                List<Child> children = new ArrayList<>();
                if (rows != null) {
                    for (PlaylistRepository.EditorRow row : rows) {
                        itemIdOf.put(row.child, row.itemId);
                        loadedItemIds.add(row.itemId);
                        children.add(row.child);
                    }
                }
                songs.setValue(children);
            });
            this.songLiveList = songs;
        } else {
            this.songLiveList = new MutableLiveData<>();
        }
    }

    public LiveData<List<Child>> getPlaylistSongLiveList() {
        return songLiveList;
    }

    public void removeFromPlaylistSongLiveList(int position) {
        List<Child> songs = songLiveList.getValue();
        Objects.requireNonNull(songs).remove(position);
        songLiveList.postValue(songs);
    }

    public void orderPlaylistSongLiveListAfterSwap(List<Child> songs) {
        songLiveList.postValue(songs);
    }

    public MutableLiveData<Share> sharePlaylist() {
        return sharingRepository.createShare(toEdit.getId(), toEdit.getName(), null);
    }
}
