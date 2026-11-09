package com.cappielloantonio.tempo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.databinding.ItemCassetteRecordTileBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: a row of record tiles (MORE BY on an album page, ALBUMS on an
 * artist page, Home's record rows). The cover opens the record; the play disc
 * plays it (only its songs on this phone) and the screen stays put, the
 * iPhone's one-tap rule.
 */
public class CassetteRecordTileAdapter extends RecyclerView.Adapter<CassetteRecordTileAdapter.Holder> {

    public interface Listener {
        void onOpen(AlbumID3 album);

        void onPlay(AlbumID3 album);
    }

    /** What the quiet line under a tile says. */
    public enum Subtitle { ARTIST, YEAR }

    private final Listener listener;
    private final Subtitle subtitle;
    private List<AlbumID3> albums = new ArrayList<>();

    public CassetteRecordTileAdapter(Listener listener, Subtitle subtitle) {
        this.listener = listener;
        this.subtitle = subtitle;
    }

    public void setItems(List<AlbumID3> items) {
        albums = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemCassetteRecordTileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AlbumID3 album = albums.get(position);
        holder.bind.tileTitle.setText(album.getName());
        String line = subtitle == Subtitle.YEAR
                ? (album.getYear() > 0 ? String.valueOf(album.getYear()) : null)
                : album.getArtist();
        holder.bind.tileSubtitle.setText(line);
        holder.bind.tileSubtitle.setVisibility(line == null || line.isEmpty() ? View.GONE : View.VISIBLE);
        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                .build()
                .into(holder.bind.tileCover);
        holder.bind.tileCover.setContentDescription(album.getName());
        holder.bind.tileCover.setOnClickListener(v -> listener.onOpen(album));
        holder.bind.tilePlayDisc.getRoot().setOnClickListener(v -> listener.onPlay(album));
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemCassetteRecordTileBinding bind;

        Holder(ItemCassetteRecordTileBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
