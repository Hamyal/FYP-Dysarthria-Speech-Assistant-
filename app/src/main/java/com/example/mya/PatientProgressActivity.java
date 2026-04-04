package com.example.mya;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Therapist: view a patient's session count and session records (from Firebase patient_sessions).
 */
public class PatientProgressActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";
    public static final String EXTRA_PATIENT_NAME = "patient_name";

    private String patientId;
    private String patientName;
    private TextView patientNameText, sessionsCountText, emptySessionsText;
    private RecyclerView sessionsList;
    private SessionRecordsAdapter adapter;
    private final List<PatientSessionRecord> records = new ArrayList<>();
    private MediaPlayer sessionRecordingPlayer;
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();
    private static final String TAG = "PatientProgress";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_progress);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        patientId = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_ID) : null;
        patientName = getIntent() != null ? getIntent().getStringExtra(EXTRA_PATIENT_NAME) : "";
        if (patientId == null) {
            finish();
            return;
        }

        patientNameText = findViewById(R.id.patientNameText);
        sessionsCountText = findViewById(R.id.sessionsCountText);
        sessionsList = findViewById(R.id.sessionsList);
        emptySessionsText = findViewById(R.id.emptySessionsText);

        patientNameText.setText(patientName != null && !patientName.isEmpty() ? patientName : patientId);

        adapter = new SessionRecordsAdapter(records, this);
        sessionsList.setLayoutManager(new LinearLayoutManager(this));
        sessionsList.setAdapter(adapter);

        findViewById(R.id.btnAiSummary).setOnClickListener(v -> {
            Intent i = new Intent(this, AiSummaryActivity.class);
            i.putExtra(AiSummaryActivity.EXTRA_PATIENT_ID, patientId);
            i.putExtra(AiSummaryActivity.EXTRA_PATIENT_NAME, patientName != null ? patientName : "");
            i.putExtra(AiSummaryActivity.EXTRA_ROLE, "therapist");
            startActivity(i);
        });

        setupDifficultyLevelSelector();
        loadSessionCount();
        loadSessionRecords();
        loadSpeechLevel();
    }

    private void setupDifficultyLevelSelector() {
        ChipGroup group = findViewById(R.id.difficultyChipGroup);
        group.setOnCheckedStateChangeListener((chipGroup, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String level = "easy";
            if (id == R.id.chipMedium) level = "medium";
            else if (id == R.id.chipHard) level = "hard";
            final String levelLabel = level.length() > 0 ? level.substring(0, 1).toUpperCase() + level.substring(1).toLowerCase() : level;
            FirebaseHelper.setPatientSpeechLevel(patientId, level, () -> runOnUiThread(() ->
                    Toast.makeText(this, getString(R.string.level_set, levelLabel), Toast.LENGTH_SHORT).show()));
        });
    }

    private void loadSpeechLevel() {
        FirebaseHelper.getPatientSpeechLevelRef(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String level = snapshot.getValue(String.class);
                if (level == null) level = "easy";
                ChipGroup group = findViewById(R.id.difficultyChipGroup);
                int checkId = R.id.chipEasy;
                if ("medium".equalsIgnoreCase(level)) checkId = R.id.chipMedium;
                else if ("hard".equalsIgnoreCase(level)) checkId = R.id.chipHard;
                group.check(checkId);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void loadSessionCount() {
        FirebaseHelper.getPatientSessionCountRef(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                long count = snapshot.getValue() instanceof Number ? ((Number) snapshot.getValue()).longValue() : 0;
                sessionsCountText.setText(getString(R.string.sessions_attended, (int) count));
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void loadSessionRecords() {
        FirebaseHelper.getPatientSessionRecords(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                records.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    PatientSessionRecord r = snapshotToRecord(child);
                    if (r != null) records.add(r);
                }
                // newest first
                java.util.Collections.reverse(records);
                adapter.notifyDataSetChanged();
                emptySessionsText.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                sessionsList.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private PatientSessionRecord snapshotToRecord(DataSnapshot snap) {
        try {
            PatientSessionRecord r = new PatientSessionRecord();
            r.setSessionId(snap.getKey());
            r.setPatientId(getString(snap, "patientId"));
            r.setTherapistId(getString(snap, "therapistId"));
            r.setAssignedDrillId(getString(snap, "assignedDrillId"));
            r.setDrillTitle(getString(snap, "drillTitle"));
            Object dateMs = snap.child("dateMs").getValue();
            r.setDateMs(dateMs instanceof Number ? ((Number) dateMs).longValue() : 0L);
            r.setDate(getString(snap, "date"));
            Object dur = snap.child("durationSeconds").getValue();
            r.setDurationSeconds(dur instanceof Number ? ((Number) dur).intValue() : 0);
            Object score = snap.child("dysarthriaScore").getValue();
            r.setDysarthriaScore(score instanceof Number ? ((Number) score).doubleValue() : null);
            r.setDysarthriaPrediction(getString(snap, "dysarthriaPrediction"));
            r.setNote(getString(snap, "note"));
            r.setRecordingUrl(getString(snap, "recordingUrl"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(DataSnapshot snap, String key) {
        Object v = snap.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    /** Play patient recording from Firebase Storage URL. Downloads first so playback works with Firebase URLs. */
    void playRecordingUrl(String recordingUrl) {
        if (recordingUrl == null || recordingUrl.isEmpty()) {
            Toast.makeText(this, R.string.no_recording_available, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isDestroyed()) return;
        stopSessionRecording();
        Toast.makeText(this, R.string.loading_recording, Toast.LENGTH_SHORT).show();
        playbackExecutor.execute(() -> {
            File tempFile = null;
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(10, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
                Request request = new Request.Builder()
                        .url(recordingUrl)
                        .header("User-Agent", "VocalAid-Android")
                        .header("Accept", "*/*")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (isDestroyed()) return;
                    if (!response.isSuccessful() || response.body() == null) {
                        runOnUiThread(() -> {
                            if (!isDestroyed()) Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    tempFile = new File(getCacheDir(), "play_recording_" + System.currentTimeMillis() + ".wav");
                    try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    if (tempFile.length() < 100) {
                        runOnUiThread(() -> {
                            if (!isDestroyed()) Toast.makeText(this, R.string.no_recording_available, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    final File toPlay = tempFile;
                    runOnUiThread(() -> {
                        if (!isDestroyed()) playFromFile(toPlay);
                        else if (toPlay.exists()) toPlay.delete();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Download recording failed", e);
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                runOnUiThread(() -> {
                    if (!isDestroyed()) Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void playFromFile(File file) {
        if (isDestroyed()) return;
        stopSessionRecording();
        try {
            sessionRecordingPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                sessionRecordingPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } else {
                sessionRecordingPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            sessionRecordingPlayer.setDataSource(file.getAbsolutePath());
            sessionRecordingPlayer.setOnCompletionListener(mp -> {
                if (file.exists()) file.delete();
                stopSessionRecording();
            });
            sessionRecordingPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                if (file.exists()) file.delete();
                stopSessionRecording();
                if (!isDestroyed()) Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                return true;
            });
            sessionRecordingPlayer.prepareAsync();
            sessionRecordingPlayer.setOnPreparedListener(mp -> {
                if (isDestroyed()) return;
                try {
                    mp.start();
                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer start failed", e);
                    if (file.exists()) file.delete();
                    stopSessionRecording();
                    Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Play from file failed", e);
            if (file.exists()) file.delete();
            if (!isDestroyed()) Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSessionRecording() {
        if (sessionRecordingPlayer != null) {
            try {
                if (sessionRecordingPlayer.isPlaying()) sessionRecordingPlayer.stop();
                sessionRecordingPlayer.release();
            } catch (Exception ignored) {}
            sessionRecordingPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopSessionRecording();
        playbackExecutor.shutdown();
        super.onDestroy();
    }

    static class SessionRecordsAdapter extends RecyclerView.Adapter<SessionRecordsAdapter.VH> {
        private final List<PatientSessionRecord> items;
        private final PatientProgressActivity activity;

        SessionRecordsAdapter(List<PatientSessionRecord> items, PatientProgressActivity activity) {
            this.items = items;
            this.activity = activity;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session_record, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            PatientSessionRecord r = items.get(position);
            holder.date.setText(r.getDate() != null ? r.getDate() : "");
            holder.drillTitle.setText(r.getDrillTitle() != null && !r.getDrillTitle().isEmpty() ? r.getDrillTitle() : "Drill");
            holder.result.setText(PatientSessionRecord.formatResultForDisplay(r.getDysarthriaPrediction(), r.getDysarthriaScore()));
            boolean hasRecording = r.getRecordingUrl() != null && !r.getRecordingUrl().isEmpty();
            holder.btnListen.setVisibility(hasRecording ? View.VISIBLE : View.GONE);
            final String url = r.getRecordingUrl();
            holder.btnListen.setOnClickListener(hasRecording ? v -> {
                v.setEnabled(false);
                v.postDelayed(() -> v.setEnabled(true), 500);
                activity.playRecordingUrl(url);
            } : null);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView date, drillTitle, result;
            MaterialButton btnListen;

            VH(View itemView) {
                super(itemView);
                date = itemView.findViewById(R.id.sessionDate);
                drillTitle = itemView.findViewById(R.id.sessionDrillTitle);
                result = itemView.findViewById(R.id.sessionResult);
                btnListen = itemView.findViewById(R.id.btnListenRecording);
            }
        }
    }
}
