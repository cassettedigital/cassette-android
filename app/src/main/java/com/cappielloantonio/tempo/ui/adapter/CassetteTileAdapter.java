package com.cappielloantonio.tempo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.databinding.ItemCassetteRecordTileBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: one Home row of tiles, whatever they hold (records, artists in
 * circles, made-for-you playlists, your playlists). Every tile is 150dp with
 * its name under it and the cream play disc on it: the picture opens it, the
 * disc plays it and Home stays put (iPhone bac358ed).
 */
public class CassetteTileAdapter extends RecyclerView.Adapter<CassetteTileAdapter.Holder> {

    public static final class Tile {
        public final String title;
        @Nullable public final String subtitle;
        @Nullable public final String coverId;
        public final CustomGlideRequest.ResourceType coverType;
        public final boolean circle;
        public final Runnable onOpen;
        public final Runnable onPlay;

        public Tile(String title, @Nullable String subtitle, @Nullable String coverId,
                    CustomGlideRequest.ResourceType coverType, boolean circle,
                    Runnable onOpen, Runnable onPlay) {
            this.title = title;
            this.subtitle = subtitle;
            this.coverId = coverId;
            this.coverType = coverType;
            this.circle = circle;
            this.onOpen = onOpen;
            this.onPlay = onPlay;
        }
    }

    private List<Tile> tiles = new ArrayList<>();

    public void setItems(List<Tile> items) {
        tiles = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemCassetteRecordTileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Tile tile = tiles.get(position);
        holder.bind.tileTitle.setText(tile.title);
        holder.bind.tileSubtitle.setText(tile.subtitle);
        holder.bind.tileSubtitle.setVisibility(tile.subtitle == null || tile.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        // circles for artists, squircles for stations (kit shape.station 22.5%), else 3dp
        float corner = tile.circle ? 75f * density
                : tile.coverType == CustomGlideRequest.ResourceType.Radio ? 150f * 0.225f * density
                : 3f * density;
        holder.bind.tileCover.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(corner).build());
        if (tile.coverId != null) {
            CustomGlideRequest.Builder.from(holder.itemView.getContext(), tile.coverId, tile.coverType)
                    .build().into(holder.bind.tileCover);
        } else {
            holder.bind.tileCover.setImageDrawable(null);
        }
        holder.bind.tileCover.setContentDescription(tile.title);
        holder.bind.tileCover.setOnClickListener(v -> tile.onOpen.run());
        holder.bind.tilePlayDisc.getRoot().setOnClickListener(v -> tile.onPlay.run());
    }

    @Override
    public int getItemCount() {
        return tiles.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemCassetteRecordTileBinding bind;

        Holder(ItemCassetteRecordTileBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
