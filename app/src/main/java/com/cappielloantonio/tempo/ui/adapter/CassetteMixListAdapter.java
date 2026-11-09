package com.cappielloantonio.tempo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemHorizontalPlaylistBinding;
import com.cappielloantonio.tempo.util.CassetteHome;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: the made-for-you playlists under your own in Library > Playlists (iPhone
 * PlaylistsVC "Made for you", I-M8): Home's current set, only those with songs on this
 * phone, each opening its page. Nothing (not even the heading) when Home has none.
 */
public class CassetteMixListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onOpen(CassetteHome.DrawnMix mix);
    }

    private static final int HEADING = 0;
    private static final int MIX = 1;

    private final Listener listener;
    private List<CassetteHome.DrawnMix> mixes = new ArrayList<>();

    public CassetteMixListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<CassetteHome.DrawnMix> items) {
        mixes = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? HEADING : MIX;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == HEADING) {
            TextView heading = new TextView(new android.view.ContextThemeWrapper(parent.getContext(), R.style.Cassette_Role_Shout));
            heading.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            float d = parent.getResources().getDisplayMetrics().density;
            heading.setPadding(Math.round(4 * d), Math.round(28 * d), Math.round(4 * d), Math.round(8 * d));
            heading.setText(parent.getContext().getString(R.string.cassette_made_for_you).toUpperCase(java.util.Locale.getDefault()));
            return new RecyclerView.ViewHolder(heading) { };
        }
        // the same row as your own playlists just above
        return new MixHolder(ItemHorizontalPlaylistBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof MixHolder)) return;
        CassetteHome.DrawnMix mix = mixes.get(position - 1);
        ItemHorizontalPlaylistBinding b = ((MixHolder) holder).bind;
        b.playlistTitleTextView.setText(mix.getTitle());
        int n = mix.getSongs().size();
        String songs = holder.itemView.getResources().getQuantityString(R.plurals.cassette_song_count, n, n);
        String sub = mix.getSubtitle();
        b.playlistSubtitleTextView.setText(sub != null && !sub.isEmpty() ? sub + " · " + songs : songs);
        b.playlistMoreButton.setVisibility(View.GONE);
        // its drawn cover, else its first record's, as Home shows it
        boolean drawn = mix.getCoverUrl() != null;
        com.cappielloantonio.tempo.glide.CustomGlideRequest.Builder.from(holder.itemView.getContext(),
                        drawn ? mix.getCoverUrl() : mix.getSongs().get(0).getCoverArtId(),
                        drawn ? com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Playlist
                                : com.cappielloantonio.tempo.glide.CustomGlideRequest.ResourceType.Album)
                .build().into(b.playlistCoverImageView);
        holder.itemView.setOnClickListener(v -> listener.onOpen(mix));
    }

    @Override
    public int getItemCount() {
        return mixes.isEmpty() ? 0 : mixes.size() + 1;
    }

    static class MixHolder extends RecyclerView.ViewHolder {
        final ItemHorizontalPlaylistBinding bind;

        MixHolder(ItemHorizontalPlaylistBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
