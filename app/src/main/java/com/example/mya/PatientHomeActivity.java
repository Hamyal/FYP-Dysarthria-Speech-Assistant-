package com.example.mya;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseUser;
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

public class PatientHomeActivity extends AppCompatActivity {

    private String therapistId;
    private String therapistName;
    private TextView patientNameText, patientEmailText;
    private ImageView patientAvatar;
    private ActionBarDrawerToggle drawerToggle;

    private File cameraPhotoFile;
    private String pendingCameraUid;
    private final ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> editProfileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) loadPatientProfile();
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            ok -> {
                if (ok && cameraPhotoFile != null)
                    uploadPhotoFromFile(cameraPhotoFile);
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
                else if (!granted) Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show();
                pendingCameraUid = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_patient_home);
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
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        NavigationView navView = findViewById(R.id.nav_view);
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_overview) {
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }
            if (id == R.id.nav_personal_drills) {
                drawerLayout.closeDrawer(GravityCompat.START);
                openWithPatientId(PersonalDrillsActivity.class, PersonalDrillsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.nav_assigned_drills) {
                drawerLayout.closeDrawer(GravityCompat.START);
                openWithPatientId(AssignedDrillsActivity.class, AssignedDrillsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.nav_sessions) {
                drawerLayout.closeDrawer(GravityCompat.START);
                openWithPatientId(SessionsSummaryActivity.class, SessionsSummaryActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.nav_reports) {
                drawerLayout.closeDrawer(GravityCompat.START);
                openWithPatientId(ReportsActivity.class, ReportsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.nav_ai_suggestions) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent i = new Intent(this, AiSummaryActivity.class);
                i.putExtra(AiSummaryActivity.EXTRA_PATIENT_ID, FirebaseHelper.getCurrentUser() != null ? FirebaseHelper.getCurrentUser().getUid() : null);
                i.putExtra(AiSummaryActivity.EXTRA_ROLE, "patient");
                startActivity(i);
                return true;
            }
            if (id == R.id.nav_talk_with_agent) {
                drawerLayout.closeDrawer(GravityCompat.START);
                startActivity(new Intent(this, AgentChatActivity.class));
                return true;
            }
            if (id == R.id.nav_edit_profile) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "patient");
                editProfileLauncher.launch(i);
                return true;
            }
            if (id == R.id.nav_games) {
                drawerLayout.closeDrawer(GravityCompat.START);
                startActivity(new Intent(this, GamesActivity.class));
                return true;
            }
            if (id == R.id.nav_chat) {
                drawerLayout.closeDrawer(GravityCompat.START);
                if (therapistId != null && !therapistId.isEmpty()) {
                    openChat();
                } else {
                    Toast.makeText(this, getString(R.string.request_pending_msg), Toast.LENGTH_SHORT).show();
                }
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
            int id = item.getItemId();
            if (id == R.id.action_personalized_drills) {
                openWithPatientId(PersonalDrillsActivity.class, PersonalDrillsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.action_assigned_drills) {
                openWithPatientId(AssignedDrillsActivity.class, AssignedDrillsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.action_sessions) {
                openWithPatientId(SessionsSummaryActivity.class, SessionsSummaryActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.action_reports) {
                openWithPatientId(ReportsActivity.class, ReportsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.action_profile) {
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "patient");
                editProfileLauncher.launch(i);
                return true;
            }
            if (id == R.id.action_games) {
                startActivity(new Intent(this, GamesActivity.class));
                return true;
            }
            if (id == R.id.action_logout) {
                doLogout();
                return true;
            }
            return false;
        });
        patientNameText = findViewById(R.id.patientNameText);
        patientEmailText = findViewById(R.id.patientEmailText);
        patientAvatar = findViewById(R.id.patientAvatar);

        findViewById(R.id.avatarCard).setOnClickListener(v -> showPhotoOptionsDialog());

        // Bottom nav (like reference UI)
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_bottom_home);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_bottom_home) return true;
            if (id == R.id.nav_bottom_drills) {
                openWithPatientId(PersonalDrillsActivity.class, PersonalDrillsActivity.EXTRA_PATIENT_ID);
                return true;
            }
            if (id == R.id.nav_bottom_chat) {
                if (therapistId != null && !therapistId.isEmpty()) openChat();
                else Toast.makeText(this, getString(R.string.request_pending_msg), Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == R.id.nav_bottom_profile) {
                Intent i = new Intent(this, EditProfileActivity.class);
                i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "patient");
                editProfileLauncher.launch(i);
                return true;
            }
            return false;
        });

        // 2x2 grid cards
        findViewById(R.id.cardPersonalDrills).setOnClickListener(v ->
                openWithPatientId(PersonalDrillsActivity.class, PersonalDrillsActivity.EXTRA_PATIENT_ID));
        findViewById(R.id.cardAssignedDrills).setOnClickListener(v ->
                openWithPatientId(AssignedDrillsActivity.class, AssignedDrillsActivity.EXTRA_PATIENT_ID));
        findViewById(R.id.cardChat).setOnClickListener(v -> {
            if (therapistId != null && !therapistId.isEmpty()) openChat();
            else Toast.makeText(this, getString(R.string.request_pending_msg), Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.cardProfile).setOnClickListener(v -> {
            Intent i = new Intent(this, EditProfileActivity.class);
            i.putExtra(EditProfileActivity.EXTRA_USER_TYPE, "patient");
            editProfileLauncher.launch(i);
        });

        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user != null) {
            loadPatientProfile();
            FirebaseHelper.getPatientByUID(user.getUid(), new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        Object at = snapshot.child("assigned_therapist").getValue();
                        therapistId = at != null ? String.valueOf(at).trim() : null;
                        FirebaseHelper.ensurePatientListedUnderTherapistIndex(snapshot, user.getUid());
                        if (therapistId != null && !therapistId.isEmpty()) {
                            therapistName = "Therapist";
                            loadTherapistName();
                        }
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {}
            });
        }
    }

    private void loadPatientProfile() {
        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) return;
        FirebaseHelper.getPatientByUID(user.getUid(), new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    FirebaseHelper.ensurePatientListedUnderTherapistIndex(snapshot, user.getUid());
                    String name = snapshot.child("Name").getValue(String.class);
                    String last = snapshot.child("last_name").getValue(String.class);
                    String n = ((name != null ? name : "") + " " + (last != null ? last : "")).trim();
                    if (n.isEmpty()) n = "Patient";
                    patientNameText.setText(getString(R.string.hi_name, n));
                    String em = snapshot.child("email").getValue(String.class);
                    if (em == null || em.isEmpty()) {
                        FirebaseUser u = FirebaseHelper.getCurrentUser();
                        em = u != null ? u.getEmail() : null;
                    }
                    patientEmailText.setText(em != null ? em : "");
                    patientEmailText.setVisibility(em != null && !em.isEmpty() ? View.VISIBLE : View.GONE);
                    String photoUrl = snapshot.child("photoUrl").getValue(String.class);
                    if (photoUrl != null && !photoUrl.isEmpty()) {
                        loadProfileImage(photoUrl, patientAvatar);
                    } else {
                        patientAvatar.setImageResource(R.drawable.ic_person_avatar);
                        patientAvatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void showPhotoOptionsDialog() {
        String uid = FirebaseHelper.getCurrentUser() != null ? FirebaseHelper.getCurrentUser().getUid() : null;
        if (uid == null) return;
        String[] options = {
                getString(R.string.take_photo),
                getString(R.string.choose_from_gallery),
                getString(R.string.remove_photo)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_photo)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) launchCamera(uid);
                    else if (which == 1) showGalleryAndPresetsSheet(uid);
                    else if (which == 2) removePhoto(uid);
                })
                .show();
    }

    private void showGalleryAndPresetsSheet(String uid) {
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
                uploadPhotoFromPresetDrawable(uid, resId);
            });
        }

        content.findViewById(R.id.btn_pick_device).setOnClickListener(v -> {
            sheet.dismiss();
            pickImageLauncher.launch("image/*");
        });

        sheet.show();
    }

    private void uploadPhotoFromPresetDrawable(String uid, int drawableResId) {
        byte[] bytes = PresetAvatars.encodeDrawableAsJpeg(this, drawableResId);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, R.string.failed_read_image, Toast.LENGTH_SHORT).show();
            return;
        }
        uploadPhotoBytes(uid, bytes);
    }

    private void launchCamera(String uid) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraUid = uid;
            requestCameraLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        launchCameraAfterPermission(uid);
    }

    private void launchCameraAfterPermission(String uid) {
        try {
            File cacheDir = getCacheDir();
            cameraPhotoFile = new File(cacheDir, "profile_photo_" + uid + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraPhotoFile);
            takePictureLauncher.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_fill_all), Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadPhotoFromUri(Uri uri) {
        String uid = FirebaseHelper.getCurrentUser() != null ? FirebaseHelper.getCurrentUser().getUid() : null;
        if (uid == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            byte[] bytes = readAllBytes(is);
            if (bytes.length > 0) uploadPhotoBytes(uid, bytes);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to read image", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadPhotoFromFile(File file) {
        String uid = FirebaseHelper.getCurrentUser() != null ? FirebaseHelper.getCurrentUser().getUid() : null;
        if (uid == null || !file.exists()) return;
        try (InputStream is = getContentResolver().openInputStream(Uri.fromFile(file))) {
            if (is == null) return;
            byte[] bytes = readAllBytes(is);
            if (bytes.length > 0) uploadPhotoBytes(uid, bytes);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to read image", Toast.LENGTH_SHORT).show();
        }
    }

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    private void uploadPhotoBytes(String uid, byte[] bytes) {
        Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show();
        FirebaseHelper.uploadProfilePhoto(uid, bytes,
                url -> runOnUiThread(() -> {
                    FirebaseHelper.updatePatientPhotoUrl(uid, url, () -> runOnUiThread(() -> {
                        loadPatientProfile();
                        Toast.makeText(this, "Photo updated", Toast.LENGTH_SHORT).show();
                    }));
                }),
                e -> runOnUiThread(() -> {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : "Upload failed";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }));
    }

    private void removePhoto(String uid) {
        FirebaseHelper.updatePatientPhotoUrl(uid, "", () -> {
            loadPatientProfile();
            Toast.makeText(this, "Photo removed", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadProfileImage(String url, ImageView imageView) {
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

    private void loadTherapistName() {
        if (therapistId == null) return;
        FirebaseHelper.getTherapistRef(therapistId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String name = snapshot.child("Name").getValue(String.class);
                    String lastName = snapshot.child("last_name").getValue(String.class);
                    therapistName = (name != null ? name : "") + " " + (lastName != null ? lastName : "");
                    therapistName = therapistName.trim();
                    if (therapistName.isEmpty()) therapistName = "Therapist";
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void openChat() {
        if (therapistId == null) return;
        if (therapistName == null) therapistName = "Therapist";
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_OTHER_UID, therapistId);
        i.putExtra(ChatActivity.EXTRA_OTHER_NAME, therapistName);
        startActivity(i);
    }

    private void openWithPatientId(Class<?> activityClass, String extraKey) {
        String uid = FirebaseHelper.getCurrentUser() != null ? FirebaseHelper.getCurrentUser().getUid() : null;
        Intent i = new Intent(this, activityClass);
        i.putExtra(extraKey, uid);
        startActivity(i);
    }

    private void doLogout() {
        FirebaseHelper.getAuth().signOut();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
