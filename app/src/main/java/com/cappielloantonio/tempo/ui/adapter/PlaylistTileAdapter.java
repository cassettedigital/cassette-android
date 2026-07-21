package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemHomePlaylistTileBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.util.Constants;

import java.util.Collections;
import java.util.List;

/**
 * cassette: playlists as square tiles for the Home shelf, matching iOS's 160pt
 * tiles. The existing PlaylistHorizontalAdapter is a vertical list row with a
 * kebab menu, which is right for the Library category and wrong for a shelf
 * that sits between two rows of artwork.
 */
public class PlaylistTileAdapter extends RecyclerView.Adapter<PlaylistTileAdapter.ViewHolder> {
    private final ClickCallback click;
    private List<Playlist> playlists = Collections.emptyList();

    public PlaylistTileAdapter(ClickCallback click) {
        this.click = click;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemHomePlaylistTileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);

        holder.item.playlistTitleLabel.setText(playlist.getName());
        holder.item.playlistSubtitleLabel.setText(
                holder.itemView.getContext().getString(R.string.playlist_track_count, playlist.getSongCount()));

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), playlist.getCoverArtId(), CustomGlideRequest.ResourceType.Playlist)
                .build()
                .into(holder.item.playlistCoverImageView);
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    public void setItems(List<Playlist> playlists) {
        this.playlists = playlists != null ? playlists : Collections.emptyList();
        notifyDataSetChanged();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        final ItemHomePlaylistTileBinding item;

        ViewHolder(ItemHomePlaylistTileBinding item) {
            super(item.getRoot());
            this.item = item;

            itemView.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlists.get(getBindingAdapterPosition()));
                click.onPlaylistClick(bundle);
            });
        }
    }
}
