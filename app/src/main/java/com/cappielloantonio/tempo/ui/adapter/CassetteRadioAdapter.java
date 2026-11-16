package com.cappielloantonio.tempo.ui.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemRadioOnAirBinding;
import com.cappielloantonio.tempo.databinding.ItemRadioStationBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.util.CassetteOnNowBoard;
import com.cappielloantonio.tempo.util.CassetteRadio;
import com.cappielloantonio.tempo.util.CassetteRadioLive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * cassette: the Radio page, the one the Mac app and the website draw (apps/cassette/src/app/components/radio/
 * RadioDial.tsx; the founder, 2026-11-16: "build the Radio page across surfaces"). On now first: the person's
 * saved stations, then the live stations in today's turn, six at most, as cards that fill in one after another
 * (CassetteOnNowBoard). Then a row of section chips to jump with, then every station on the dial in its
 * section. Saved stations lead On now and their own sections. A tap on a station opens its page; only a play
 * disc tunes in. Orange marks the station on air.
 */
public class CassetteRadioAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements CassetteOnNowBoard.Listener {
    public interface Listener {
        void onOpen(CassetteRadio.Station station);

        /** A play disc: tune in, or, `on` (it's the station playing), stop it. */
        void onDisc(CassetteRadio.Station station, boolean on);

        void onMenu(View anchor, CassetteRadio.Station station);

        /** A section chip: jump to that section (its place in `sectionTitles`). */
        void onJump(int section);
    }

    private static final int ON_NOW = 0;
    private static final int ON_AIR = 1;
    private static final int CHIPS = 2;
    private static final int LABEL = 3;
    private static final int STATION = 4;
    /** A card's own news, drawn in place (and animated) rather than bound afresh. */
    private static final Object LIVE = new Object();

    /** One card in On now, by its station. */
    private static final class OnAirRow {
        final CassetteRadio.Station station;

        OnAirRow(CassetteRadio.Station station) {
            this.station = station;
        }
    }

    /** A section's name on the dial. */
    private static final class Label {
        final String title;

        Label(String title) {
            this.title = title;
        }
    }

    private static final Object ON_NOW_HEADING = new Object();
    private static final Object CHIP_ROW = new Object();

    private final Listener listener;
    private final CassetteOnNowBoard board;
    private final List<Object> rows = new ArrayList<>();
    private final List<String> sectionTitles = new ArrayList<>();
    private final List<Integer> sectionStarts = new ArrayList<>();
    private List<CassetteRadio.Station> all = new ArrayList<>();
    private List<String> saved = new ArrayList<>();
    private String onAir;
    private boolean playing;

    public CassetteRadioAdapter(Context context, Listener listener) {
        this.listener = listener;
        this.board = new CassetteOnNowBoard(context, this);
    }

    /** Your stations, latest save first: they lead On now and their own sections. */
    public void setSaved(List<String> slugs) {
        saved = slugs != null ? slugs : new ArrayList<>();
        rebuild();
    }

    public void setStations(List<CassetteRadio.Station> stations) {
        all = stations != null ? stations : new ArrayList<>();
        rebuild();
    }

    /** What's sounding (the player's media id) and whether it's playing. */
    public void setNowPlaying(String mediaId, boolean isPlaying) {
        if (equal(mediaId, onAir) && isPlaying == playing) return;
        onAir = mediaId;
        playing = isPlaying;
        notifyItemRangeChanged(0, rows.size(), LIVE);
    }

    /** The sections, in the chips' order. */
    public List<String> getSectionTitles() {
        return sectionTitles;
    }

    /** Where a section's name sits in the list, or -1. */
    public int sectionStart(int section) {
        return section >= 0 && section < sectionStarts.size() ? sectionStarts.get(section) : -1;
    }

    /** The in-list row of chips, or -1 when there is none. */
    public int chipsPosition() {
        return rows.indexOf(CHIP_ROW);
    }

    /** Back on the page after a while: the cards ask what's on again. */
    public void rereadIfStale(long olderThanMs) {
        if (board.getLastRead() > 0 && System.currentTimeMillis() - board.getLastRead() > olderThanMs) board.reread();
    }

