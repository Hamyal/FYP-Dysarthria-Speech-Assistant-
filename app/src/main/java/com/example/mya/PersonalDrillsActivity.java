package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Patient: self-practice with phoneme-level personalization.
 * Uses A/B testing: Group A gets drills targeting weak phonemes, Group B gets random drills.
 * Difficulty is limited to therapist-set level; advance by practice (80%+).
 */
public class PersonalDrillsActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";

    private String patientId;
    private RadioGroup difficultyGroup;
    private RadioButton radioEasy, radioMedium, radioHard;
    private MaterialButton btnStartPractice;
    private TextView weakPhonemesInfo;
    private ProgressBar phonemeProgress;
    private String allowedLevel = "easy";
    private String abGroup = "A";
    private List<PhonemeProfileHelper.PersonalizedDrill> pendingDrills;
    private ValueEventListener speechLevelListener;

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
        btnStartPractice = findViewById(R.id.btnStartPractice);

        // Optional UI elements for phoneme info (gracefully handle if not in layout)
        weakPhonemesInfo = findViewById(R.id.weakPhonemesInfo);
        phonemeProgress = findViewById(R.id.phonemeProgress);

        btnStartPractice.setOnClickListener(v -> startPractice());

        loadTherapistSetLevel();
        loadPhonemeProfile();
    }

    private void loadTherapistSetLevel() {
        speechLevelListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String level = snapshot.getValue(String.class);
                if (level == null) level = "easy";
                allowedLevel = level;
                applyLevelRestriction();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                applyLevelRestriction();
            }
        };
        FirebaseHelper.getPatientSpeechLevelRef(patientId).addValueEventListener(speechLevelListener);
    }

    /** Load and display weak phoneme profile from Firebase (cached from last analysis). */
    private void loadPhonemeProfile() {
        if (phonemeProgress != null) phonemeProgress.setVisibility(View.VISIBLE);

        FirebaseHelper.getPhonemeProfileRef(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (phonemeProgress != null) phonemeProgress.setVisibility(View.GONE);
                if (!snapshot.exists()) {
                    if (weakPhonemesInfo != null) {
                        weakPhonemesInfo.setVisibility(View.VISIBLE);
                        weakPhonemesInfo.setText("Complete a few drills to unlock personalized phoneme targeting.");
                    }
                    return;
                }
                // Display weak phonemes summary
                DataSnapshot weakSnap = snapshot.child("weakPhonemes");
                if (weakSnap.exists() && weakSnap.getChildrenCount() > 0) {
                    StringBuilder sb = new StringBuilder("Weak phonemes: ");
                    int count = 0;
                    for (DataSnapshot ph : weakSnap.getChildren()) {
                        if (count >= 5) { sb.append("..."); break; }
                        String phoneme = ph.child("phoneme").getValue(String.class);
                        Double acc = ph.child("avgAccuracy").getValue(Double.class);
                        if (phoneme != null) {
                            if (count > 0) sb.append(", ");
                            sb.append("/").append(phoneme).append("/");
                            if (acc != null) sb.append(" (").append(String.format("%.0f%%", acc)).append(")");
                            count++;
                        }
                    }
                    if (weakPhonemesInfo != null) {
                        weakPhonemesInfo.setVisibility(View.VISIBLE);
                        weakPhonemesInfo.setText(sb.toString());
                    }
                } else {
                    if (weakPhonemesInfo != null) {
                        weakPhonemesInfo.setVisibility(View.VISIBLE);
                        weakPhonemesInfo.setText("Great job! No weak phonemes detected.");
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (phonemeProgress != null) phonemeProgress.setVisibility(View.GONE);
            }
        });

        // Get A/B group
        FirebaseHelper.getOrAssignAbGroup(patientId, group -> abGroup = group);
    }

    @Override
    protected void onDestroy() {
        if (patientId != null && speechLevelListener != null) {
            FirebaseHelper.getPatientSpeechLevelRef(patientId).removeEventListener(speechLevelListener);
        }
        super.onDestroy();
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

        btnStartPractice.setEnabled(false);
        if (phonemeProgress != null) phonemeProgress.setVisibility(View.VISIBLE);

        final String finalDifficulty = difficulty;

        // Use phoneme personalization API (respects A/B group)
        PhonemeProfileHelper.getPersonalizedDrills(patientId, difficulty, 5, new PhonemeProfileHelper.DrillsCallback() {
            @Override
            public void onSuccess(List<PhonemeProfileHelper.PersonalizedDrill> drills, String group, boolean personalized) {
                btnStartPractice.setEnabled(true);
                if (phonemeProgress != null) phonemeProgress.setVisibility(View.GONE);
                abGroup = group;

                if (drills == null || drills.isEmpty()) {
                    // Fallback to random words
                    launchDrillWithRandomWords(finalDifficulty);
                    return;
                }

                // Use first drill's target text
                StringBuilder targetWords = new StringBuilder();
                List<String> phonemesTargeted = new ArrayList<>();
                for (int i = 0; i < drills.size(); i++) {
                    if (i > 0) targetWords.append(", ");
                    targetWords.append(drills.get(i).targetText);
                    phonemesTargeted.addAll(drills.get(i).targetPhonemes);
                }

                AssignedDrill drill = new AssignedDrill();
                drill.setTitle(personalized ? "Phoneme-Targeted" : "Personalized");
                drill.setTargetWords(targetWords.toString());
                drill.setDifficulty(finalDifficulty);
                drill.setAssignedDrillId("");
                drill.setTherapistId("");
                drill.setTherapistName("");

                Intent i = new Intent(PersonalDrillsActivity.this, RecordDrillActivity.class);
                i.putExtra("drill", drill);
                i.putExtra("patient_id", patientId);
                i.putExtra("is_personalized", true);
                i.putExtra("ab_group", abGroup);
                i.putStringArrayListExtra("phonemes_targeted", new ArrayList<>(phonemesTargeted));
                startActivityForResult(i, 100);
            }

            @Override
            public void onError(String message) {
                btnStartPractice.setEnabled(true);
                if (phonemeProgress != null) phonemeProgress.setVisibility(View.GONE);
                // Fallback to random words on API failure
                launchDrillWithRandomWords(finalDifficulty);
            }
        });
    }

    /** Fallback: use random words when API is unavailable. */
    private void launchDrillWithRandomWords(String difficulty) {
        String words = DrillWordProvider.getRandomWordsAsString(difficulty, 5, ", ");
        AssignedDrill drill = new AssignedDrill();
        drill.setTitle("Personalized");
        drill.setTargetWords(words);
        drill.setDifficulty(difficulty);
        drill.setAssignedDrillId("");
        drill.setTherapistId("");
        drill.setTherapistName("");

        Intent i = new Intent(this, RecordDrillActivity.class);
        i.putExtra("drill", drill);
        i.putExtra("patient_id", patientId);
        i.putExtra("is_personalized", true);
        i.putExtra("ab_group", abGroup);
        startActivityForResult(i, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Refresh phoneme profile after drill completion
        if (resultCode == RESULT_OK) {
            PhonemeProfileHelper.analyzePatientPhonemes(patientId, new PhonemeProfileHelper.PhonemeProfileCallback() {
                @Override
                public void onSuccess(List<PhonemeProfileHelper.WeakPhoneme> weakPhonemes) {
                    loadPhonemeProfile(); // refresh UI
                }
                @Override
                public void onError(String message) {
                    // Non-critical, profile will update next time
                }
            });
        }
    }
}
