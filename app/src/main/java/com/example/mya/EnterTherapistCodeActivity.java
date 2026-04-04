package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

public class EnterTherapistCodeActivity extends AppCompatActivity {

    private TextInputEditText codeInput;
    private MaterialButton btnSend;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_enter_therapist_code);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        codeInput = findViewById(R.id.code);
        btnSend = findViewById(R.id.btnSend);
        progress = findViewById(R.id.progress);
        View btnLogout = findViewById(R.id.btnLogout);

        btnSend.setOnClickListener(v -> sendRequest());
        btnLogout.setOnClickListener(v -> {
            FirebaseHelper.getAuth().signOut();
            Intent i = new Intent(this, LoginActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
    }

    /**
     * Patient enters therapist code. If code is valid (exists in Therapist):
     * - Automatically link patient to that therapist (no request/accept flow).
     * - Create Patient record if missing (from Auth/users), then set assigned_therapist + status accepted.
     * - Go to PatientHomeActivity.
     */
    private void sendRequest() {
        String code = codeInput.getText() != null ? codeInput.getText().toString().trim().toUpperCase() : "";
        if (TextUtils.isEmpty(code)) {
            Toast.makeText(this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
            return;
        }

        com.google.firebase.auth.FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String patientId = user.getUid();

        setLoading(true);
        FirebaseHelper.getTherapistByCode(code, new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                setLoading(false);
                if (snapshot == null || !snapshot.exists() || !snapshot.hasChildren()) {
                    Toast.makeText(EnterTherapistCodeActivity.this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
                    return;
                }
                DataSnapshot first = snapshot.getChildren().iterator().next();
                String therapistId = first.getKey();
                if (therapistId == null || therapistId.isEmpty()) {
                    Toast.makeText(EnterTherapistCodeActivity.this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
                    return;
                }

                // Valid code: link patient to therapist and go to Patient Home (no request)
                linkPatientToTherapistAndGoHome(patientId, therapistId);
            }
            @Override
            public void onCancelled(DatabaseError error) {
                setLoading(false);
                String msg = (error != null && error.getMessage() != null) ? error.getMessage() : getString(R.string.error_invalid_code);
                Toast.makeText(EnterTherapistCodeActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void linkPatientToTherapistAndGoHome(String patientId, String therapistId) {
        FirebaseHelper.getPatientByUID(patientId, new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot patientSnap) {
                if (patientSnap != null && patientSnap.exists()) {
                    // Patient exists: update assigned therapist and status to accepted
                    FirebaseHelper.updatePatientAssignedTherapist(patientId, therapistId, "accepted");
                    FirebaseHelper.incrementTherapistAssignedPatients(therapistId);
                    openPatientHome();
                    return;
                }
                // Patient record missing: create from Auth/user info and link to therapist
                com.google.firebase.auth.FirebaseUser user = FirebaseHelper.getCurrentUser();
                if (user == null) {
                    Toast.makeText(EnterTherapistCodeActivity.this, "Please sign in again.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String displayName = user.getDisplayName();
                String email = user.getEmail() != null ? user.getEmail() : "";
                String name = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : "Patient";
                String[] parts = name.split("\\s+", 2);
                String firstName = parts.length > 0 ? parts[0] : "Patient";
                String lastName = parts.length > 1 ? parts[1] : "";
                Patient patient = new Patient(patientId, firstName, lastName, email, 0);
                patient.setAssigned_therapist(therapistId);
                patient.setStatus("accepted");
                FirebaseHelper.savePatient(patient, () -> {
                    FirebaseHelper.incrementTherapistAssignedPatients(therapistId);
                    openPatientHome();
                });
            }
            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(EnterTherapistCodeActivity.this, "Error. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openPatientHome() {
        Toast.makeText(this, R.string.request_sent, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, PatientHomeActivity.class));
        finish();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSend.setEnabled(!loading);
    }
}
