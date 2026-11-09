package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentAllShelvesBinding;
import com.cappielloantonio.tempo.databinding.ItemShelfRowBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.CassetteHome;
import com.cappielloantonio.tempo.util.CassetteType;

import java.util.ArrayList;
import java.util.List;

/**
 * cassette: All shelves (iPhone AllShelvesVC): every shelf made today that this phone holds a
 * record of, the one on Home first: three of its covers, its name in two voices, how many
 * records, ON HOME on the one Home shows. A row opens the shelf.
 */
@UnstableApi
public class AllShelvesFragment extends Fragment {
    private FragmentAllShelvesBinding bind;
    private MainActivity activity;
    private final Adapter adapter = new Adapter();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentAllShelvesBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity.setSupportActionBar(bind.allShelvesToolbar);
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        bind.allShelvesToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        // the heading in two voices, in the bar
        bind.allShelvesToolbar.setTitle(CassetteType.twoVoiceText(requireContext(), getString(R.string.cassette_all_shelves),
                getString(R.string.cassette_today), ContextCompat.getColor(requireContext(), R.color.cs_accent_text), 20));
        bind.allShelvesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.allShelvesList.setAdapter(adapter);
        bind.allShelvesEmpty.emptyStateTitle.setText(R.string.cassette_no_shelves);
        bind.allShelvesEmpty.emptyStateTitle.setVisibility(View.GONE);
        android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            List<CassetteHome.DrawnShelf> shelves = CassetteHome.shelvesNow(app);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (bind == null) return;
                adapter.set(shelves);
                bind.allShelvesEmpty.emptyStateTitle.setVisibility(shelves.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "cassette-all-shelves").start();
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private List<CassetteHome.DrawnShelf> items = new ArrayList<>();

        void set(List<CassetteHome.DrawnShelf> next) {
            items = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemShelfRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            CassetteHome.DrawnShelf s = items.get(position);
            int ink2 = ContextCompat.getColor(h.itemView.getContext(), R.color.cs_accent_text);
            h.bind.shelfName.setText(CassetteType.twoVoiceText(h.itemView.getContext(), s.getShout(), s.getAnswer(), ink2, 17));
            int n = s.getAlbums().size();
            String count = n > 0 ? getResources().getQuantityString(R.plurals.cassette_record_count, n, n) : s.getMix() != null ? s.getMix().getTitle() : "";
            h.bind.shelfCount.setText(s.getOnHome() ? count + " · " + getString(R.string.cassette_on_home) : count);
            com.google.android.material.imageview.ShapeableImageView[] covers = {h.bind.shelfCover1, h.bind.shelfCover2, h.bind.shelfCover3};
            for (int i = 0; i < 3; i++) {
                AlbumID3 a = i < n ? s.getAlbums().get(i) : null;
                covers[i].setVisibility(a != null ? View.VISIBLE : View.INVISIBLE);
                if (a != null) CustomGlideRequest.Builder.from(h.itemView.getContext(), a.getCoverArtId(), CustomGlideRequest.ResourceType.Album)
                        .build().into(covers[i]);
            }
            h.itemView.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putString(GenrePageFragment.SHELF_ID, s.getId());
                activity.navController.navigate(R.id.genrePageFragment, bundle);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemShelfRowBinding bind;

            Holder(ItemShelfRowBinding bind) {
                super(bind.getRoot());
                this.bind = bind;
            }
        }
    }
}
