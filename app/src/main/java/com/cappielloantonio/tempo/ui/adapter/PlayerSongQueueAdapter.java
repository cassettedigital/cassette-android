package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.media3.session.MediaBrowser;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemPlayerQueueSongBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.interfaces.MediaIndexCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerSongQueueAdapter extends RecyclerView.Adapter<PlayerSongQueueAdapter.ViewHolder> {
    private final ClickCallback click;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private List<Child> songs;

    public PlayerSongQueueAdapter(ClickCallback click) {
        this.click = click;
        this.songs = Collections.emptyList();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPlayerQueueSongBinding view = ItemPlayerQueueSongBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Child song = songs.get(position);

        holder.item.queueSongTitleTextView.setText(song.getTitle());

        // cassette: just the artist. The queue row used to read
        // "Artist · 3:41 · FLAC 1411" — the same duration/quality clutter the
        // album rows already dropped, on the screen with the least room for it.
        holder.item.queueSongSubtitleTextView.setText(song.getArtist());

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.queueSongCoverImageView);

        MediaManager.getCurrentIndex(mediaBrowserListenableFuture, new MediaIndexCallback() {
            @Override
            public void onRecovery(int index) {
                // cassette: one list in play order with no history above it (iPhone
                // 518ef8b4). Played rows fold away (kept in the list so indexes stay
                // the player's); the song playing keeps ink, its bars take the
                // record's colour.
                boolean played = position < index;
                ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                int height = played ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp.height != height) {
                    lp.height = height;
                    holder.itemView.setLayoutParams(lp);
                }
                holder.itemView.setVisibility(played ? View.GONE : View.VISIBLE);

                boolean isCurrent = position == index;
                holder.item.queueSongTitleTextView.setAlpha(1f);
                holder.item.queueSongSubtitleTextView.setAlpha(1f);
                holder.item.queueSongTitleTextView.setTextColor(androidx.core.content.ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.titleTextColor));

                holder.item.queueNowPlayingIndicator.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
                com.cappielloantonio.tempo.util.CoverTint tint = com.cappielloantonio.tempo.util.CoverTint.nowPlaying().getValue();
                holder.item.queueNowPlayingIndicator.setColorFilter(
                        (tint != null ? tint : com.cappielloantonio.tempo.util.CoverTint.NEUTRAL).text(holder.itemView.getContext()));
                android.graphics.drawable.Drawable bars = holder.item.queueNowPlayingIndicator.getDrawable();
                if (bars instanceof android.graphics.drawable.AnimationDrawable) {
                    if (isCurrent) ((android.graphics.drawable.AnimationDrawable) bars).start();
                    else ((android.graphics.drawable.AnimationDrawable) bars).stop();
                }
            }
        });

        // cassette: no star ratings (founder directive) - always hidden.
        holder.item.ratingIndicatorImageView.setVisibility(View.GONE);
    }

    public List<Child> getItems() {
        return this.songs;
    }

    public void setItems(List<Child> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        if (songs == null) {
            return 0;
        }
        return songs.size();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void setMediaBrowserListenableFuture(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        this.mediaBrowserListenableFuture = mediaBrowserListenableFuture;
    }

    public Child getItem(int id) {
        return songs.get(id);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemPlayerQueueSongBinding item;

        ViewHolder(ItemPlayerQueueSongBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnClickListener(v -> onClick());
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(songs));
            bundle.putInt(Constants.ITEM_POSITION, getBindingAdapterPosition());

            click.onMediaClick(bundle);
        }
    }
}
