package com.example.mya;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AssignDrillActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";
    public static final String EXTRA_PATIENT_NAME = "patient_name";

    private String patientId;
    private String patientName;
    private TextInputEditText editTitle, editInstructions;
    private RadioGroup difficultyGroup;
    private MaterialButton btnAssign, btnPickTorgo;
    private TextView torgoTranscriptionLabel, torgoTranscriptionPreview;
    private String pendingTorgoId;
    private String pendingTorgoTranscription;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assign_drill);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        patientId = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_ID) : null;
        patientName = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_NAME) : "";
        if (patientId == null) {
            Toast.makeText(this, "Patient not selected.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView patientNameText = findViewById(R.id.patientNameText);
        patientNameText.setText(getString(R.string.personal_drills) + " → " + (patientName != null ? patientName : patientId));

        editTitle = findViewById(R.id.editTitle);
        editInstructions = findViewById(R.id.editInstructions);
        difficultyGroup = findViewById(R.id.difficultyGroup);
        btnAssign = findViewById(R.id.btnAssign);
        btnPickTorgo = findViewById(R.id.btnPickTorgo);
        torgoTranscriptionLabel = findViewById(R.id.torgoTranscriptionLabel);
        torgoTranscriptionPreview = findViewById(R.id.torgoTranscriptionPreview);

        btnAssign.setOnClickListener(v -> assignDrill());
        btnPickTorgo.setOnClickListener(v -> pickTorgoPhrase());
    }

    private String currentDifficulty() {
        int rid = difficultyGroup.getCheckedRadioButtonId();
        if (rid == R.id.radioEasy) return "easy";
        if (rid == R.id.radioHard) return "hard";
        return "medium";
    }

    private void pickTorgoPhrase() {
        btnPickTorgo.setEnabled(false);
        String diff = currentDifficulty();
        executor.execute(() -> {
            try {
                String url = BuildConfig.VOCALAID_API_URL + "/dataset/torgo/pick?difficulty=" + diff;
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder().url(url).get().build();
                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    JSONObject obj = new JSONObject(body);
                    if (obj.has("error")) {
                        mainHandler.post(() -> {
                            btnPickTorgo.setEnabled(true);
                            Toast.makeText(this, obj.optString("error", getString(R.string.torgo_pick_failed)), Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    String id = obj.optString("id", "");
                    String transcription = obj.optString("transcription", "");
                    mainHandler.post(() -> {
                        btnPickTorgo.setEnabled(true);
                        if (id.isEmpty() || transcription.isEmpty()) {
                            Toast.makeText(this, R.string.torgo_pick_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        pendingTorgoId = id;
                        pendingTorgoTranscription = transcription;
                        torgoTranscriptionLabel.setVisibility(View.VISIBLE);
                        torgoTranscriptionPreview.setVisibility(View.VISIBLE);
                        torgoTranscriptionPreview.setText(transcription);
                        if (editTitle.getText() == null || editTitle.getText().toString().trim().isEmpty()) {
                            editTitle.setText(getString(R.string.torgo_drill_title_format, id));
                        }
                        Toast.makeText(this, R.string.torgo_picked, Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    btnPickTorgo.setEnabled(true);
                    Toast.makeText(this, R.string.torgo_pick_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void assignDrill() {
        String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
        String instructions = editInstructions.getText() != null ? editInstructions.getText().toString().trim() : "";
        String difficulty = currentDifficulty();

        boolean fromTorgo = pendingTorgoTranscription != null && !pendingTorgoTranscription.isEmpty();
        if (!fromTorgo && title.isEmpty()) {
            Toast.makeText(this, "Enter a drill title or pick a TORGO phrase.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fromTorgo && title.isEmpty()) {
            title = getString(R.string.torgo_drill_title_format, pendingTorgoId != null ? pendingTorgoId : "");
        }

        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        AssignedDrill drill = new AssignedDrill();
        drill.setPatientId(patientId);
        drill.setTherapistId(user.getUid());
        drill.setTherapistName(user.getDisplayName() != null ? user.getDisplayName() : "");
        drill.setTitle(title);
        drill.setInstructions(instructions);
        drill.setDifficulty(difficulty);
        drill.setCompleted(false);
        drill.setAssignedAt(System.currentTimeMillis());

        if (fromTorgo) {
            drill.setTargetWords(pendingTorgoTranscription);
            drill.setTranscription(pendingTorgoTranscription);
            drill.setTorgoUtteranceId(pendingTorgoId != null ? pendingTorgoId : "");
        } else {
            drill.setTargetWords(DrillWordProvider.getRandomWordsAsString(difficulty, 5, ", "));
            drill.setTranscription("");
            drill.setTorgoUtteranceId("");
        }

        FirebaseHelper.saveAssignedDrill(drill, () -> {
            runOnUiThread(() -> {
                Toast.makeText(this, "Drill assigned.", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
