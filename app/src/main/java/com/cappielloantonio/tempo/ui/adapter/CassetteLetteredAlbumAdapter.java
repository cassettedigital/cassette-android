package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemLibraryCatalogueAlbumBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.util.CassetteLetters;
import com.cappielloantonio.tempo.util.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * cassette: the Library's album grid filed by letter (iPhone 42a38ecb): a big quiet
 * letter over each run of records, "#" first, no line under it. Letters only appear
 * past CassetteLetters.THRESHOLD records; below that the grid is flat.
 */
public class CassetteLetteredAlbumAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int LETTER = 0;
    private static final int ALBUM = 1;

    private final ClickCallback click;
    private final List<Object> rows = new ArrayList<>();
    private final Map<String, Integer> letterAt = new HashMap<>();

    public CassetteLetteredAlbumAdapter(ClickCallback click) {
        this.click = click;
    }

    public void setItems(List<AlbumID3> albums) {
        rows.clear();
        letterAt.clear();
        String current = null;
        for (AlbumID3 album : albums) {
            String letter = CassetteLetters.letter(album.getName());
            if (!letter.equals(current)) {
                current = letter;
                letterAt.put(letter, rows.size());
                rows.add(letter);
            }
            rows.add(album);
        }
        notifyDataSetChanged();
    }

    /** The row a letter starts at, else the next letter that has records. */
    public int positionFor(String letter) {
        int from = CassetteLetters.LETTERS.indexOf(letter);
        for (int i = Math.max(from, 0); i < CassetteLetters.LETTERS.size(); i++) {
            Integer at = letterAt.get(CassetteLetters.LETTERS.get(i));
            if (at != null) return at;
        }
        return rows.size() - 1;
    }

    /** Letters span the whole row of the grid. */
    public GridLayoutManager.SpanSizeLookup spans(int columns) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return rows.get(position) instanceof String ? columns : 1;
            }
        };
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? LETTER : ALBUM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == LETTER) {
            TextView letter = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.item_library_letter, parent, false);
            return new RecyclerView.ViewHolder(letter) { };
        }
        return new AlbumHolder(ItemLibraryCatalogueAlbumBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof String) {
            ((TextView) holder.itemView).setText((String) row);
            return;
        }
        AlbumID3 album = (AlbumID3) row;
        ItemLibraryCatalogueAlbumBinding b = ((AlbumHolder) holder).bind;
        b.albumNameLabel.setText(album.getName());
        b.artistNameLabel.setText(album.getArtist());
        CustomGlideRequest.Builder.from(holder.itemView.getContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                .build().into(b.albumCatalogueCoverImageView);
        holder.itemView.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, album);
            click.onAlbumClick(bundle);
        });
        holder.itemView.setOnLongClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, album);
            click.onAlbumLongClick(bundle);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class AlbumHolder extends RecyclerView.ViewHolder {
        final ItemLibraryCatalogueAlbumBinding bind;

        AlbumHolder(ItemLibraryCatalogueAlbumBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
