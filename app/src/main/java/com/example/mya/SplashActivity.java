package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY_MS = 2200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        startSparkleAnimations();
        new Handler(Looper.getMainLooper()).postDelayed(this::goToNext, SPLASH_DELAY_MS);
    }

    private void startSparkleAnimations() {
        int[] sparkleIds = {
                R.id.sparkle1, R.id.sparkle2, R.id.sparkle3, R.id.sparkle4,
                R.id.sparkle5, R.id.sparkle6, R.id.sparkle7, R.id.sparkle8,
                R.id.sparkle9, R.id.sparkle10,
                R.id.sparkleLogo1, R.id.sparkleLogo2, R.id.sparkleLogo3, R.id.sparkleLogo4
        };
        for (int i = 0; i < sparkleIds.length; i++) {
            View v = findViewById(sparkleIds[i]);
            if (v instanceof ImageView) {
                Animation a = AnimationUtils.loadAnimation(this, R.anim.twinkle);
                if (a != null) {
                    a.setRepeatMode(Animation.REVERSE);
                    a.setRepeatCount(Animation.INFINITE);
                    a.setStartOffset(i * 120L);
                    v.startAnimation(a);
                }
            }
        }
    }

    private void goToNext() {
        if (isFinishing()) return;
        if (FirebaseHelper.getCurrentUser() != null) {
            Intent i = new Intent(this, LoginActivity.class);
            startActivity(i);
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
