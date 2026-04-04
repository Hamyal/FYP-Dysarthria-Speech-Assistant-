package com.example.mya;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import androidx.drawerlayout.widget.DrawerLayout;
import android.util.Log;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TherapistHomeActivity extends AppCompatActivity implements RequestAdapter.OnRequestActionListener, PatientsAdapter.OnPatientMessageListener {

    private static final String TAG = "TherapistHome";

    /** Intent extra: therapist code passed after registration so it shows immediately. */
    public static final String EXTRA_THERAPIST_CODE = "com.example.mya.EXTRA_THERAPIST_CODE";

    private TextView therapistCodeView, therapistNameText, therapistEmailText, emptyText;
    private ImageView therapistAvatar;
    private final ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();
    private RecyclerView requestsList, patientsList;
    private RequestAdapter adapter;
    private PatientsAdapter patientsAdapter;
    private ActionBarDrawerToggle drawerToggle;
    private DatabaseReference therapistPatientIndexRef;
    private ValueEventListener therapistPatientIndexListener;
    private final ActivityResultLauncher<Intent> editProfileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) loadTherapistProfile();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_therapist_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        findViewById(R.id.btn_toolbar_logout).setOnClickListener(v -> doLogout());

        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.open_drawer, R.string.close_drawer);
        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        NavigationView navView = findViewById(R.id.nav_view);
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_overview) {
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }
            if (id == R.id.nav_edit_profile) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "therapist");
                editProfileLauncher.launch(i);
                return true;
            }
            if (id == R.id.nav_patients) {
                drawerLayout.closeDrawer(GravityCompat.START);
                if (patientsList != null) patientsList.requestFocus();
                return true;
            }
            if (id == R.id.nav_talk_with_agent) {
                drawerLayout.closeDrawer(GravityCompat.START);
                startActivity(new Intent(this, AgentChatActivity.class));
                return true;
            }
            if (id == R.id.nav_logout) {
                drawerLayout.closeDrawer(GravityCompat.START);
                doLogout();
                return true;
            }
            return false;
        });

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_profile) {
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "therapist");
                editProfileLauncher.launch(i);
                return true;
            }
            if (item.getItemId() == R.id.action_patients) {
                if (patientsList != null) patientsList.requestFocus();
                return true;
            }
            if (item.getItemId() == R.id.action_logout) {
                doLogout();
                return true;
            }
            return false;
        });

        therapistCodeView = findViewById(R.id.therapistCode);
        therapistNameText = findViewById(R.id.therapistNameText);
        therapistEmailText = findViewById(R.id.therapistEmailText);
        therapistAvatar = findViewById(R.id.therapistAvatar);
        String codeFromIntent = getIntent() != null ? getIntent().getStringExtra(EXTRA_THERAPIST_CODE) : null;
        if (codeFromIntent != null && !codeFromIntent.trim().isEmpty()) {
            therapistCodeView.setText(codeFromIntent.trim().toUpperCase());
        } else {
            therapistCodeView.setText("…"); // placeholder until loaded from Firebase
        }
        requestsList = findViewById(R.id.requestsList);
        emptyText = findViewById(R.id.emptyText);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_bottom_home);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_bottom_home) return true;
            if (id == R.id.nav_bottom_patients) {
                if (patientsList != null) patientsList.requestFocus();
                return true;
            }
            if (id == R.id.nav_bottom_profile) {
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "therapist");
                editProfileLauncher.launch(i);
                return true;
            }
            return false;
        });

        adapter = new RequestAdapter();
        adapter.setListener(this);
        requestsList.setLayoutManager(new LinearLayoutManager(this));
        requestsList.setAdapter(adapter);

        patientsList = findViewById(R.id.patientsList);
        patientsAdapter = new PatientsAdapter();
        patientsAdapter.setListener(this);
        patientsList.setLayoutManager(new LinearLayoutManager(this));
        patientsList.setAdapter(patientsAdapter);

        loadTherapistProfile();
        loadTherapistCode();
        loadRequests();
        loadAssignedPatients();
    }

    private void loadTherapistProfile() {
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) return;
        FirebaseHelper.getTherapistRef(user.getUid()).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String name = snapshot.child("Name").getValue(String.class);
                    String last = snapshot.child("last_name").getValue(String.class);
                    String n = (name != null ? name : "") + " " + (last != null ? last : "");
                    therapistNameText.setText(n.trim().isEmpty() ? "Therapist" : n.trim());
                    String em = snapshot.child("email").getValue(String.class);
                    therapistEmailText.setText(em != null ? em : "");
                    therapistEmailText.setVisibility(em != null && !em.isEmpty() ? View.VISIBLE : View.GONE);
                    String photoUrl = snapshot.child("photoUrl").getValue(String.class);
                    if (photoUrl != null && !photoUrl.isEmpty()) {
                        loadProfileImage(photoUrl);
                    } else if (therapistAvatar != null) {
                        therapistAvatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        therapistAvatar.setImageResource(R.drawable.ic_person_avatar);
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void loadProfileImage(String url) {
        if (therapistAvatar == null) return;
        imageLoadExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setDoInput(true);
                conn.connect();
                InputStream is = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                if (bitmap != null) {
                    runOnUiThread(() -> {
                        therapistAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        therapistAvatar.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    therapistAvatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    therapistAvatar.setImageResource(R.drawable.ic_person_avatar);
                });
            }
        });
    }

    private void loadTherapistCode() {
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        FirebaseHelper.getTherapistRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String code = snapshot.child("code").getValue(String.class);
                    if (code != null && !code.trim().isEmpty()) {
                        // Always use the code from Firebase – never change it on login
                        runOnUiThread(() -> therapistCodeView.setText(code.trim().toUpperCase()));
                    } else {
                        // Code missing – set only if still missing (transaction never overwrites existing)
                        String newCode = FirebaseHelper.generateTherapistCode();
                        runOnUiThread(() -> therapistCodeView.setText(newCode)); // show immediately
                        FirebaseHelper.setTherapistCodeIfMissing(uid, newCode, finalCode -> {
                            runOnUiThread(() -> {
                                if (finalCode != null) therapistCodeView.setText(finalCode);
                            });
                        });
                    }
                } else {
                    // No Therapist record (e.g. write failed on register) – create one with a code
                    createTherapistWithCode(uid, user.getDisplayName(), user.getEmail());
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                runOnUiThread(() -> therapistCodeView.setText("—")); // show something on error
            }
        });
    }

    /** Create Therapist record with a new code when missing (so code always shows). */
    private void createTherapistWithCode(String uid, String displayName, String email) {
        String code = FirebaseHelper.generateTherapistCode();
        runOnUiThread(() -> therapistCodeView.setText(code)); // show immediately
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : "Therapist";
        String[] parts = name.trim().split("\\s+", 2);
        String firstName = parts.length > 0 ? parts[0] : "Therapist";
        String lastName = parts.length > 1 ? parts[1] : "";
        Therapist therapist = new Therapist(uid, code, firstName, lastName, email != null ? email : "");
        FirebaseHelper.saveTherapist(therapist);
    }

    private void loadAssignedPatients() {
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) return;
        final String therapistUid = user.getUid();
        if (therapistPatientIndexRef != null && therapistPatientIndexListener != null) {
            therapistPatientIndexRef.removeEventListener(therapistPatientIndexListener);
        }
        therapistPatientIndexRef = FirebaseHelper.getTherapistPatientIndexRef(therapistUid);
        therapistPatientIndexListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    patientsAdapter.setItems(new ArrayList<>());
                    return;
                }
                List<String> ids = new ArrayList<>();
                for (DataSnapshot ch : snapshot.getChildren()) {
                    if (ch.getKey() != null) ids.add(ch.getKey());
                }
                if (ids.isEmpty()) {
                    patientsAdapter.setItems(new ArrayList<>());
                    return;
                }
                final int n = ids.size();
                final Patient[] buffer = new Patient[n];
                final AtomicInteger completed = new AtomicInteger(0);
                for (int i = 0; i < n; i++) {
                    final int index = i;
                    String pid = ids.get(i);
                    FirebaseHelper.getPatientRef(pid).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot patientSnap) {
                            Patient p = snapshotToPatient(patientSnap);
                            if (p != null && therapistUid.equals(p.getAssigned_therapist()) && isPatientAccepted(p.getStatus())) {
                                buffer[index] = p;
                            } else {
                                buffer[index] = null;
                            }
                            finishPatientBatchIfDone(buffer, completed, n);
                        }
                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            Log.e(TAG, "Patient read failed: " + pid, error.toException());
                            buffer[index] = null;
                            finishPatientBatchIfDone(buffer, completed, n);
                        }
                    });
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "therapist_patients index listener cancelled", error.toException());
                patientsAdapter.setItems(new ArrayList<>());
            }
        };
        therapistPatientIndexRef.addValueEventListener(therapistPatientIndexListener);
    }

    private static boolean isPatientAccepted(String status) {
        return status != null && "accepted".equalsIgnoreCase(status.trim());
    }

    private void finishPatientBatchIfDone(Patient[] buffer, AtomicInteger completed, int expected) {
        if (completed.incrementAndGet() != expected) return;
        List<Patient> list = new ArrayList<>();
        for (Patient pat : buffer) {
            if (pat != null) list.add(pat);
        }
        runOnUiThread(() -> patientsAdapter.setItems(list));
    }

    private Patient snapshotToPatient(DataSnapshot snap) {
        try {
            Patient p = new Patient();
            p.setPatient_id(snap.getKey());
            p.setName(snap.child("Name").getValue(String.class));
            p.setLast_name(snap.child("last_name").getValue(String.class));
            p.setEmail(snap.child("email").getValue(String.class));
            Object age = snap.child("Age").getValue();
            p.setAge(age instanceof Number ? ((Number) age).intValue() : 0);
            Object at = snap.child("assigned_therapist").getValue();
            p.setAssigned_therapist(at != null ? String.valueOf(at).trim() : null);
            p.setStatus(snap.child("status").getValue(String.class));
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadRequests() {
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) return;
        FirebaseHelper.getPatientRequestsByTherapist(user.getUid()).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<PatientRequest> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    PatientRequest r = snapshotToRequest(child);
                    if (r != null && "pending".equals(r.getStatus())) {
                        list.add(r);
                    }
                }
                adapter.setItems(list);
                emptyText.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private PatientRequest snapshotToRequest(DataSnapshot snap) {
        try {
            PatientRequest r = new PatientRequest();
            r.setRequestId(snap.child("requestId").getValue(String.class));
            r.setPatientId(snap.child("patientId").getValue(String.class));
            r.setPatientName(snap.child("patientName").getValue(String.class));
            r.setPatientEmail(snap.child("patientEmail").getValue(String.class));
            Object age = snap.child("patientAge").getValue();
            r.setPatientAge(age instanceof Number ? ((Number) age).intValue() : 0);
            r.setTherapistId(snap.child("therapistId").getValue(String.class));
            r.setTherapistName(snap.child("therapistName").getValue(String.class));
            r.setStatus(snap.child("status").getValue(String.class));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onAccept(PatientRequest request) {
        FirebaseHelper.updateRequestStatus(request.getRequestId(), "accepted");
        FirebaseHelper.updatePatientAssignedTherapist(request.getPatientId(), request.getTherapistId(), "accepted");
        FirebaseHelper.incrementTherapistAssignedPatients(request.getTherapistId());
        Toast.makeText(this, "Request accepted.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onReject(PatientRequest request) {
        FirebaseHelper.updateRequestStatus(request.getRequestId(), "rejected");
        Toast.makeText(this, "Request rejected.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMessage(Patient patient) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_OTHER_UID, patient.getPatient_id());
        i.putExtra(ChatActivity.EXTRA_OTHER_NAME, patient.getFullName());
        startActivity(i);
    }

    @Override
    public void onAssignDrill(Patient patient) {
        Intent i = new Intent(this, AssignDrillActivity.class);
        i.putExtra(AssignDrillActivity.EXTRA_PATIENT_ID, patient.getPatient_id());
        i.putExtra(AssignDrillActivity.EXTRA_PATIENT_NAME, patient.getFullName());
        startActivity(i);
    }

    @Override
    public void onViewProgress(Patient patient) {
        Intent i = new Intent(this, PatientProgressActivity.class);
        i.putExtra(PatientProgressActivity.EXTRA_PATIENT_ID, patient.getPatient_id());
        i.putExtra(PatientProgressActivity.EXTRA_PATIENT_NAME, patient.getFullName());
        startActivity(i);
    }

    @Override
    protected void onDestroy() {
        if (therapistPatientIndexRef != null && therapistPatientIndexListener != null) {
            therapistPatientIndexRef.removeEventListener(therapistPatientIndexListener);
        }
        imageLoadExecutor.shutdown();
        super.onDestroy();
    }

    private void doLogout() {
        FirebaseHelper.getAuth().signOut();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
