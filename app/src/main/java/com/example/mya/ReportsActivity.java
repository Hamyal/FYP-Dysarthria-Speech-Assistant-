package com.example.mya;

import android.os.Bundle;
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
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Patient: daily, weekly, monthly reports with sessions and accuracy. */
public class ReportsActivity extends AppCompatActivity {

    public static final String EXTRA_PATIENT_ID = "patient_id";
    private static final int TAB_DAILY = 0;
    private static final int TAB_WEEKLY = 1;
    private static final int TAB_MONTHLY = 2;

    private String patientId;
    private int currentTab = TAB_DAILY;
    private TextView periodLabel, sessionsInPeriod, accuracyInPeriod, emptyReportText;
    private RecyclerView reportSessionsList;
    private ReportSessionAdapter adapter;
    private List<PatientSessionRecord> allRecords = new ArrayList<>();
    private final List<PatientSessionRecord> filteredRecords = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reports);

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

        periodLabel = findViewById(R.id.periodLabel);
        sessionsInPeriod = findViewById(R.id.sessionsInPeriod);
        accuracyInPeriod = findViewById(R.id.accuracyInPeriod);
        reportSessionsList = findViewById(R.id.reportSessionsList);
        emptyReportText = findViewById(R.id.emptyReportText);

        adapter = new ReportSessionAdapter(filteredRecords);
        reportSessionsList.setLayoutManager(new LinearLayoutManager(this));
        reportSessionsList.setAdapter(adapter);

        TabLayout tabLayout = findViewById(R.id.tabLayout);
        tabLayout.addTab(tabLayout.newTab().setText(R.string.daily));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.weekly));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.monthly));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                applyFilter();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        loadSessions();
    }

    private void loadSessions() {
        FirebaseHelper.getPatientSessionRecords(patientId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                allRecords.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    PatientSessionRecord r = snapshotToRecord(child);
                    if (r != null) allRecords.add(r);
                }
                Collections.sort(allRecords, (a, b) -> Long.compare(b.getDateMs(), a.getDateMs()));
                applyFilter();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void applyFilter() {
        filteredRecords.clear();
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        long cutoff = 0;

        if (currentTab == TAB_DAILY) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cutoff = cal.getTimeInMillis();
            periodLabel.setText(getString(R.string.daily) + " - " + String.format(Locale.getDefault(), "%tF", cal));
        } else if (currentTab == TAB_WEEKLY) {
            cal.add(Calendar.DAY_OF_YEAR, -7);
            cutoff = cal.getTimeInMillis();
            periodLabel.setText(getString(R.string.weekly) + " (last 7 days)");
        } else {
            cal.add(Calendar.DAY_OF_YEAR, -30);
            cutoff = cal.getTimeInMillis();
            periodLabel.setText(getString(R.string.monthly) + " (last 30 days)");
        }

        for (PatientSessionRecord r : allRecords) {
            if (r.getDateMs() >= cutoff) filteredRecords.add(r);
        }

        adapter.notifyDataSetChanged();
        emptyReportText.setVisibility(filteredRecords.isEmpty() ? View.VISIBLE : View.GONE);

        double sumAcc = 0;
        int withScore = 0;
        for (PatientSessionRecord r : filteredRecords) {
            if (r.getDysarthriaScore() != null) {
                sumAcc += r.getDysarthriaScore() * 100;
                withScore++;
            }
        }
        sessionsInPeriod.setText(getString(R.string.total_sessions_count, filteredRecords.size()));
        accuracyInPeriod.setText(getString(R.string.average_accuracy,
                withScore > 0 ? String.format("%.1f", sumAcc / withScore) : "0"));
    }

    private PatientSessionRecord snapshotToRecord(DataSnapshot snap) {
        try {
            PatientSessionRecord r = new PatientSessionRecord();
            r.setSessionId(snap.getKey());
            r.setPatientId(getString(snap, "patientId"));
            r.setDrillTitle(getString(snap, "drillTitle"));
            Object dateMs = snap.child("dateMs").getValue();
            r.setDateMs(dateMs instanceof Number ? ((Number) dateMs).longValue() : 0L);
            r.setDate(getString(snap, "date"));
            Object score = snap.child("dysarthriaScore").getValue();
            r.setDysarthriaScore(score instanceof Number ? ((Number) score).doubleValue() : null);
            r.setDysarthriaPrediction(getString(snap, "dysarthriaPrediction"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(DataSnapshot snap, String key) {
        Object v = snap.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    static class ReportSessionAdapter extends RecyclerView.Adapter<ReportSessionAdapter.VH> {
        private final List<PatientSessionRecord> items;

        ReportSessionAdapter(List<PatientSessionRecord> items) {
            this.items = items;
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
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView date, drillTitle, result;

            VH(View itemView) {
                super(itemView);
                date = itemView.findViewById(R.id.sessionDate);
                drillTitle = itemView.findViewById(R.id.sessionDrillTitle);
                result = itemView.findViewById(R.id.sessionResult);
            }
        }
    }
}
