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

public class PatientPendingActivity extends AppCompatActivity {

    private TextInputEditText codeInput;
    private MaterialButton btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_patient_pending);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        codeInput = findViewById(R.id.code);
        btnSend = findViewById(R.id.btnSend);
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
     * Same flow as EnterTherapistCode: valid code → auto-link patient to therapist → PatientHome.
     */
    private void sendRequest() {
        String code = codeInput.getText() != null ? codeInput.getText().toString().trim().toUpperCase() : "";
        if (TextUtils.isEmpty(code)) {
            Toast.makeText(this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
            return;
        }
        if (FirebaseHelper.getCurrentUser() == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String patientId = FirebaseHelper.getCurrentUser().getUid();

        FirebaseHelper.getTherapistByCode(code, new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot == null || !snapshot.exists() || !snapshot.hasChildren()) {
                    Toast.makeText(PatientPendingActivity.this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
                    return;
                }
                DataSnapshot first = snapshot.getChildren().iterator().next();
                String therapistId = first.getKey();
                if (therapistId == null || therapistId.isEmpty()) {
                    Toast.makeText(PatientPendingActivity.this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
                    return;
                }
                linkPatientToTherapistAndGoHome(patientId, therapistId);
            }
            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(PatientPendingActivity.this, R.string.error_invalid_code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void linkPatientToTherapistAndGoHome(String patientId, String therapistId) {
        FirebaseHelper.getPatientByUID(patientId, new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot patientSnap) {
                if (patientSnap != null && patientSnap.exists()) {
                    FirebaseHelper.updatePatientAssignedTherapist(patientId, therapistId, "accepted");
                    FirebaseHelper.incrementTherapistAssignedPatients(therapistId);
                    openPatientHome();
                    return;
                }
                com.google.firebase.auth.FirebaseUser user = FirebaseHelper.getCurrentUser();
                if (user == null) {
                    Toast.makeText(PatientPendingActivity.this, "Please sign in again.", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(PatientPendingActivity.this, "Error. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openPatientHome() {
        Toast.makeText(this, R.string.request_sent, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, PatientHomeActivity.class));
        finish();
    }
}