    /** The page is gone. */
    public void release() {
        board.release();
    }

    private void rebuild() {
        rows.clear();
        sectionTitles.clear();
        sectionStarts.clear();
        List<CassetteRadio.Station> onNow = CassetteRadio.onNowStations(all, saved);
        if (!onNow.isEmpty()) {
            rows.add(ON_NOW_HEADING);
            for (CassetteRadio.Station s : onNow) rows.add(new OnAirRow(s));
        }
        // the dial in the Mac's order; a section comes in where its first station does
        Map<String, List<CassetteRadio.Station>> sections = new LinkedHashMap<>();
        for (CassetteRadio.Station s : CassetteRadio.dial(all, saved)) {
            List<CassetteRadio.Station> list = sections.get(s.getSection());
            if (list == null) {
                list = new ArrayList<>();
                sections.put(s.getSection(), list);
            }
            list.add(s);
        }
        int named = 0;
        for (String title : sections.keySet()) if (!title.isEmpty()) named++;
        if (named > 1) rows.add(CHIP_ROW);
        for (Map.Entry<String, List<CassetteRadio.Station>> e : sections.entrySet()) {
            if (!e.getKey().isEmpty()) {
                sectionTitles.add(e.getKey());
                sectionStarts.add(rows.size());
                rows.add(new Label(e.getKey()));
            }
            rows.addAll(e.getValue());
        }
        board.show(onNow);
        notifyDataSetChanged();
    }

