package com.example.mya;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditProfileActivity extends AppCompatActivity {

    public static final String EXTRA_USER_TYPE = "userType"; // "therapist" or "patient"

    private TextInputEditText firstName, lastName, email, ageInput, experienceInput;
    private View ageLayout, experienceLayout, codeLabel;
    private TextView codeValue;
    private ImageView profileAvatar;
    private MaterialCardView profileAvatarCard;
    private String userType;
    private String uid;

    private File cameraPhotoFile;
    private String pendingCameraUid;
    private final ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Uri> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            ok -> {
                if (ok && cameraPhotoFile != null) uploadPhotoFromFile(cameraPhotoFile);
            });
    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) uploadPhotoFromUri(uri);
            });
    private final ActivityResultLauncher<String> requestCameraLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted && pendingCameraUid != null) launchCameraAfterPermission(pendingCameraUid);
                else if (!granted) Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show();
                pendingCameraUid = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit_profile);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        userType = getIntent() != null ? getIntent().getStringExtra(EXTRA_USER_TYPE) : null;
        if (userType == null) userType = "patient";
        uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null
                ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        firstName = findViewById(R.id.firstName);
        lastName = findViewById(R.id.lastName);
        email = findViewById(R.id.email);
        ageInput = findViewById(R.id.age);
        ageLayout = findViewById(R.id.ageLayout);
        experienceLayout = findViewById(R.id.experienceLayout);
        experienceInput = findViewById(R.id.experience);
        codeLabel = findViewById(R.id.codeLabel);
        codeValue = findViewById(R.id.codeValue);
        profileAvatar = findViewById(R.id.profileAvatar);
        profileAvatarCard = findViewById(R.id.profileAvatarCard);
        MaterialButton btnChangePhoto = findViewById(R.id.btnChangePhoto);

        View.OnClickListener photoClick = v -> showPhotoOptionsDialog();
        profileAvatarCard.setOnClickListener(photoClick);
        profileAvatar.setOnClickListener(photoClick);
        btnChangePhoto.setOnClickListener(photoClick);

        if ("therapist".equals(userType)) {
            ageLayout.setVisibility(View.GONE);
            experienceLayout.setVisibility(View.VISIBLE);
            codeLabel.setVisibility(View.VISIBLE);
            codeValue.setVisibility(View.VISIBLE);
            loadTherapist();
        } else {
            ageLayout.setVisibility(View.VISIBLE);
            experienceLayout.setVisibility(View.GONE);
            codeLabel.setVisibility(View.GONE);
            codeValue.setVisibility(View.GONE);
            loadPatient();
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> saveProfile());
    }

    @Override
    protected void onDestroy() {
        imageLoadExecutor.shutdown();
        super.onDestroy();
    }

    private void showPhotoOptionsDialog() {
        String[] options = {
                getString(R.string.take_photo),
                getString(R.string.choose_from_gallery),
                getString(R.string.remove_photo)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_photo)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) launchCamera(uid);
                    else if (which == 1) showGalleryAndPresetsSheet();
                    else if (which == 2) removePhoto();
                })
                .show();
    }

    /** Gallery flow: favorites row + button to open device picker. */
    private void showGalleryAndPresetsSheet() {
        View content = getLayoutInflater().inflate(R.layout.sheet_profile_photo_presets, null, false);
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.ThemeOverlay_MyA_BottomSheetDialog);
        sheet.setContentView(content);

        int[] imageViewIds = {
                R.id.preset_1, R.id.preset_2, R.id.preset_3, R.id.preset_4, R.id.preset_5
        };
        int[] drawables = PresetAvatars.DRAWABLE_IDS;
        for (int i = 0; i < drawables.length && i < imageViewIds.length; i++) {
            ImageView iv = content.findViewById(imageViewIds[i]);
            iv.setImageResource(drawables[i]);
            final int resId = drawables[i];
            iv.setOnClickListener(v -> {
                sheet.dismiss();
                uploadPhotoFromPresetDrawable(resId);
            });
        }

        content.findViewById(R.id.btn_pick_device).setOnClickListener(v -> {
            sheet.dismiss();
            pickImageLauncher.launch("image/*");
        });

        sheet.show();
    }

    private void uploadPhotoFromPresetDrawable(int drawableResId) {
        byte[] bytes = PresetAvatars.encodeDrawableAsJpeg(this, drawableResId);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, R.string.failed_read_image, Toast.LENGTH_SHORT).show();
            return;
        }
        uploadPhotoBytes(bytes);
    }

    private void launchCamera(String userUid) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraUid = userUid;
            requestCameraLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        launchCameraAfterPermission(userUid);
    }

    private void launchCameraAfterPermission(String userUid) {
        try {
            File cacheDir = getCacheDir();
            cameraPhotoFile = new File(cacheDir, "profile_photo_" + userUid + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraPhotoFile);
            takePictureLauncher.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_fill_all, Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadPhotoFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            byte[] bytes = readAllBytes(is);
            if (bytes.length > 0) uploadPhotoBytes(bytes);
        } catch (Exception e) {
            Toast.makeText(this, R.string.failed_read_image, Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadPhotoFromFile(File file) {
        if (!file.exists()) return;
        try (InputStream is = getContentResolver().openInputStream(Uri.fromFile(file))) {
            if (is == null) return;
            byte[] bytes = readAllBytes(is);
            if (bytes.length > 0) uploadPhotoBytes(bytes);
        } catch (Exception e) {
            Toast.makeText(this, R.string.failed_read_image, Toast.LENGTH_SHORT).show();
        }
    }

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    private void uploadPhotoBytes(byte[] bytes) {
        Toast.makeText(this, R.string.uploading_photo, Toast.LENGTH_SHORT).show();
        FirebaseHelper.uploadProfilePhoto(uid, bytes,
                url -> runOnUiThread(() -> applyPhotoUrl(url)),
                e -> runOnUiThread(() -> {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : getString(R.string.upload_failed);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }));
    }

    private void applyPhotoUrl(String url) {
        Runnable afterDb = () -> runOnUiThread(() -> {
            loadProfileImageIntoView(url, profileAvatar);
            Toast.makeText(this, R.string.photo_updated, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        });
        if ("therapist".equals(userType)) {
            FirebaseHelper.updateTherapistPhotoUrl(uid, url, afterDb);
        } else {
            FirebaseHelper.updatePatientPhotoUrl(uid, url, afterDb);
        }
    }

    private void removePhoto() {
        Runnable afterDb = () -> runOnUiThread(() -> {
            profileAvatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            profileAvatar.setImageResource(R.drawable.ic_person_avatar);
            Toast.makeText(this, R.string.photo_removed, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        });
        if ("therapist".equals(userType)) {
            FirebaseHelper.updateTherapistPhotoUrl(uid, "", afterDb);
        } else {
            FirebaseHelper.updatePatientPhotoUrl(uid, "", afterDb);
        }
    }

    private void loadProfileImageIntoView(String url, ImageView imageView) {
        if (url == null || url.isEmpty()) {
            imageView.setImageResource(R.drawable.ic_person_avatar);
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return;
        }
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
                        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        imageView.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    imageView.setImageResource(R.drawable.ic_person_avatar);
                    imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                });
            }
        });
    }

    private void bindPhotoFromSnapshot(DataSnapshot snapshot) {
        String photoUrl = snapshot.child("photoUrl").getValue(String.class);
        if (photoUrl != null && !photoUrl.isEmpty()) {
            loadProfileImageIntoView(photoUrl, profileAvatar);
        } else {
            profileAvatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            profileAvatar.setImageResource(R.drawable.ic_person_avatar);
        }
    }

    private void loadTherapist() {
        FirebaseHelper.getTherapistRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    firstName.setText(snapshot.child("Name").getValue(String.class));
                    lastName.setText(snapshot.child("last_name").getValue(String.class));
                    email.setText(snapshot.child("email").getValue(String.class));
                    String code = snapshot.child("code").getValue(String.class);
                    if (code != null) codeValue.setText(code.trim().toUpperCase());
                    String exp = snapshot.child("Experience").getValue(String.class);
                    if (exp != null) experienceInput.setText(exp);
                    bindPhotoFromSnapshot(snapshot);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void loadPatient() {
        FirebaseHelper.getPatientByUID(uid, new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    firstName.setText(snapshot.child("Name").getValue(String.class));
                    lastName.setText(snapshot.child("last_name").getValue(String.class));
                    email.setText(snapshot.child("email").getValue(String.class));
                    Object a = snapshot.child("Age").getValue();
                    if (a instanceof Number) ageInput.setText(String.valueOf(((Number) a).intValue()));
                    bindPhotoFromSnapshot(snapshot);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void saveProfile() {
        String first = firstName.getText() != null ? firstName.getText().toString().trim() : "";
        String last = lastName.getText() != null ? lastName.getText().toString().trim() : "";
        String em = email.getText() != null ? email.getText().toString().trim() : "";
        if (TextUtils.isEmpty(first) || TextUtils.isEmpty(em)) {
            Toast.makeText(this, R.string.error_fill_all, Toast.LENGTH_SHORT).show();
            return;
        }
        if ("therapist".equals(userType)) {
            String exp = experienceInput.getText() != null ? experienceInput.getText().toString().trim() : "";
            FirebaseHelper.updateTherapistProfile(uid, first, last, em, exp, () -> {
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        } else {
            int age = 0;
            try {
                String ageStr = ageInput.getText() != null ? ageInput.getText().toString().trim() : "";
                if (!ageStr.isEmpty()) age = Integer.parseInt(ageStr);
            } catch (NumberFormatException ignored) {}
            FirebaseHelper.updatePatientProfile(uid, first, last, em, age, () -> {
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        }
    }
}
