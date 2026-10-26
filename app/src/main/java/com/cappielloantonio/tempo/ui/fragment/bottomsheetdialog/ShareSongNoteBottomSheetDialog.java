package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.FragmentActivity;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.util.CassetteShareSong;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

/**
 * Cassette: the note sheet behind the share sheet's Add a note (CassetteShareSong). Send
 * brings the share sheet back with the same link, now carrying the note. A note being
 * written survives the sheet being swiped away (CassetteShareSong.Drafts) until it is sent.
 */
public class ShareSongNoteBottomSheetDialog extends BottomSheetDialogFragment {
    private static final String ARG_CODE = "share_code";

    public static ShareSongNoteBottomSheetDialog newInstance(String code) {
        Bundle args = new Bundle();
        args.putString(ARG_CODE, code);
        ShareSongNoteBottomSheetDialog dialog = new ShareSongNoteBottomSheetDialog();
        dialog.setArguments(args);
        return dialog;
    }

    private TextInputEditText noteEditText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_share_song_note, container, false);
        String code = requireArguments().getString(ARG_CODE);
        CassetteShareSong.Subject subject = code != null ? CassetteShareSong.noteSubject(code) : null;
        if (subject == null) {
            // the share it was for is gone (the app restarted): nothing to attach a note to
            dismissAllowingStateLoss();
            return view;
        }

        CustomGlideRequest.Builder
                .from(requireContext(), subject.coverArtId, CustomGlideRequest.ResourceType.Song)
                .build()
                .into((ImageView) view.findViewById(R.id.share_note_cover_image_view));
        ((TextView) view.findViewById(R.id.share_note_title_text_view)).setText(subject.title);
        ((TextView) view.findViewById(R.id.share_note_artist_text_view)).setText(subject.artist);

        MaterialButtonToggleGroup startToggle = view.findViewById(R.id.share_note_start_toggle);
        if (subject.elapsed != null) {
            MaterialButton fromHere = view.findViewById(R.id.share_note_from_here_button);
            fromHere.setText(getString(R.string.share_song_from_here, clock(subject.elapsed)));
            startToggle.setVisibility(View.VISIBLE);
        }

        noteEditText = view.findViewById(R.id.share_note_edit_text);
        paint(view, startToggle);
        noteEditText.setText(CassetteShareSong.Drafts.note(subject.songId));
        noteEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                CassetteShareSong.Drafts.keep(s.toString(), subject.songId);
            }
        });

        view.findViewById(R.id.share_note_send_button).setOnClickListener(v -> {
            Editable text = noteEditText.getText();
            String note = text != null ? text.toString().trim() : "";
            Integer startSeconds = startToggle.getCheckedButtonId() == R.id.share_note_from_here_button
                    ? subject.elapsed : null;
            FragmentActivity activity = requireActivity();
            dismiss();
            CassetteShareSong.sendWithNote(activity, code, note.isEmpty() ? null : note, startSeconds);
        });
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!(getDialog() instanceof BottomSheetDialog) || noteEditText == null) return;
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        // the person asked to write a note: the keyboard comes up once the sheet has settled
        noteEditText.postDelayed(() -> {
            if (noteEditText == null || !isAdded()) return;
            noteEditText.requestFocus();
            InputMethodManager ime = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.showSoftInput(noteEditText, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    @Override
    public void onDestroyView() {
        noteEditText = null;
        super.onDestroyView();
    }

    /**
     * Cassette's sheets swap the accent for a grey (Cassette.BottomSheet, so their menus don't
     * tint brown), which would leave Send grey on grey. This sheet takes the account's own
     * accent back from the activity for Send, the note field and the start choice.
     */
    private void paint(View view, MaterialButtonToggleGroup startToggle) {
        Context activity = requireActivity();
        int accent = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimary, Color.GRAY);
        int onAccent = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK);
        int ink = requireContext().getColor(R.color.titleTextColor);
        int quiet = requireContext().getColor(R.color.subtitleTextColor);
        int rule = ColorUtils.setAlphaComponent(quiet, 0x66);
        int[][] checkedOrNot = {{android.R.attr.state_checked}, {}};

        MaterialButton send = view.findViewById(R.id.share_note_send_button);
        send.setBackgroundTintList(ColorStateList.valueOf(accent));
        send.setTextColor(onAccent);

        TextInputLayout field = view.findViewById(R.id.share_note_input_layout);
        field.setBoxStrokeColorStateList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{accent, rule}));
        field.setDefaultHintTextColor(ColorStateList.valueOf(quiet));
        field.setHintTextColor(ColorStateList.valueOf(accent));
        noteEditText.setTextColor(ink);

        for (int i = 0; i < startToggle.getChildCount(); i++) {
            MaterialButton choice = (MaterialButton) startToggle.getChildAt(i);
            choice.setBackgroundTintList(new ColorStateList(checkedOrNot,
                    new int[]{ColorUtils.setAlphaComponent(accent, 0x40), Color.TRANSPARENT}));
            choice.setTextColor(new ColorStateList(checkedOrNot, new int[]{ink, quiet}));
            choice.setStrokeColor(ColorStateList.valueOf(rule));
        }
        startToggle.check(R.id.share_note_from_start_button);
    }

    private static String clock(int seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