    /** A card has news: draw it where it stands. */
    @Override
    public void changed(@NonNull String slug) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof OnAirRow && ((OnAirRow) row).station.getSlug().equals(slug)) {
                notifyItemChanged(i, LIVE);
                return;
            }
        }
    }

    private boolean isOn(CassetteRadio.Station station) {
        return playing && ("cassette-radio-" + station.getSlug()).equals(onAir);
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row == ON_NOW_HEADING) return ON_NOW;
        if (row instanceof OnAirRow) return ON_AIR;
        if (row == CHIP_ROW) return CHIPS;
        if (row instanceof Label) return LABEL;
        return STATION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case ON_NOW:
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_radio_on_now_heading, parent, false)) { };
            case ON_AIR:
                return new OnAirHolder(ItemRadioOnAirBinding.inflate(inflater, parent, false));
            case CHIPS:
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_radio_chips, parent, false)) { };
            case LABEL:
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_radio_heading, parent, false)) { };
            default:
                return new StationHolder(ItemRadioStationBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(LIVE)) {
            Object row = rows.get(position);
            if (holder instanceof OnAirHolder && row instanceof OnAirRow) {
                bindOnAir((OnAirHolder) holder, ((OnAirRow) row).station, true);
                return;
            }
            if (holder instanceof StationHolder && row instanceof CassetteRadio.Station) {
                paintPlaying((StationHolder) holder, (CassetteRadio.Station) row);
                return;
            }
            if (!(holder instanceof OnAirHolder) && !(holder instanceof StationHolder)) return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (row instanceof OnAirRow) {
            bindOnAir((OnAirHolder) holder, ((OnAirRow) row).station, false);
        } else if (row == CHIP_ROW) {
            fillChips((LinearLayout) holder.itemView.findViewById(R.id.radio_chips), sectionTitles, listener);
        } else if (row instanceof Label) {
            ((TextView) holder.itemView).setText(((Label) row).title);
        } else if (row instanceof CassetteRadio.Station) {
            bindStation((StationHolder) holder, (CassetteRadio.Station) row);
        }
    }

    // ── On now ──

    private void bindOnAir(OnAirHolder h, CassetteRadio.Station station, boolean animated) {
        Context context = h.itemView.getContext();
        CassetteOnNowBoard.Card card = board.card(station.getSlug());
        if (!station.getSlug().equals(h.slug)) {
            // a card drawn for another station: start it whole, words and all
            h.slug = station.getSlug();
            h.shownNow = null;
            h.coverUrl = null;
            h.b.onAirArt.reset();
            loadMark(h, station);
            animated = false;
        }
        h.b.onAirStation.setText(station.getName());

        boolean shown = card != null && card.getShown();
        CassetteRadioLive.OnAir answer = card != null ? card.getAnswer() : null;
        String now = shown && answer != null && answer.getNow() != null ? answer.getNow() : station.getDescription();
        if (!now.equals(h.shownNow)) {
            boolean fresh = h.shownNow != null && animated;
            h.shownNow = now;
            h.b.onAirNow.setText(now);
            if (fresh) {
                // the words change with the picture, and fade up into place as they do
                h.b.onAirNow.setAlpha(0f);
                h.b.onAirNow.setTranslationY(3 * context.getResources().getDisplayMetrics().density);
                h.b.onAirNow.animate().alpha(1f).translationY(0f).setDuration(700).start();
            }
        }
        String meta = meta(context, station, shown ? answer : null);
        h.b.onAirMeta.setText(meta);
        h.b.onAirMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        String cover = shown && card != null ? card.getCoverUrl() : null;
        if (cover != null && !cover.equals(h.coverUrl)) {
            h.coverUrl = cover;
            // from Glide's memory: the board waited for it to arrive before the card settled
            int px = h.b.onAirArt.getLayoutParams().width;
            Glide.with(h.b.onAirArt).load(cover).centerCrop().override(px, px).into(h.b.onAirArt.getCover());
            h.b.onAirArt.wear(animated);
        } else if (cover == null && h.coverUrl != null) {
            h.coverUrl = null;
            h.b.onAirArt.unwear(animated);
        }

        boolean on = isOn(station);
        Drawable ripple = h.ripple;
        h.b.onAirCard.setForeground(on
                ? new LayerDrawable(new Drawable[]{ripple, ContextCompat.getDrawable(context, R.drawable.cs_on_air_ring)})
                : ripple);
        h.b.onAirPlayGlyph.setImageResource(on ? R.drawable.cs_stop_glyph : R.drawable.cs_play_glyph);
        int pad = Math.round(11 * context.getResources().getDisplayMetrics().density);
        h.b.onAirPlayGlyph.setPaddingRelative(on ? pad : pad + Math.round(context.getResources().getDisplayMetrics().density), pad, pad, pad);
        h.b.onAirPlay.setContentDescription(context.getString(on ? R.string.cassette_stop_station : R.string.cassette_tune_in_to, station.getName()));
        h.b.onAirCard.setContentDescription(station.getName() + ", " + now + (meta.isEmpty() ? "" : ", " + meta));

        h.b.onAirCard.setOnClickListener(v -> listener.onOpen(station));
        h.b.onAirCard.setOnLongClickListener(v -> {
            listener.onMenu(v, station);
            return true;
        });
        h.b.onAirPlay.setOnClickListener(v -> listener.onDisc(station, isOn(station)));
    }

    /**
     * A station's mark at the ladder rung its frame needs, else (the ladder can refuse a size it hasn't made
     * yet: "today's sizes are used up") the station's own picture, as the iPhone's loader falls back.
     */
    private static void loadStationMark(Context context, CassetteRadio.Station station, int px, android.widget.ImageView into) {
        String ladder = station.imageUrl(px);
        com.bumptech.glide.RequestBuilder<Drawable> request =
                CustomGlideRequest.Builder.from(context, ladder, CustomGlideRequest.ResourceType.Radio).build();
        String plain = station.getImage();
        if (plain != null && !plain.equals(ladder)) {
            request = request.error(CustomGlideRequest.Builder.from(context, plain, CustomGlideRequest.ResourceType.Radio).build());
        }
        request.into(into);
    }

    private static void loadMark(OnAirHolder h, CassetteRadio.Station station) {
        int px = h.b.onAirArt.getLayoutParams().width;
        String image = station.imageUrl(px);
        if (image != null) {
            h.b.onAirArt.showLogo();
            loadStationMark(h.itemView.getContext(), station, px, h.b.onAirArt.getMark());
        } else {
            // no picture: its name as word art on its own colour
            Glide.with(h.b.onAirArt).clear(h.b.onAirArt.getMark());
            h.b.onAirArt.showWord(station.getName(), station.accentColor());
        }
    }

    /** "KUSC 91.5 · until 3:00 PM": the network when it isn't the station's own name, and when the show ends while that's still ahead. */
    private static String meta(Context context, CassetteRadio.Station station, CassetteRadioLive.OnAir answer) {
        StringBuilder out = new StringBuilder(station.getNetwork());
        Long until = answer != null ? answer.getUntil() : null;
        if (until != null && until > System.currentTimeMillis()) {
            String clock = android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(until));
            if (out.length() > 0) out.append(" · ");
            out.append(context.getString(R.string.cassette_until, clock));
        }
        return out.toString();
    }

    // ── the chips ──

    /** One chip per section, in the dial's order; a tap jumps to it. The Library's held copy is filled the same way. */
    public static void fillChips(LinearLayout row, List<String> titles, Listener listener) {
        Context context = row.getContext();
        if (titles.equals(row.getTag())) return;
        row.setTag(new ArrayList<>(titles));
        row.removeAllViews();
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < titles.size(); i++) {
            final int section = i;
            TextView chip = new TextView(context);
            chip.setText(titles.get(i));
            chip.setTypeface(ResourcesCompat.getFont(context, R.font.barlow_condensed_semibold));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chip.setTextColor(ContextCompat.getColorStateList(context, R.color.cs_radio_chip_text));
            chip.setBackgroundResource(R.drawable.cs_radio_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setIncludeFontPadding(false);
            int padX = Math.round(12 * density);
            chip.setPadding(padX, 0, padX, 0);
            chip.setOnClickListener(v -> listener.onJump(section));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) lp.setMarginStart(Math.round(6 * density));
            row.addView(chip, lp);
        }
    }

    // ── the dial ──

    private void bindStation(StationHolder holder, CassetteRadio.Station station) {
        ItemRadioStationBinding b = holder.bind;
        b.stationName.setText(station.getName());
        b.stationLabel.setText(station.getKicker());
        b.stationLabel.setVisibility(station.getKicker().isEmpty() ? View.GONE : View.VISIBLE);
        b.stationDescription.setText(station.getDescription());
        b.stationDescription.setVisibility(station.getDescription().isEmpty() ? View.GONE : View.VISIBLE);
        Context context = holder.itemView.getContext();
        int px = b.stationArt.getLayoutParams().width;
        String image = station.imageUrl(px);
        if (image != null) {
            b.stationWordArt.setVisibility(View.GONE);
            loadStationMark(context, station, px, b.stationArt);
        } else {
            // no picture: its name as word art on its own colour
            Glide.with(b.stationArt).clear(b.stationArt);
            b.stationArt.setImageDrawable(new android.graphics.drawable.ColorDrawable(station.accentColor()));
            b.stationWordArt.setVisibility(View.VISIBLE);
            b.stationWordArt.setText(station.getName());
        }
        paintPlaying(holder, station);
        holder.itemView.setOnClickListener(v -> listener.onOpen(station));
        b.stationPlay.getRoot().setOnClickListener(v -> listener.onDisc(station, isOn(station)));
        b.stationPlay.getRoot().setContentDescription(context.getString(R.string.cassette_tune_in_to, station.getName()));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onMenu(v, station);
            return true;
        });
    }

    /** The station on air: its name in the live orange, and the waveform where its disc was. */
    private void paintPlaying(StationHolder holder, CassetteRadio.Station station) {
        ItemRadioStationBinding b = holder.bind;
        boolean on = isOn(station);
        b.stationName.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), on ? R.color.cs_accent_text : R.color.cs_ink));
        b.stationPlay.getRoot().setVisibility(on ? View.GONE : View.VISIBLE);
        b.stationWave.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static class OnAirHolder extends RecyclerView.ViewHolder {
        final ItemRadioOnAirBinding b;
        /** The card's ripple, kept so the on-air ring can sit over it. */
        final Drawable ripple;
        String slug;
        String shownNow;
        String coverUrl;

        OnAirHolder(ItemRadioOnAirBinding b) {
            super(b.getRoot());
            this.b = b;
            this.ripple = b.onAirCard.getForeground();
            // the card's corners round its picture square too
            b.onAirCard.setClipToOutline(true);
        }
    }

    static class StationHolder extends RecyclerView.ViewHolder {
        final ItemRadioStationBinding bind;

        StationHolder(ItemRadioStationBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
