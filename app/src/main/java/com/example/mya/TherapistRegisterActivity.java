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

public class TherapistRegisterActivity extends AppCompatActivity {

    private TextInputEditText firstNameInput, lastNameInput, emailInput, passwordInput, experienceInput;
    private MaterialButton btnRegister;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_therapist_register);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        firstNameInput = findViewById(R.id.firstName);
        lastNameInput = findViewById(R.id.lastName);
        emailInput = findViewById(R.id.email);
        passwordInput = findViewById(R.id.password);
        experienceInput = findViewById(R.id.experience);
        btnRegister = findViewById(R.id.btnRegister);
        progress = findViewById(R.id.progress);

        btnRegister.setOnClickListener(v -> attemptRegister());
    }

    private void attemptRegister() {
        String firstName = getText(firstNameInput);
        String lastName = getText(lastNameInput);
        String email = getText(emailInput);
        String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";

        if (TextUtils.isEmpty(firstName) || TextUtils.isEmpty(lastName) || TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.error_fill_all, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, R.string.error_invalid_email, Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, R.string.error_password_short, Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        FirebaseHelper.getAuth().createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    FirebaseUser user = authResult.getUser();
                    if (user == null) {
                        setLoading(false);
                        return;
                    }
                    String fullName = firstName.trim() + " " + lastName.trim();
                    FirebaseHelper.saveUserWithUID(user.getUid(), fullName, email, "therapist");
                    String code = FirebaseHelper.generateTherapistCode();
                    Therapist therapist = new Therapist(user.getUid(), code, firstName.trim(), lastName.trim(), email);
                    String exp = getText(experienceInput);
                    therapist.setExperience(exp);
                    FirebaseHelper.saveTherapist(therapist, () -> {
                        setLoading(false);
                        Toast.makeText(TherapistRegisterActivity.this, "Account created. Your code: " + code, Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(TherapistRegisterActivity.this, TherapistHomeActivity.class);
                        intent.putExtra(TherapistHomeActivity.EXTRA_THERAPIST_CODE, code);
                        startActivity(intent);
                        finish();
                    });
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private String getText(TextInputEditText edit) {
        return edit.getText() != null ? edit.getText().toString().trim() : "";
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRegister.setEnabled(!loading);
    }
}
