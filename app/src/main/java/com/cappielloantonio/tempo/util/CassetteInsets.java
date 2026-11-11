package com.cappielloantonio.tempo.util;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * cassette: Android 16 draws every app edge to edge once it targets API 36, and the theme's
 * opt-out (windowOptOutEdgeToEdgeEnforcement) stops counting. So each screen keeps its content
 * clear of the status bar, the navigation bar and a camera cutout itself, and paints
 * behind the bars in the colours the theme gave them (statusBarColor, navigationBarColor), so it
 * looks the way it did when the system drew the bars. Every Android version gets the same path.
 *
 * One call per activity, after setContentView. The insets stop here, so no view inside pads twice.
 */
public final class CassetteInsets {
    private CassetteInsets() {}

    public static void keepClearOfBars(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        TypedArray colors = activity.obtainStyledAttributes(
                new int[]{android.R.attr.statusBarColor, android.R.attr.navigationBarColor});
        int statusColor = colors.getColor(0, 0);
        int navigationColor = colors.getColor(1, statusColor);
        colors.recycle();

        BarsBackground behind = new BarsBackground(statusColor, navigationColor);
        content.setBackground(behind);

        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            // Not the keyboard: MainActivity's "adjustPan|adjustResize" adds up to adjustNothing, so the
            // keyboard has always covered the screen rather than squeezing it, and still does.
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            behind.setNavigationHeight(bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /** The status bar's colour everywhere, and the navigation bar's in the strip it covers. */
    private static final class BarsBackground extends Drawable {
        private final Paint status = new Paint();
        private final Paint navigation = new Paint();
        private int navigationHeight;

        BarsBackground(int statusColor, int navigationColor) {
            status.setColor(statusColor);
            navigation.setColor(navigationColor);
        }

        void setNavigationHeight(int height) {
            if (height == navigationHeight) return;
            navigationHeight = height;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, status);
            if (navigationHeight > 0) {
                canvas.drawRect(b.left, b.bottom - navigationHeight, b.right, b.bottom, navigation);
            }
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
