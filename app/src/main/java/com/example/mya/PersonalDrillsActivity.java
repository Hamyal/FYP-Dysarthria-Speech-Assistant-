package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/** Patient: self-practice. Difficulty is limited to therapist-set level; advance by practice (80%+). */
public class PersonalDrillsActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";

    private String patientId;
    private RadioGroup difficultyGroup;
    private RadioButton radioEasy, radioMedium, radioHard;
    private String allowedLevel = "easy";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_personal_drills);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        patientId = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_ID) : null;
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (patientId == null && user != null) patientId = user.getUid();
        if (patientId == null) {
            Toast.makeText(this, "Not logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        difficultyGroup = findViewById(R.id.difficultyGroup);
        radioEasy = findViewById(R.id.radioEasy);
        radioMedium = findViewById(R.id.radioMedium);
        radioHard = findViewById(R.id.radioHard);

        findViewById(R.id.btnStartPractice).setOnClickListener(v -> startPractice());

        loadTherapistSetLevel();
    }

    private void loadTherapistSetLevel() {
        FirebaseHelper.getPatientSpeechLevelRef(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String level = snapshot.getValue(String.class);
                if (level == null) level = "easy";
                allowedLevel = level;
                applyLevelRestriction();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    /** Patient can only select up to therapist-set level. Medium/Hard unlock by practice (80%+), not manually. */
    private void applyLevelRestriction() {
        radioEasy.setEnabled(true);
        radioMedium.setEnabled("medium".equalsIgnoreCase(allowedLevel) || "hard".equalsIgnoreCase(allowedLevel));
        radioHard.setEnabled("hard".equalsIgnoreCase(allowedLevel));

        int checkId = R.id.radioEasy;
        if ("hard".equalsIgnoreCase(allowedLevel)) checkId = R.id.radioHard;
        else if ("medium".equalsIgnoreCase(allowedLevel)) checkId = R.id.radioMedium;
        difficultyGroup.check(checkId);
    }

    private void startPractice() {
        int rid = difficultyGroup.getCheckedRadioButtonId();
        String difficulty = "easy";
        if (rid == R.id.radioMedium) difficulty = "medium";
        else if (rid == R.id.radioHard) difficulty = "hard";

        String words = DrillWordProvider.getRandomWordsAsString(difficulty, 5, ", ");
        AssignedDrill drill = new AssignedDrill();
        drill.setTitle("Personalized");
        drill.setTargetWords(words);
        drill.setDifficulty(difficulty);
        drill.setAssignedDrillId("");  // no therapist assignment
        drill.setTherapistId("");
        drill.setTherapistName("");

        Intent i = new Intent(this, RecordDrillActivity.class);
        i.putExtra("drill", drill);
        i.putExtra("patient_id", patientId);
        i.putExtra("is_personalized", true);
        startActivityForResult(i, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Session saved by RecordDrillActivity
    }
}
