package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.card.MaterialCardView;

public class RegisterChoiceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_register_choice);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialCardView cardTherapist = findViewById(R.id.cardTherapist);
        MaterialCardView cardPatient = findViewById(R.id.cardPatient);
        View btnLogin = findViewById(R.id.btnLogin);

        cardTherapist.setOnClickListener(v -> {
            startActivity(new Intent(this, TherapistRegisterActivity.class));
            finish();
        });
        cardPatient.setOnClickListener(v -> {
            startActivity(new Intent(this, PatientRegisterActivity.class));
            finish();
        });
        btnLogin.setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }
}
