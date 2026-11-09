package com.cappielloantonio.tempo.ui.adapter;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemGenreTileBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.util.CassetteGenres;
import com.cappielloantonio.tempo.util.CoverTint;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: the genre tiles, two to a row, biggest first (iPhone SearchStartView
 * GENRES grid). Each tile takes the hue of one of its records at the shared soft
 * tone (CoverTint.tileFill / tileInk / tileCount), so the grid never averages to
 * brown. The record shown is the genre's first one with a cover.
 */
public class CassetteGenreTileAdapter extends RecyclerView.Adapter<CassetteGenreTileAdapter.Holder> {

    public interface Listener {
        void onOpen(CassetteGenres.Genre genre);
    }

    private final Listener listener;
    private List<CassetteGenres.Genre> genres = new ArrayList<>();

    public CassetteGenreTileAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<CassetteGenres.Genre> items) {
        genres = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemGenreTileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CassetteGenres.Genre genre = genres.get(position);
        int count = genre.getAlbums().size();
        // Android's breaker will not wrap after a hyphen here; a zero-width space after it does,
        // so "SINGER-SONGWRITER" splits as SINGER- / SONGWRITER, never mid-word
        holder.bind.genreTileName.setText(genre.getName().replace("-", "-\u200B").replace("/", "/\u200B"));
        holder.bind.genreTileCount.setText(String.valueOf(count));
        holder.itemView.setContentDescription(holder.itemView.getResources()
                .getQuantityString(R.plurals.cassette_genre_tile_a11y, count, genre.getName(), count));
        holder.itemView.setOnClickListener(v -> listener.onOpen(genre));

        AlbumID3 face = null;
        for (AlbumID3 album : genre.getAlbums()) {
            if (album.getCoverArtId() != null && !album.getCoverArtId().isEmpty()) { face = album; break; }
        }
        String cover = face != null ? face.getCoverArtId() : null;
        holder.cover = cover;
        holder.paint(CoverTint.NEUTRAL);
        holder.bind.genreTileCover.setVisibility(cover == null ? View.GONE : View.VISIBLE);
        if (cover == null) return;
        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), cover, CustomGlideRequest.ResourceType.Album)
                .build()
                .into(holder.bind.genreTileCover);
        CoverTint.resolve(holder.itemView.getContext(), cover, CustomGlideRequest.ResourceType.Album, tint -> {
            if (cover.equals(holder.cover)) holder.paint(tint);
        });
    }

    @Override
    public int getItemCount() {
        return genres.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemGenreTileBinding bind;
        final GradientDrawable fill = new GradientDrawable();
        String cover;

        Holder(ItemGenreTileBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
            fill.setCornerRadius(bind.getRoot().getResources().getDimension(R.dimen.kit_radius_genre_tile));
            bind.getRoot().setBackground(fill);
        }

        void paint(CoverTint tint) {
            android.content.Context c = itemView.getContext();
            fill.setColor(ColorStateList.valueOf(tint.tileFill(c)));
            bind.genreTileName.setTextColor(tint.tileInk(c));
            bind.genreTileCount.setTextColor(tint.tileCount(c));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int shadow = tint.shadow(0.45);
                bind.genreTileCover.setOutlineSpotShadowColor(shadow);
                bind.genreTileCover.setOutlineAmbientShadowColor(shadow);
            }
        }
    }
}
