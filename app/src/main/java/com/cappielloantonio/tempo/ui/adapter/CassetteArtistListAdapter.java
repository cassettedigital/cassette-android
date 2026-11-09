package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemLibraryArtistRowBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.util.CassetteLetters;
import com.cappielloantonio.tempo.util.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * cassette: Library > Artists as a list, the iPhone's way (ArtistsVC): filed under letters
 * (the album grid's own headings and # to Z index), one row per artist with a round photo,
 * the name and how many of their records are on this phone. Replaces Tempo's photo grid
 * (founder, 2026-11-09: "it is nice to be consistent").
 */
public class CassetteArtistListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int LETTER = 0;
    private static final int ARTIST = 1;

    private final ClickCallback click;
    private final List<Object> rows = new ArrayList<>();
    private final Map<String, Integer> letterAt = new HashMap<>();

    public CassetteArtistListAdapter(ClickCallback click) {
        this.click = click;
    }

    public void setItems(List<ArtistID3> artists) {
        List<ArtistID3> sorted = new ArrayList<>(artists);
        sorted.sort((a, b) -> CassetteLetters.sortKey(a.getName()).compareTo(CassetteLetters.sortKey(b.getName())));
        rows.clear();
        letterAt.clear();
        String current = null;
        for (ArtistID3 artist : sorted) {
            String letter = CassetteLetters.letter(artist.getName());
            if (!letter.equals(current)) {
                current = letter;
                letterAt.put(letter, rows.size());
                rows.add(letter);
            }
            rows.add(artist);
        }
        notifyDataSetChanged();
    }

    /** The row a letter starts at, else the next letter that has artists. */
    public int positionFor(String letter) {
        int from = CassetteLetters.LETTERS.indexOf(letter);
        for (int i = Math.max(from, 0); i < CassetteLetters.LETTERS.size(); i++) {
            Integer at = letterAt.get(CassetteLetters.LETTERS.get(i));
            if (at != null) return at;
        }
        return rows.size() - 1;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? LETTER : ARTIST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == LETTER) {
            TextView letter = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.item_library_letter, parent, false);
            return new RecyclerView.ViewHolder(letter) { };
        }
        return new ArtistHolder(ItemLibraryArtistRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof String) {
            ((TextView) holder.itemView).setText((String) row);
            return;
        }
        ArtistID3 artist = (ArtistID3) row;
        ItemLibraryArtistRowBinding b = ((ArtistHolder) holder).bind;
        b.artistRowName.setText(artist.getName());
        int n = artist.getAlbumCount();
        b.artistRowCount.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) b.artistRowCount.setText(holder.itemView.getResources().getQuantityString(R.plurals.cassette_album_count, n, n));
        CustomGlideRequest.Builder.from(holder.itemView.getContext(), artist.getCoverArtId(), CustomGlideRequest.ResourceType.Artist)
                .build().into(b.artistRowPhoto);
        holder.itemView.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
            click.onArtistClick(bundle);
        });
        holder.itemView.setOnLongClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
            click.onArtistLongClick(bundle);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class ArtistHolder extends RecyclerView.ViewHolder {
        final ItemLibraryArtistRowBinding bind;

        ArtistHolder(ItemLibraryArtistRowBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
