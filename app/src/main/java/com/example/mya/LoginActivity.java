package com.example.mya;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText emailInput, passwordInput;
    private MaterialButton btnLogin;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        emailInput = findViewById(R.id.email);
        passwordInput = findViewById(R.id.password);
        btnLogin = findViewById(R.id.btnLogin);
        progress = findViewById(R.id.progress);
        View btnRegister = findViewById(R.id.btnRegister);

        btnLogin.setOnClickListener(v -> attemptLogin());
        btnRegister.setOnClickListener(v -> {
            startActivity(new Intent(this, RegisterChoiceActivity.class));
            finish();
        });

        // If already logged in, redirect by user type
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user != null) {
            redirectAfterLogin(user.getUid());
        }
    }

    private void attemptLogin() {
        String email = emailInput.getText() != null ? emailInput.getText().toString().trim() : "";
        String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, R.string.error_invalid_email, Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, R.string.error_password_short, Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        FirebaseHelper.getAuth().signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    if (authResult.getUser() != null) {
                        redirectAfterLogin(authResult.getUser().getUid());
                    }
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void redirectAfterLogin(String uid) {
        FirebaseHelper.getUserByUID(uid, new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                setLoading(false);
                if (!snapshot.exists()) {
                    Toast.makeText(LoginActivity.this, "User profile not found.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String userType = snapshot.child("userType").getValue(String.class);
                if ("therapist".equals(userType)) {
                    startActivity(new Intent(LoginActivity.this, TherapistHomeActivity.class));
                } else {
                    // Patient: check if accepted
                    FirebaseHelper.getPatientByUID(uid, new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot patientSnap) {
                            if (patientSnap.exists()) {
                                String status = patientSnap.child("status").getValue(String.class);
                                if ("accepted".equals(status)) {
                                    startActivity(new Intent(LoginActivity.this, PatientHomeActivity.class));
                                } else {
                                    startActivity(new Intent(LoginActivity.this, PatientPendingActivity.class));
                                }
                            } else {
                                startActivity(new Intent(LoginActivity.this, EnterTherapistCodeActivity.class));
                            }
                            finish();
                        }
                        @Override
                        public void onCancelled(DatabaseError error) {
                            startActivity(new Intent(LoginActivity.this, EnterTherapistCodeActivity.class));
                            finish();
                        }
                    });
                    return;
                }
                finish();
            }
            @Override
            public void onCancelled(DatabaseError error) {
                setLoading(false);
            }
        });
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
    }
}
