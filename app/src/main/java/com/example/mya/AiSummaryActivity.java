package com.example.mya;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI-powered patient history summary and suggestions (Groq).
 * If GROQ_API_KEY is set in BuildConfig, calls Groq directly from Java; otherwise uses VocalAid API.
 */
public class AiSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";
    public static final String EXTRA_PATIENT_NAME = "patient_name";
    public static final String EXTRA_ROLE = "role"; // "patient" or "therapist"

    private String patientId;
    private String patientName;
    private String role = "patient";

    private TextInputEditText questionInput;
    private View btnGetSummary;
    private ProgressBar progressBar;
    private TextView errorText;
    private TextView summaryText;
    private TextView subtitleText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_summary);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        patientId = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_ID) : null;
        patientName = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_NAME) : "";
        String roleExtra = getIntent() != null ? getIntent().getStringExtra(EXTRA_ROLE) : null;
        if ("therapist".equalsIgnoreCase(roleExtra)) role = "therapist";

        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (patientId == null && user != null) {
            patientId = user.getUid();
            role = "patient";
        }
        if (patientId == null) {
            Toast.makeText(this, "Not logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        com.google.android.material.textfield.TextInputLayout questionLayout = findViewById(R.id.questionLayout);
        questionInput = questionLayout != null && questionLayout.getEditText() != null
                ? (TextInputEditText) questionLayout.getEditText()
                : findViewById(R.id.questionInput);

        btnGetSummary = findViewById(R.id.btnGetSummary);
        progressBar = findViewById(R.id.progressBar);
        errorText = findViewById(R.id.errorText);
        summaryText = findViewById(R.id.summaryText);
        subtitleText = findViewById(R.id.subtitleText);

        if (subtitleText != null) {
            if ("therapist".equals(role))
                subtitleText.setText(patientName != null && !patientName.isEmpty() ? patientName : patientId);
            else
                subtitleText.setText(getString(R.string.ai_suggestions));
        }

        if (btnGetSummary != null) {
            btnGetSummary.setOnClickListener(v -> loadSummary());
        }

        summaryText.setText("");
        errorText.setVisibility(View.GONE);
    }

    private void loadSummary() {
        errorText.setVisibility(View.GONE);
        summaryText.setText("");
        btnGetSummary.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);

        final int total = 4;
        AtomicInteger done = new AtomicInteger(0);
        final Object[] patientBox = new Object[1];
        final Object[] sessionsBox = new Object[1];
        final Object[] drillsBox = new Object[1];
        final Object[] reportsBox = new Object[1];

        ValueEventListener onDone = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) { /* unused */ }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* unused */ }
        };

        Runnable maybeSend = () -> {
            if (done.incrementAndGet() != total) return;
            try {
                JSONObject patientJson = patientBox[0] != null ? (JSONObject) patientBox[0] : new JSONObject();
                JSONArray sessionsJson = sessionsBox[0] != null ? (JSONArray) sessionsBox[0] : new JSONArray();
                JSONArray drillsJson = drillsBox[0] != null ? (JSONArray) drillsBox[0] : new JSONArray();
                JSONArray reportsJson = reportsBox[0] != null ? (JSONArray) reportsBox[0] : new JSONArray();

                String question = (questionInput != null && questionInput.getText() != null)
                        ? questionInput.getText().toString().trim() : "";

                String groqKey = BuildConfig.GROQ_API_KEY != null ? BuildConfig.GROQ_API_KEY.trim() : "";
                if (!groqKey.isEmpty()) {
                    // Call Groq directly from Java
                    String systemPrompt = "You are an expert speech-language pathology assistant. Be concise and practical.";
                    String userPrompt = buildGroqPrompt(patientId, role, question, patientJson, sessionsJson, drillsJson, reportsJson);
                    GroqHelper.chat(groqKey, null, systemPrompt, userPrompt, new GroqHelper.Callback() {
                        @Override
                        public void onSuccess(String content) {
                            progressBar.setVisibility(View.GONE);
                            btnGetSummary.setEnabled(true);
                            summaryText.setText(content);
                            errorText.setVisibility(View.GONE);
                        }
                        @Override
                        public void onError(String message) {
                            progressBar.setVisibility(View.GONE);
                            btnGetSummary.setEnabled(true);
                            errorText.setText(getString(R.string.summary_error) + " " + message);
                            errorText.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }

                // Fallback: call VocalAid API (no key in app)
                JSONObject payload = new JSONObject();
                payload.put("patientId", patientId);
                payload.put("role", role);
                payload.put("question", question);
                payload.put("patient", patientJson);
                payload.put("sessions", sessionsJson);
                payload.put("drills", drillsJson);
                payload.put("reports", reportsJson);

                String url = BuildConfig.VOCALAID_API_URL + "/ai/summary";
                executor.execute(() -> {
                    try {
                        OkHttpClient client = new OkHttpClient.Builder()
                                .connectTimeout(25, TimeUnit.SECONDS)
                                .readTimeout(60, TimeUnit.SECONDS)
                                .build();
                        RequestBody body = RequestBody.create(
                                payload.toString(),
                                MediaType.parse("application/json; charset=utf-8"));
                        Request request = new Request.Builder().url(url).post(body).build();
                        Response response = client.newCall(request).execute();
                        final String bodyStr = response.body() != null ? response.body().string() : "";
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            btnGetSummary.setEnabled(true);
                            try {
                                if (!response.isSuccessful()) {
                                    errorText.setText(R.string.summary_error);
                                    errorText.setVisibility(View.VISIBLE);
                                    return;
                                }
                                JSONObject json = new JSONObject(bodyStr);
                                String summary = json.optString("summary", "");
                                if (summary.isEmpty()) summary = json.optString("error", getString(R.string.summary_error));
                                summaryText.setText(summary);
                                errorText.setVisibility(View.GONE);
                            } catch (Exception e) {
                                errorText.setText(getString(R.string.summary_error) + " " + e.getMessage());
                                errorText.setVisibility(View.VISIBLE);
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            btnGetSummary.setEnabled(true);
                            errorText.setText(getString(R.string.summary_error) + " " + (e.getMessage() != null ? e.getMessage() : ""));
                            errorText.setVisibility(View.VISIBLE);
                        });
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnGetSummary.setEnabled(true);
                    errorText.setText(e.getMessage() != null ? e.getMessage() : getString(R.string.summary_error));
                    errorText.setVisibility(View.VISIBLE);
                });
            }
        };

        FirebaseHelper.getPatientRef(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    JSONObject o = new JSONObject();
                    if (snapshot.exists()) {
                        for (DataSnapshot c : snapshot.getChildren()) {
                            Object v = c.getValue();
                            if (v instanceof Number) o.put(c.getKey(), ((Number) v).doubleValue());
                            else if (v != null) o.put(c.getKey(), v.toString());
                        }
                    }
                    patientBox[0] = o;
                } catch (Exception ignored) {}
                maybeSend.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { maybeSend.run(); }
        });

        FirebaseHelper.getPatientSessionRecords(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                JSONArray arr = new JSONArray();
                try {
                    for (DataSnapshot c : snapshot.getChildren()) {
                        JSONObject o = new JSONObject();
                        for (DataSnapshot cc : c.getChildren()) {
                            Object v = cc.getValue();
                            if (v instanceof Number) o.put(cc.getKey(), ((Number) v).doubleValue());
                            else if (v != null) o.put(cc.getKey(), v.toString());
                        }
                        arr.put(o);
                    }
                    sessionsBox[0] = arr;
                } catch (Exception ignored) {}
                maybeSend.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { maybeSend.run(); }
        });

        FirebaseHelper.getAssignedDrillsByPatient(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                JSONArray arr = new JSONArray();
                try {
                    for (DataSnapshot c : snapshot.getChildren()) {
                        JSONObject o = new JSONObject();
                        for (DataSnapshot cc : c.getChildren()) {
                            Object v = cc.getValue();
                            if (v instanceof Boolean) o.put(cc.getKey(), (Boolean) v);
                            else if (v instanceof Number) o.put(cc.getKey(), ((Number) v).doubleValue());
                            else if (v != null) o.put(cc.getKey(), v.toString());
                        }
                        arr.put(o);
                    }
                    drillsBox[0] = arr;
                } catch (Exception ignored) {}
                maybeSend.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { maybeSend.run(); }
        });

        FirebaseHelper.getProgressReportsByPatient(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                JSONArray arr = new JSONArray();
                try {
                    for (DataSnapshot c : snapshot.getChildren()) {
                        JSONObject o = new JSONObject();
                        for (DataSnapshot cc : c.getChildren()) {
                            Object v = cc.getValue();
                            if (v instanceof Number) o.put(cc.getKey(), ((Number) v).doubleValue());
                            else if (v != null) o.put(cc.getKey(), v.toString());
                        }
                        arr.put(o);
                    }
                    reportsBox[0] = arr;
                } catch (Exception ignored) {}
                maybeSend.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) { maybeSend.run(); }
        });
    }

    /** Build the same prompt as the VocalAid API so Groq returns summary + suggestions. */
    private String buildGroqPrompt(String patientId, String role, String question,
                                   JSONObject patient, JSONArray sessions, JSONArray drills, JSONArray reports) {
        String roleInstruction = "patient".equals(role)
                ? "Speak directly to the patient in simple, supportive language. Focus on encouragement and clear next steps."
                : "Speak to the therapist as a professional summary. Include clinical observations, trends, and actionable suggestions.";
        String questionLine = (question != null && !question.isEmpty())
                ? "\nThe user asked: \"" + question + "\"."
                : "\nProvide a useful summary and 3–5 personalized suggestions.";

        String sessionsStr = sessions != null ? sessions.toString() : "[]";
        String drillsStr = drills != null ? drills.toString() : "[]";
        String reportsStr = reports != null ? reports.toString() : "[]";
        String patientStr = patient != null ? patient.toString() : "{}";

        return "You are a speech-language pathology assistant for dysarthria therapy (VocalAid app).\n\n"
                + roleInstruction + "\n\n"
                + "Patient identifier: " + (patientId != null ? patientId : "") + "\n"
                + "Patient info (name, age, etc.): " + patientStr + "\n\n"
                + "Recent sessions (date, drill, dysarthria score/prediction): " + sessionsStr + "\n\n"
                + "Assigned drills (title, difficulty, completed, scores): " + drillsStr + "\n\n"
                + "Progress reports / therapist notes (if any): " + reportsStr + "\n"
                + questionLine + "\n\n"
                + "Respond with:\n"
                + "1. A short history summary (sessions attended, trends in speech clarity/dysarthria).\n"
                + "2. Main strengths and current challenges.\n"
                + "3. 3–5 concrete suggestions for practice or for the therapist.\n"
                + "Keep the reply concise and readable (use short paragraphs or bullet points). Do not use markdown code blocks.";
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
