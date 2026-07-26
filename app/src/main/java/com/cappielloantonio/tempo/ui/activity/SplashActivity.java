package com.cappielloantonio.tempo.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.cappielloantonio.tempo.R;

/**
 * cassette: a dedicated launch screen that plays the lockup reveal in ISOLATION.
 *
 * The reveal used to run inside MainActivity.onCreate, which cold-starts the
 * whole app on the main thread — inflating the nav host and fragments, building
 * the cover grids, kicking the sync + cover sweep. A ViewPropertyAnimator ticks
 * on the main thread, so the animation was starved of frames by all that work
 * and looked laggy (a hardware layer only cut render cost, not the tick
 * starvation). This activity does nothing but animate, so the main thread is
 * free and the scale/slide is smooth. On completion it hands off to MainActivity
 * with a fade; MainActivity then does its heavy startup with no animation to jank.
 */
public class SplashActivity extends AppCompatActivity {

    private boolean handedOff = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_splash_lockup);

        final View root = findViewById(R.id.splash_lockup_root);
        final View lockup = findViewById(R.id.splash_lockup);
        final View mark = findViewById(R.id.splash_lockup_mark);
        final View word = findViewById(R.id.splash_lockup_word);
        final float startScale = 1.55f;

        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (word.getWidth() == 0 || mark.getWidth() == 0) return true;
                root.getViewTreeObserver().removeOnPreDrawListener(this);

                final float gap = word.getLeft() - mark.getRight();     // marginStart, px
                final float shift = (word.getWidth() + gap) / 2f;        // centers the mark at start
                final float tuck = -(mark.getWidth() + gap);             // wordmark hidden under the mark

                lockup.setTranslationX(shift);
                mark.setScaleX(startScale);
                mark.setScaleY(startScale);
                word.setAlpha(0f);
                word.setTranslationX(tuck);

                final DecelerateInterpolator decel = new DecelerateInterpolator(1.6f);
                lockup.animate().translationX(0f).setDuration(560).setInterpolator(decel);
                mark.animate().scaleX(1f).scaleY(1f).setDuration(560).setInterpolator(decel);
                word.animate().alpha(1f).translationX(0f).setStartDelay(120)
                        .setDuration(480).setInterpolator(decel);

                root.postDelayed(SplashActivity.this::goToMain, 980);
                return true;
            }
        });

        // Safety net: never strand the user on the splash if a frame never comes.
        root.postDelayed(this::goToMain, 2500);
    }

    private void goToMain() {
        if (handedOff) return;
        handedOff = true;
        Intent intent = new Intent(this, MainActivity.class);
        Intent launch = getIntent();
        if (launch != null) {
            intent.setData(launch.getData());
            if (launch.getExtras() != null) intent.putExtras(launch.getExtras());
        }
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
