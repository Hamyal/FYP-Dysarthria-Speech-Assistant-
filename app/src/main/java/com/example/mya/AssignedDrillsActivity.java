package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Patient: view drills assigned by therapist, record & analyze, mark complete. */
public class AssignedDrillsActivity extends AppCompatActivity {

    private static final String TAG = "AssignedDrillsActivity";
    public static final String EXTRA_PATIENT_ID = "patient_id";

    private String patientId;
    private RecyclerView drillsList;
    private TextView emptyText;
    private AssignedDrillsAdapter adapter;
    private final List<AssignedDrill> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assigned_drills);

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

        drillsList = findViewById(R.id.drillsList);
        emptyText = findViewById(R.id.emptyText);

        adapter = new AssignedDrillsAdapter(items, this::onRecordAnalyze, this::onMarkComplete);
        drillsList.setLayoutManager(new LinearLayoutManager(this));
        drillsList.setAdapter(adapter);

        loadAssignedDrills();
    }

    private void loadAssignedDrills() {
        FirebaseHelper.getAssignedDrillsByPatient(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                items.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    AssignedDrill d = snapshotToAssignedDrill(child);
                    if (d != null) items.add(d);
                }
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                drillsList.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to load drills: " + error.getMessage(), error.toException());
                String msg = getString(R.string.failed_to_load_drills);
                if (error.getMessage() != null && error.getMessage().contains("index"))
                    msg = getString(R.string.failed_to_load_drills_index);
                Toast.makeText(AssignedDrillsActivity.this, msg, Toast.LENGTH_LONG).show();
                emptyText.setVisibility(View.VISIBLE);
                emptyText.setText(msg);
                drillsList.setVisibility(View.GONE);
            }
        });
    }

    private AssignedDrill snapshotToAssignedDrill(DataSnapshot snap) {
        try {
            AssignedDrill d = new AssignedDrill();
            d.setAssignedDrillId(snap.getKey());
            d.setPatientId(getString(snap, "patientId"));
            d.setTherapistId(getString(snap, "therapistId"));
            d.setTherapistName(getString(snap, "therapistName"));
            d.setTitle(getString(snap, "title"));
            d.setInstructions(getString(snap, "instructions"));
            d.setDifficulty(getString(snap, "difficulty"));
            Object completed = snap.child("completed").getValue();
            d.setCompleted(completed instanceof Boolean && (Boolean) completed);
            Object completedAt = snap.child("completedAt").getValue();
            d.setCompletedAt(completedAt instanceof Number ? ((Number) completedAt).longValue() : 0L);
            Object score = snap.child("dysarthriaScore").getValue();
            d.setDysarthriaScore(score instanceof Number ? ((Number) score).doubleValue() : null);
            d.setDysarthriaPrediction(getString(snap, "dysarthriaPrediction"));
            Object assignedAt = snap.child("assignedAt").getValue();
            d.setAssignedAt(assignedAt instanceof Number ? ((Number) assignedAt).longValue() : 0L);
            d.setTargetWords(getString(snap, "targetWords"));
            d.setTranscription(getString(snap, "transcription"));
            d.setTorgoUtteranceId(getString(snap, "torgoUtteranceId"));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(DataSnapshot snap, String key) {
        Object v = snap.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    private void onRecordAnalyze(AssignedDrill drill) {
        Intent i = new Intent(this, RecordDrillActivity.class);
        i.putExtra("drill", drill);
        i.putExtra("patient_id", patientId);
        startActivityForResult(i, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK) loadAssignedDrills();
    }

    private void onMarkComplete(AssignedDrill drill) {
        FirebaseHelper.updateAssignedDrillCompleted(drill.getAssignedDrillId(), true, drill.getDysarthriaScore(), drill.getDysarthriaPrediction());
        PatientSessionRecord record = new PatientSessionRecord();
        record.setTherapistId(drill.getTherapistId());
        record.setAssignedDrillId(drill.getAssignedDrillId());
        record.setDrillTitle(drill.getTitle());
        record.setDateMs(System.currentTimeMillis());
        record.setDate(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));
        record.setDurationSeconds(0);
        record.setDysarthriaScore(drill.getDysarthriaScore());
        record.setDysarthriaPrediction(drill.getDysarthriaPrediction());
        FirebaseHelper.addPatientSessionRecord(patientId, record, () -> runOnUiThread(() -> {
            loadAssignedDrills();
            Toast.makeText(this, "Drill marked complete.", Toast.LENGTH_SHORT).show();
        }));
    }
}
