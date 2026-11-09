package com.cappielloantonio.tempo.ui.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemRadioStationBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.util.CassetteRadio;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: the dial, a heading for each part of it ("Live · United States",
 * moods, eras) and its stations. Orange marks the station on air.
 */
public class CassetteRadioAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onTune(CassetteRadio.Station station);

        void onMenu(View anchor, CassetteRadio.Station station);
    }

    private static final int HEADING = 0;
    private static final int STATION = 1;

    private final Listener listener;
    private final List<Object> rows = new ArrayList<>();
    private String onAir;
    private java.util.Map<String, String> onNow = new java.util.HashMap<>();

    /** What's on at each station (by slug), shown under its name while the dial is up. */
    public void setOnNow(java.util.Map<String, String> lines) {
        onNow = lines != null ? lines : new java.util.HashMap<>();
        notifyDataSetChanged();
    }

    public CassetteRadioAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setStations(List<CassetteRadio.Station> stations) {
        rows.clear();
        String section = null;
        for (CassetteRadio.Station s : stations) {
            if (!s.getSection().equals(section)) {
                section = s.getSection();
                if (!section.isEmpty()) rows.add(section);
            }
            rows.add(s);
        }
        notifyDataSetChanged();
    }

    public void setOnAir(String mediaId) {
        onAir = mediaId;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? HEADING : STATION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == HEADING) {
            TextView heading = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.item_radio_heading, parent, false);
            return new RecyclerView.ViewHolder(heading) { };
        }
        return new StationHolder(ItemRadioStationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof String) {
            ((TextView) holder.itemView).setText(((String) row).toUpperCase(java.util.Locale.getDefault()));
            return;
        }
        CassetteRadio.Station station = (CassetteRadio.Station) row;
        ItemRadioStationBinding b = ((StationHolder) holder).bind;
        b.stationName.setText(station.getName());
        String now = onNow.get(station.getSlug());
        b.stationLabel.setText(now != null ? now : station.getLabel());
        boolean live = ("cassette-radio-" + station.getSlug()).equals(onAir);
        b.stationName.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(),
                live ? R.color.cs_accent : R.color.cs_ink));
        if (station.getImage() != null) {
            b.stationWordArt.setVisibility(View.GONE);
            CustomGlideRequest.Builder.from(holder.itemView.getContext(), station.getImage(), CustomGlideRequest.ResourceType.Radio)
                    .build().into(b.stationArt);
        } else {
            // no picture: its name as word art on its own colour
            int color;
            try {
                color = station.getAccent() != null ? Color.parseColor(station.getAccent()) : 0xFF6B5A44;
            } catch (IllegalArgumentException e) {
                color = 0xFF6B5A44;
            }
            b.stationArt.setImageDrawable(new android.graphics.drawable.ColorDrawable(color));
            b.stationWordArt.setVisibility(View.VISIBLE);
            b.stationWordArt.setText(station.getName());
        }
        holder.itemView.setOnClickListener(v -> listener.onTune(station));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onMenu(v, station);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class StationHolder extends RecyclerView.ViewHolder {
        final ItemRadioStationBinding bind;

        StationHolder(ItemRadioStationBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
