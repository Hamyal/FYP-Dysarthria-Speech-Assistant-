package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

public class GamesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_games);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        findViewById(R.id.cardMemoryMatch).setOnClickListener(v -> startActivity(new Intent(this, MemoryGameActivity.class)));
        findViewById(R.id.btnMemoryMatch).setOnClickListener(v -> startActivity(new Intent(this, MemoryGameActivity.class)));

        findViewById(R.id.cardWordScramble).setOnClickListener(v -> startActivity(new Intent(this, WordScrambleActivity.class)));
        findViewById(R.id.btnWordScramble).setOnClickListener(v -> startActivity(new Intent(this, WordScrambleActivity.class)));
    }
}
