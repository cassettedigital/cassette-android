package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.cappielloantonio.tempo.R;

/**
 * cassette: apply the account avatar (photo URL or default disc) to the
 * toolbar chip. Photo wins; otherwise silhouette / initials / color disc
 * from {@link Preferences#getCassetteAvatarPreset()}.
 */
public final class CassetteAvatar {
    private CassetteAvatar() {}

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
                    .placeholder(R.drawable.ic_lucide_user)
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
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new LinearGradient(0, 0, size, size,
                    Color.parseColor("#3A342C"), Color.parseColor("#231F18"),
                    Shader.TileMode.CLAMP));
            c.drawCircle(size / 2f, size / 2f, size / 2f, bg);
            android.graphics.drawable.Drawable icon =
                    ContextCompat.getDrawable(context, R.drawable.ic_lucide_user);
            if (icon != null) {
                int inset = size / 4;
                icon.setBounds(inset, inset, size - inset, size - inset);
                icon.setTint(Color.parseColor("#C8C0B4"));
                icon.draw(c);
            }
            return bmp;
        }

        if ("initials".equals(preset)) {
            String label = Preferences.getCassetteAccountName();
            if (label == null || label.isEmpty()) label = Preferences.getCassetteAccountEmail();
            if (label == null || label.isEmpty()) label = "?";
            String initials = label.trim().substring(0, Math.min(2, label.trim().length())).toUpperCase();
            return disc(size, Color.parseColor("#3A342C"), Color.parseColor("#231F18"), initials, null);
        }

        int[] colors = colorsFor(preset);
        return disc(size, colors[0], colors[1], null, "♪");
    }

    private static int[] colorsFor(String preset) {
        switch (preset) {
            case "moss":
                return new int[]{Color.parseColor("#3D5A3A"), Color.parseColor("#1E2E1C")};
            case "marine":
                return new int[]{Color.parseColor("#2A6A6E"), Color.parseColor("#163438")};
            case "violet":
                return new int[]{Color.parseColor("#6B4A9A"), Color.parseColor("#2E1F45")};
            case "rose":
                return new int[]{Color.parseColor("#9A4A62"), Color.parseColor("#3D1F28")};
            case "ember":
            default:
                return new int[]{Color.parseColor("#F27326"), Color.parseColor("#B24714")};
        }
    }

    private static Bitmap disc(int size, int c0, int c1, @Nullable String text, @Nullable String mark) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, size, size, c0, c1, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, bg);
        Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        fg.setColor(Color.WHITE);
        fg.setTextAlign(Paint.Align.CENTER);
        String draw = text != null ? text : mark;
        if (draw != null) {
            fg.setTextSize(size * (text != null ? 0.38f : 0.45f));
            Paint.FontMetrics fm = fg.getFontMetrics();
            float y = size / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(draw, size / 2f, y, fg);
        }
        return bmp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
