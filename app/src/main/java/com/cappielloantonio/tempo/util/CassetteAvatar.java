package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.cappielloantonio.tempo.R;

/**
 * cassette: apply the account avatar (photo URL or default disc) to the
 * toolbar chip. Photo wins; otherwise silhouette / initials / emoji from
 * {@link Preferences#getCassetteAvatarPreset()}.
 *
 * Silhouette matches iOS: cream person.circle.fill (circle with person cut out).
 * Initials / emoji sit on a neutral grey disc.
 */
public final class CassetteAvatar {
    private CassetteAvatar() {}

    /** Neutral milled key — approximates web --cs-bg3. */
    private static final int DISC_BG = Color.parseColor("#2D2820");
    private static final int DISC_INK = Color.parseColor("#EDE4D0");

    public static void apply(ImageView view) {
        if (view == null) return;
        Context context = view.getContext();
        String image = Preferences.getCassetteAccountImage();
        if (image != null && !image.isEmpty()) {
            view.setPadding(0, 0, 0, 0);
            view.setBackground(null);
            Glide.with(context)
                    .load(image)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_person_circle_fill)
                    .into(view);
            return;
        }

        String preset = Preferences.getCassetteAvatarPreset();
        int size = dp(context, 34);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(null);
        view.setImageDrawable(new BitmapDrawable(context.getResources(), renderPreset(context, preset, size)));
    }

    private static Bitmap renderPreset(Context context, String preset, int size) {
        if ("silhouette".equals(preset) || preset == null || preset.isEmpty()) {
            return silhouette(context, size);
        }

        if ("initials".equals(preset)) {
            String label = Preferences.getCassetteAccountName();
            if (label == null || label.isEmpty()) label = Preferences.getCassetteAccountEmail();
            if (label == null || label.isEmpty()) label = "?";
            String initials = label.trim().substring(0, Math.min(2, label.trim().length())).toUpperCase();
            return disc(size, initials, 0.38f);
        }

        if (preset.startsWith("emoji:") && preset.length() > "emoji:".length()) {
            return disc(size, preset.substring("emoji:".length()), 0.55f);
        }

        // Legacy color-disc ids → silhouette.
        return silhouette(context, size);
    }

    private static Bitmap silhouette(Context context, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable icon = ContextCompat.getDrawable(context, R.drawable.ic_person_circle_fill);
        if (icon != null) {
            Drawable tinted = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(tinted, DISC_INK);
            // Match iOS optical size (~0.9 of the chip).
            int inset = Math.round(size * 0.05f);
            tinted.setBounds(inset, inset, size - inset, size - inset);
            tinted.draw(c);
        }
        return bmp;
    }

    private static Bitmap disc(int size, @Nullable String text, float textScale) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(DISC_BG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, bg);
        if (text != null) {
            Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
            fg.setColor(DISC_INK);
            fg.setTextAlign(Paint.Align.CENTER);
            fg.setTextSize(size * textScale);
            Paint.FontMetrics fm = fg.getFontMetrics();
            float y = size / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(text, size / 2f, y, fg);
        }
        return bmp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
