package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemHorizontalTrackBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.DiscTitle;
import com.cappielloantonio.tempo.util.CassetteTrackSubtitle;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@UnstableApi
public class SongHorizontalAdapter extends RecyclerView.Adapter<SongHorizontalAdapter.ViewHolder> implements Filterable {
    private final ClickCallback click;
    private final boolean showCoverArt;
    private final boolean showAlbum;
    private final AlbumID3 album;
    /** When set (album/artist page), suppress a redundant primary and keep feat. guests. */
    private final String contextArtist;

    private List<Child> songsFull;
    private List<Child> songs;
    private String currentFilter;

    // cassette: which row is sounding, and whether it is moving (paused rows
    // keep the marker but freeze the bars).
    private String nowPlayingId;
    private boolean nowPlayingIsPlaying;

    private final Filter filtering = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<Child> filteredList = new ArrayList<>();

            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(songsFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();
                currentFilter = filterPattern;

                for (Child item : songsFull) {
                    if (item.getTitle().toLowerCase().contains(filterPattern)) {
                        filteredList.add(item);
                    }
                }
            }

            FilterResults results = new FilterResults();
            results.values = filteredList;

            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            songs = (List<Child>) results.values;
            notifyDataSetChanged();
        }
    };

    public SongHorizontalAdapter(ClickCallback click, boolean showCoverArt, boolean showAlbum, AlbumID3 album) {
        this(click, showCoverArt, showAlbum, album, album != null ? album.getArtist() : null);
    }

    public SongHorizontalAdapter(
            ClickCallback click,
            boolean showCoverArt,
            boolean showAlbum,
            AlbumID3 album,
            String contextArtist
    ) {
        this.click = click;
        this.showCoverArt = showCoverArt;
        this.showAlbum = showAlbum;
        this.songs = Collections.emptyList();
        this.songsFull = Collections.emptyList();
        this.currentFilter = "";
        this.album = album;
        this.contextArtist = contextArtist;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHorizontalTrackBinding view = ItemHorizontalTrackBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Child song = songs.get(position);

        holder.item.searchResultSongTitleTextView.setText(song.getTitle());

        // cassette: album/artist context — hide a redundant primary, keep "feat. X".
        // showAlbum lists (legacy) still prefer the album name as the secondary line.
        String subtitlePrimary;
        if (this.showAlbum) {
            subtitlePrimary = song.getAlbum();
        } else if (this.contextArtist != null && !this.contextArtist.trim().isEmpty()) {
            subtitlePrimary = CassetteTrackSubtitle.contextual(song.getArtist(), this.contextArtist);
        } else {
            subtitlePrimary = song.getArtist();
        }

        if (subtitlePrimary == null || subtitlePrimary.trim().isEmpty()) {
            holder.item.searchResultSongSubtitleTextView.setVisibility(View.GONE);
        } else {
            holder.item.searchResultSongSubtitleTextView.setVisibility(View.VISIBLE);
            holder.item.searchResultSongSubtitleTextView.setText(subtitlePrimary);
        }

        holder.item.trackNumberTextView.setText(MusicUtil.getReadableTrackNumber(holder.itemView.getContext(), song.getTrack()));

        // cassette: duration in the trailing slot; overflow is long-press.
        if (song.getDuration() != null && song.getDuration() > 0) {
            holder.item.searchResultSongDurationTextView.setVisibility(View.VISIBLE);
            holder.item.searchResultSongDurationTextView.setText(
                    MusicUtil.getReadableDurationString(song.getDuration(), false));
        } else {
            holder.item.searchResultSongDurationTextView.setVisibility(View.GONE);
            holder.item.searchResultSongDurationTextView.setText("");
        }

        if (showCoverArt) CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.songCoverImageView);

        holder.item.trackNumberTextView.setVisibility(showCoverArt ? View.INVISIBLE : View.VISIBLE);
        holder.item.songCoverImageView.setVisibility(showCoverArt ? View.VISIBLE : View.INVISIBLE);

        // cassette: the title is anchored to the end of the cover slot. On an
        // album page the cover is hidden and only the track number leads, so
        // collapse that slot to the number's width — otherwise the empty 48dp
        // art column holds every title needlessly far to the right. Rows that
        // show real art (search, lists) keep the full slot.
        ViewGroup.LayoutParams leadParams = holder.item.songCoverImageView.getLayoutParams();
        int leadWidth = holder.itemView.getResources().getDimensionPixelSize(
                showCoverArt ? R.dimen.track_row_art : R.dimen.track_row_number);
        if (leadParams.width != leadWidth) {
            leadParams.width = leadWidth;
            holder.item.songCoverImageView.setLayoutParams(leadParams);
        }

        // cassette: mark the row that is actually sounding — accent title plus
        // the animated bars standing in for the track number, so the list
        // answers "where am I" without opening the player. The animation is
        // explicitly stopped on every other row: a recycled ImageView keeps its
        // AnimationDrawable running otherwise, and stray rows twitch.
        bindNowPlaying(holder, song);

        // cassette: a disc heading is only information when there is more than
        // one disc. Upstream showed it above the first track of every album, so
        // single-disc records read "Disc 1" for no reason, and records with no
        // disc numbers at all got the divider with an empty label — two stray
        // rules above the tracklist. The heading now appears only at a real
        // disc change on a genuinely multi-disc album, and the else branch
        // clears it: without one, recycled rows carried the divider down the
        // list.
        boolean discBreak = !showCoverArt && hasMultipleDiscs() && (
                position == 0 ||
                        (songs.get(position - 1) != null &&
                                songs.get(position - 1).getDiscNumber() != null &&
                                songs.get(position).getDiscNumber() != null &&
                                songs.get(position - 1).getDiscNumber() < songs.get(position).getDiscNumber()
                        )
        );

        if (discBreak) {
            holder.item.differentDiskDividerSector.setVisibility(View.VISIBLE);

            if (songs.get(position).getDiscNumber() != null && !Objects.requireNonNull(songs.get(position).getDiscNumber()).toString().isBlank()) {
                holder.item.discTitleTextView.setText(holder.itemView.getContext().getString(R.string.disc_titleless, songs.get(position).getDiscNumber().toString()));
            }

            if (album != null && album.getDiscTitles() != null) {
                Optional<DiscTitle> discTitle = album.getDiscTitles().stream().filter(title -> Objects.equals(title.getDisc(), songs.get(position).getDiscNumber())).findFirst();

                if (discTitle.isPresent() && discTitle.get().getDisc() != null && discTitle.get().getTitle() != null && !discTitle.get().getTitle().isEmpty()) {
                    holder.item.discTitleTextView.setText(holder.itemView.getContext().getString(R.string.disc_titlefull, discTitle.get().getDisc().toString() , discTitle.get().getTitle()));
                }
            }
        } else {
            holder.item.differentDiskDividerSector.setVisibility(View.GONE);
        }

        // cassette: no star ratings. Founder directive - Cassette has no
        // rating concept, so the five-star + heart indicator is always hidden
        // (iOS removed it from rows and menus entirely).
        holder.item.ratingIndicatorImageView.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    public void setItems(List<Child> songs) {
        this.songsFull = songs != null ? songs : Collections.emptyList();
        filtering.filter(currentFilter);
        notifyDataSetChanged();
    }

    /**
     * cassette: the id of the track currently sounding, pushed in by the host
     * fragment from the app-wide now-playing signal. Null clears the marker.
     */
    public void setNowPlaying(String mediaId, boolean isPlaying) {
        if (Objects.equals(this.nowPlayingId, mediaId) && this.nowPlayingIsPlaying == isPlaying) return;
        this.nowPlayingId = mediaId;
        this.nowPlayingIsPlaying = isPlaying;
        notifyDataSetChanged();
    }

    private void bindNowPlaying(ViewHolder holder, Child song) {
        boolean isCurrent = nowPlayingId != null && nowPlayingId.equals(song.getId());
        android.graphics.drawable.Drawable indicator = holder.item.nowPlayingIndicator.getDrawable();

        if (isCurrent) {
            holder.item.searchResultSongTitleTextView.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                            holder.itemView, com.google.android.material.R.attr.colorPrimary));
            if (!showCoverArt) {
                holder.item.trackNumberTextView.setVisibility(View.INVISIBLE);
                holder.item.nowPlayingIndicator.setVisibility(View.VISIBLE);
            }
            if (indicator instanceof android.graphics.drawable.AnimationDrawable) {
                android.graphics.drawable.AnimationDrawable bars = (android.graphics.drawable.AnimationDrawable) indicator;
                if (nowPlayingIsPlaying) bars.start(); else bars.stop();
            }
        } else {
            holder.item.searchResultSongTitleTextView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                            holder.itemView.getContext(), R.color.titleTextColor));
            holder.item.nowPlayingIndicator.setVisibility(View.GONE);
            if (indicator instanceof android.graphics.drawable.AnimationDrawable) {
                ((android.graphics.drawable.AnimationDrawable) indicator).stop();
            }
        }
    }

    /**
     * cassette: true only when the tracklist really spans more than one disc.
     * Tracks with no disc number are treated as one disc, which is the common
     * case for a rip that never carried DISCNUMBER at all.
     */
    private boolean hasMultipleDiscs() {
        Integer seen = null;
        for (Child song : songs) {
            if (song == null || song.getDiscNumber() == null) continue;
            if (seen == null) {
                seen = song.getDiscNumber();
            } else if (!seen.equals(song.getDiscNumber())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public Filter getFilter() {
        return filtering;
    }

    public Child getItem(int id) {
        return songs.get(id);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemHorizontalTrackBinding item;

        ViewHolder(ItemHorizontalTrackBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnClickListener(v -> onClick());
            // Overflow via long-press (web parity: duration owns the trailing slot).
            itemView.setOnLongClickListener(v -> onLongClick());
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(MusicUtil.limitPlayableMedia(songs, getBindingAdapterPosition())));
            bundle.putInt(Constants.ITEM_POSITION, MusicUtil.getPlayableMediaPosition(songs, getBindingAdapterPosition()));

            click.onMediaClick(bundle);
        }

        private boolean onLongClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, songs.get(getBindingAdapterPosition()));

            click.onMediaLongClick(bundle);

            return true;
        }
    }

    public void sort(String order) {
        switch (order) {
            case Constants.MEDIA_BY_TITLE:
                songs.sort(Comparator.comparing(Child::getTitle));
                break;
            case Constants.MEDIA_MOST_RECENTLY_STARRED:
                songs.sort(Comparator.comparing(Child::getStarred, Comparator.nullsLast(Comparator.reverseOrder())));
                break;
            case Constants.MEDIA_LEAST_RECENTLY_STARRED:
                songs.sort(Comparator.comparing(Child::getStarred, Comparator.nullsLast(Comparator.naturalOrder())));
                break;
        }

        notifyDataSetChanged();
    }
}
