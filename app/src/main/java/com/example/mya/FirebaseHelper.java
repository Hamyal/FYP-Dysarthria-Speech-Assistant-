package com.example.mya;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.function.Consumer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Firebase Auth + Realtime Database helper for the full application:
 * users, patients, therapists, sessions, messages, notes, tags, treatment plans,
 * progress reports, speech drills, chatbot, patient requests.
 */
public class FirebaseHelper {

    private static final String TAG = "FirebaseHelper";
    private static final FirebaseDatabase database;
    private static final DatabaseReference rootRef;
    private static final FirebaseAuth auth = FirebaseAuth.getInstance();

    // Database paths
    public static final String PATH_USERS = "users";
    public static final String PATH_PATIENTS = "Patient";
    public static final String PATH_THERAPISTS = "Therapist";
    public static final String PATH_SESSIONS = "sessions";
    public static final String PATH_MESSAGES = "messages";
    /** Patient–therapist chat: conversations/{conversationId}/messages/{messageId} */
    public static final String PATH_CONVERSATIONS = "conversations";
    public static final String PATH_NOTES = "notes";
    public static final String PATH_TAGS = "tags";
    public static final String PATH_TREATMENT_PLANS = "treatment_plans";
    public static final String PATH_EXERCISES = "exercises";
    public static final String PATH_CHATBOT_INTERACTIONS = "chatbot_interaction";
    public static final String PATH_PATIENT_REQUESTS = "patient_requests";
    public static final String PATH_PROGRESS_REPORTS = "progress_Reports";
    public static final String PATH_SPEECH_DRILLS = "speechDrills";
    /** Therapist-assigned drills: assigned_drills/{assignedDrillId} */
    public static final String PATH_ASSIGNED_DRILLS = "assigned_drills";
    /** patient_drill_index/{patientUid}/{drillId} = true — lets patients list drills without a filtered query (rules-friendly). */
    public static final String PATH_PATIENT_DRILL_INDEX = "patient_drill_index";
    /** Patient session count + records: patient_sessions/{patientId}/sessionCount, patient_sessions/{patientId}/sessions/{sessionId} */
    public static final String PATH_PATIENT_SESSIONS = "patient_sessions";
    /** therapist_patients/{therapistUid}/{patientUid} — lets therapists list patients (Patient queries are unreliable under security rules). */
    public static final String PATH_THERAPIST_PATIENTS = "therapist_patients";

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final Random random = new Random();

    static {
        database = FirebaseDatabase.getInstance("https://fypproject-487fb-default-rtdb.firebaseio.com");
        rootRef = database.getReference();
    }

    public static FirebaseAuth getAuth() { return auth; }
    public static FirebaseUser getCurrentUser() { return auth.getCurrentUser(); }
    public static DatabaseReference getRootReference() { return rootRef; }
    public static DatabaseReference getReference(String path) { return rootRef.child(path); }

    // ==================== USER OPERATIONS ====================

    public static void saveUser(User user) {
        if (user == null) return;
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("name", user.getName());
        userData.put("email", user.getEmail());
        userData.put("userType", user.getUserType());
        userData.put("createdAt", user.getCreatedAt() > 0 ? user.getCreatedAt() : System.currentTimeMillis());

        rootRef.child(PATH_USERS).child(String.valueOf(user.getId())).setValue(userData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "User saved: " + user.getEmail());
                    else Log.e(TAG, "Failed to save user", task.getException());
                });
    }

    public static void saveUserWithUID(String firebaseUID, String name, String email, String userType) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", firebaseUID);
        userData.put("name", name);
        userData.put("email", email);
        userData.put("userType", userType);
        userData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_USERS).child(firebaseUID).setValue(userData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "User saved: " + firebaseUID);
                    else Log.e(TAG, "Failed to save user with UID", task.getException());
                });
    }

    public static void getUserByUID(String uid, ValueEventListener listener) {
        rootRef.child(PATH_USERS).child(uid).addListenerForSingleValueEvent(listener);
    }

    /** Update therapist profile (Name, last_name, email, Experience) and users node. */
    public static void updateTherapistProfile(String uid, String firstName, String lastName, String email, String experience, Runnable onSuccess) {
        if (uid == null) return;
        Map<String, Object> therapistUpdates = new HashMap<>();
        therapistUpdates.put("Name", firstName != null ? firstName.trim() : "");
        therapistUpdates.put("last_name", lastName != null ? lastName.trim() : "");
        therapistUpdates.put("email", email != null ? email.trim() : "");
        therapistUpdates.put("Experience", experience != null ? experience.trim() : "");
        rootRef.child(PATH_THERAPISTS).child(uid).updateChildren(therapistUpdates)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "Failed to update therapist profile", task.getException());
                        return;
                    }
                    String fullName = ((firstName != null ? firstName.trim() : "") + " " + (lastName != null ? lastName.trim() : "")).trim();
                    if (fullName.isEmpty()) fullName = "Therapist";
                    Map<String, Object> userUpdates = new HashMap<>();
                    userUpdates.put("name", fullName);
                    userUpdates.put("email", email != null ? email.trim() : "");
                    rootRef.child(PATH_USERS).child(uid).updateChildren(userUpdates)
                            .addOnCompleteListener(t -> {
                                if (t.isSuccessful() && onSuccess != null) onSuccess.run();
                            });
                });
    }

    /** Update patient profile (Name, last_name, email, Age) and users node. */
    public static void updatePatientProfile(String uid, String firstName, String lastName, String email, int age, Runnable onSuccess) {
        if (uid == null) return;
        Map<String, Object> patientUpdates = new HashMap<>();
        patientUpdates.put("Name", firstName != null ? firstName.trim() : "");
        patientUpdates.put("last_name", lastName != null ? lastName.trim() : "");
        patientUpdates.put("email", email != null ? email.trim() : "");
        patientUpdates.put("Age", age);
        rootRef.child(PATH_PATIENTS).child(uid).updateChildren(patientUpdates)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "Failed to update patient profile", task.getException());
                        return;
                    }
                    String fullName = ((firstName != null ? firstName.trim() : "") + " " + (lastName != null ? lastName.trim() : "")).trim();
                    if (fullName.isEmpty()) fullName = "Patient";
                    Map<String, Object> userUpdates = new HashMap<>();
                    userUpdates.put("name", fullName);
                    userUpdates.put("email", email != null ? email.trim() : "");
                    rootRef.child(PATH_USERS).child(uid).updateChildren(userUpdates)
                            .addOnCompleteListener(t -> {
                                if (t.isSuccessful() && onSuccess != null) onSuccess.run();
                            });
                });
    }

    // ==================== PATIENT OPERATIONS ====================

    public static void savePatient(Patient patient) {
        if (patient == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("patient_id", patient.getPatient_id());
        data.put("Name", patient.getName());
        data.put("last_name", patient.getLast_name());
        data.put("email", patient.getEmail());
        data.put("Age", patient.getAge());
        data.put("assigned_therapist", patient.getAssigned_therapist() != null ? patient.getAssigned_therapist() : "");
        data.put("status", patient.getStatus() != null ? patient.getStatus() : "pending");
        data.put("Last_session", patient.getLast_session() != null ? patient.getLast_session() : "");
        data.put("progress_score", patient.getProgress_score());

        rootRef.child(PATH_PATIENTS).child(patient.getPatient_id()).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Patient saved: " + patient.getEmail());
                        maybeLinkPatientTherapistIndex(patient);
                    } else Log.e(TAG, "Failed to save patient", task.getException());
                });
    }

    /** Save patient and run onSuccess when done. */
    public static void savePatient(Patient patient, Runnable onSuccess) {
        if (patient == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("patient_id", patient.getPatient_id());
        data.put("Name", patient.getName());
        data.put("last_name", patient.getLast_name());
        data.put("email", patient.getEmail());
        data.put("Age", patient.getAge());
        data.put("assigned_therapist", patient.getAssigned_therapist() != null ? patient.getAssigned_therapist() : "");
        data.put("status", patient.getStatus() != null ? patient.getStatus() : "pending");
        data.put("Last_session", patient.getLast_session() != null ? patient.getLast_session() : "");
        data.put("progress_score", patient.getProgress_score());

        rootRef.child(PATH_PATIENTS).child(patient.getPatient_id()).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Patient saved: " + patient.getEmail());
                        maybeLinkPatientTherapistIndex(patient);
                        if (onSuccess != null) onSuccess.run();
                    } else Log.e(TAG, "Failed to save patient", task.getException());
                });
    }

    /** Add patient under therapist index when assignment is accepted (idempotent). */
    public static void linkPatientToTherapistIndex(String therapistId, String patientId) {
        if (therapistId == null || patientId == null || therapistId.isEmpty() || patientId.isEmpty()) return;
        rootRef.child(PATH_THERAPIST_PATIENTS).child(therapistId).child(patientId).setValue(System.currentTimeMillis());
    }

    public static DatabaseReference getTherapistPatientIndexRef(String therapistId) {
        return rootRef.child(PATH_THERAPIST_PATIENTS).child(therapistId);
    }

    /** Backfill index from Patient snapshot (call with patient’s own UID as patientId). */
    public static void ensurePatientListedUnderTherapistIndex(DataSnapshot patientSnap, String patientUid) {
        if (patientSnap == null || !patientSnap.exists() || patientUid == null || patientUid.isEmpty()) return;
        Object at = patientSnap.child("assigned_therapist").getValue();
        String tid = at != null ? String.valueOf(at).trim() : "";
        if (tid.isEmpty()) return;
        String st = patientSnap.child("status").getValue(String.class);
        if (st == null || !"accepted".equalsIgnoreCase(st.trim())) return;
        linkPatientToTherapistIndex(tid, patientUid);
    }

    private static void maybeLinkPatientTherapistIndex(Patient patient) {
        if (patient == null) return;
        String tid = patient.getAssigned_therapist();
        String st = patient.getStatus();
        if (tid == null || tid.isEmpty() || st == null || !"accepted".equalsIgnoreCase(st.trim())) return;
        linkPatientToTherapistIndex(tid, patient.getPatient_id());
    }

    /** By therapist ID (numeric). */
    public static Query getPatientsByTherapist(int therapistId) {
        return rootRef.child(PATH_PATIENTS).orderByChild("assigned_therapist").equalTo(therapistId);
    }

    /** By therapist UID (string). */
    public static Query getPatientsByTherapist(String therapistId) {
        return rootRef.child(PATH_PATIENTS).orderByChild("assigned_therapist").equalTo(therapistId);
    }

    public static DatabaseReference getPatientById(int patientId) {
        return rootRef.child(PATH_PATIENTS).child(String.valueOf(patientId));
    }

    public static DatabaseReference getPatientRef(String patientId) {
        return rootRef.child(PATH_PATIENTS).child(patientId);
    }

    private static final String STORAGE_PROFILE_PHOTOS = "profile_photos";

    /** Upload profile photo for patient; onUrlReady receives download URL. */
    public static void uploadProfilePhoto(String patientId, byte[] imageBytes, Consumer<String> onUrlReady, Consumer<Exception> onError) {
        if (patientId == null || imageBytes == null || imageBytes.length == 0) {
            if (onError != null) onError.accept(new IllegalArgumentException("patientId and imageBytes required"));
            return;
        }
        StorageReference ref = FirebaseStorage.getInstance().getReference()
                .child(STORAGE_PROFILE_PHOTOS).child(patientId + ".jpg");
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build();
        UploadTask task = ref.putBytes(imageBytes, metadata);
        task.addOnSuccessListener(t -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
            if (onUrlReady != null) onUrlReady.accept(uri.toString());
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get profile photo URL", e);
            if (onError != null) onError.accept(e);
        })).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to upload profile photo", e);
            if (onError != null) onError.accept(e);
        });
    }

    /** Set or clear patient photoUrl in database. */
    public static void updatePatientPhotoUrl(String patientId, String photoUrl, Runnable onSuccess) {
        if (patientId == null) return;
        rootRef.child(PATH_PATIENTS).child(patientId).child("photoUrl").setValue(photoUrl != null ? photoUrl : "")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && onSuccess != null) onSuccess.run();
                    else if (!task.isSuccessful()) Log.e(TAG, "Failed to update photoUrl", task.getException());
                });
    }

    /** Set or clear therapist photoUrl (same Storage path as patients: profile_photos/{uid}.jpg). */
    public static void updateTherapistPhotoUrl(String therapistId, String photoUrl, Runnable onSuccess) {
        if (therapistId == null) return;
        rootRef.child(PATH_THERAPISTS).child(therapistId).child("photoUrl").setValue(photoUrl != null ? photoUrl : "")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && onSuccess != null) onSuccess.run();
                    else if (!task.isSuccessful()) Log.e(TAG, "Failed to update therapist photoUrl", task.getException());
                });
    }

    public static void getPatientByUID(String uid, ValueEventListener listener) {
        rootRef.child(PATH_PATIENTS).child(uid).addListenerForSingleValueEvent(listener);
    }

    public static Query getPatientByEmail(String email) {
        return rootRef.child(PATH_PATIENTS).orderByChild("email").equalTo(email);
    }

    public static void updatePatientProgressScore(int patientId, int newScore) {
        rootRef.child(PATH_PATIENTS).child(String.valueOf(patientId)).child("progress_score").setValue(newScore)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient progress_score updated: " + patientId);
                    else Log.e(TAG, "Failed to update progress_score", task.getException());
                });
    }

    public static void updatePatientProgressScore(String patientId, int newScore) {
        rootRef.child(PATH_PATIENTS).child(patientId).child("progress_score").setValue(newScore)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient progress_score updated: " + patientId);
                    else Log.e(TAG, "Failed to update progress_score", task.getException());
                });
    }

    public static void updatePatientLastSession(int patientId, String lastSessionDate) {
        rootRef.child(PATH_PATIENTS).child(String.valueOf(patientId)).child("Last_session").setValue(lastSessionDate)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient Last_session updated: " + patientId);
                    else Log.e(TAG, "Failed to update Last_session", task.getException());
                });
    }

    public static void updatePatientLastSession(String patientId, String lastSessionDate) {
        rootRef.child(PATH_PATIENTS).child(patientId).child("Last_session").setValue(lastSessionDate)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient Last_session updated: " + patientId);
                    else Log.e(TAG, "Failed to update Last_session", task.getException());
                });
    }

    public static void updatePatientLevel(int patientId, String newLevel) {
        rootRef.child(PATH_PATIENTS).child(String.valueOf(patientId)).child("currentLevel").setValue(newLevel);
    }

    public static void updatePatientLevel(String patientId, String newLevel) {
        rootRef.child(PATH_PATIENTS).child(patientId).child("currentLevel").setValue(newLevel);
    }

    public static void updatePatientAssignedTherapist(String patientId, String therapistId, String status) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("assigned_therapist", therapistId);
        updates.put("status", status);
        rootRef.child(PATH_PATIENTS).child(patientId).updateChildren(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Patient assigned: " + patientId);
                        if (status != null && "accepted".equalsIgnoreCase(status.trim())
                                && therapistId != null && !therapistId.isEmpty()) {
                            linkPatientToTherapistIndex(therapistId, patientId);
                        }
                    } else Log.e(TAG, "Failed to update patient", task.getException());
                });
    }

    // ==================== THERAPIST OPERATIONS ====================

    public static String generateTherapistCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    public static void saveTherapist(Therapist therapist) {
        if (therapist == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("therapist_id", therapist.getTherapist_id());
        data.put("code", therapist.getCode() != null ? therapist.getCode().trim().toUpperCase() : "");
        data.put("Name", therapist.getName());
        data.put("last_name", therapist.getLast_name());
        data.put("email", therapist.getEmail());
        data.put("Experience", therapist.getExperience() != null ? therapist.getExperience() : "");
        data.put("assigned_patients", therapist.getAssigned_patients());

        rootRef.child(PATH_THERAPISTS).child(therapist.getTherapist_id()).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Therapist saved: " + therapist.getEmail());
                    else Log.e(TAG, "Failed to save therapist", task.getException());
                });
    }

    /** Save therapist and run onSuccess when done (so code is in Firebase before opening home). */
    public static void saveTherapist(Therapist therapist, Runnable onSuccess) {
        if (therapist == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("therapist_id", therapist.getTherapist_id());
        data.put("code", therapist.getCode() != null ? therapist.getCode().trim().toUpperCase() : "");
        data.put("Name", therapist.getName());
        data.put("last_name", therapist.getLast_name());
        data.put("email", therapist.getEmail());
        data.put("Experience", therapist.getExperience() != null ? therapist.getExperience() : "");
        data.put("assigned_patients", therapist.getAssigned_patients());

        rootRef.child(PATH_THERAPISTS).child(therapist.getTherapist_id()).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Therapist saved: " + therapist.getEmail());
                        if (onSuccess != null) onSuccess.run();
                    } else {
                        Log.e(TAG, "Failed to save therapist", task.getException());
                    }
                });
    }

    /** Update only the therapist's code when missing. Never overwrite an existing code. */
    public static void updateTherapistCode(String therapistId, String code) {
        if (therapistId == null || code == null) return;
        rootRef.child(PATH_THERAPISTS).child(therapistId).child("code").setValue(code.toUpperCase())
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Therapist code updated: " + therapistId);
                    else Log.e(TAG, "Failed to update therapist code", task.getException());
                });
    }

    /**
     * Set therapist code only if it is currently missing (null or empty).
     * Uses a transaction so the code is never overwritten once it exists.
     * onResult is always called once with the final code to display (existing or newCode as fallback).
     */
    public static void setTherapistCodeIfMissing(String therapistId, String newCode, Consumer<String> onResult) {
        if (therapistId == null || newCode == null || onResult == null) return;
        final String fallbackCode = newCode.trim().toUpperCase();
        DatabaseReference codeRef = rootRef.child(PATH_THERAPISTS).child(therapistId).child("code");
        codeRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull com.google.firebase.database.MutableData current) {
                Object val = current.getValue();
                String existing = (val instanceof String) ? ((String) val).trim() : null;
                if (existing != null && !existing.isEmpty()) {
                    return Transaction.success(current);
                }
                current.setValue(fallbackCode);
                return Transaction.success(current);
            }
            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                if (error != null) {
                    Log.e(TAG, "setTherapistCodeIfMissing failed", error.toException());
                    onResult.accept(fallbackCode);
                    return;
                }
                if (snapshot != null && snapshot.exists()) {
                    Object val = snapshot.getValue();
                    String finalCode = (val instanceof String) ? ((String) val).trim() : null;
                    if (finalCode != null && !finalCode.isEmpty()) {
                        onResult.accept(finalCode.toUpperCase());
                        return;
                    }
                }
                onResult.accept(fallbackCode);
            }
        });
    }

    public static void getTherapistByCode(String code, ValueEventListener listener) {
        if (code == null || code.trim().isEmpty()) {
            if (listener != null) {
                listener.onCancelled(DatabaseError.fromException(new IllegalArgumentException("code is empty")));
            }
            return;
        }
        String normalized = code.trim().toUpperCase();
        Query q = rootRef.child(PATH_THERAPISTS).orderByChild("code").equalTo(normalized);
        q.addListenerForSingleValueEvent(listener);
    }

    public static DatabaseReference getTherapistRef(String therapistId) {
        return rootRef.child(PATH_THERAPISTS).child(therapistId);
    }

    public static Query getTherapists() {
        return rootRef.child(PATH_THERAPISTS);
    }

    public static Query getTherapistById(int therapistId) {
        return rootRef.child(PATH_THERAPISTS).orderByChild("therapist_id").equalTo(therapistId);
    }

    public static void updateTherapistAssignedPatients(int therapistId, int count) {
        rootRef.child(PATH_THERAPISTS).child(String.valueOf(therapistId)).child("assigned_patients").setValue(count)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Therapist assigned_patients updated: " + therapistId);
                    else Log.e(TAG, "Failed to update assigned_patients", task.getException());
                });
    }

    public static void incrementTherapistAssignedPatients(String therapistId) {
        rootRef.child(PATH_THERAPISTS).child(therapistId).child("assigned_patients")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        long count = 0;
                        if (snapshot.exists()) {
                            Object v = snapshot.getValue();
                            if (v instanceof Number) count = ((Number) v).longValue();
                        }
                        rootRef.child(PATH_THERAPISTS).child(therapistId).child("assigned_patients").setValue(count + 1);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    // ==================== SESSION OPERATIONS ====================

    public static void saveTherapySession(int sessionId, int patientId, String sessionType,
                                          String therapyLevel, double clarityScore, double fluencyScore,
                                          double accuracyScore, double overallScore, int duration,
                                          String feedback) {
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("sessionId", sessionId);
        sessionData.put("patient_id", patientId);
        sessionData.put("sessionType", sessionType);
        sessionData.put("therapyLevel", therapyLevel);
        sessionData.put("clarityScore", clarityScore);
        sessionData.put("fluencyScore", fluencyScore);
        sessionData.put("accuracyScore", accuracyScore);
        sessionData.put("overallScore", overallScore);
        sessionData.put("duration", duration);
        sessionData.put("feedback", feedback);
        String sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        sessionData.put("sessionDate", sessionDate);
        sessionData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_SESSIONS).child(String.valueOf(sessionId)).setValue(sessionData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Session saved: " + sessionId);
                        updatePatientLastSession(patientId, sessionDate);
                        updatePatientProgressScore(patientId, (int) overallScore);
                    } else Log.e(TAG, "Failed to save session", task.getException());
                });
    }

    public static Query getSessionsByPatient(int patientId) {
        return rootRef.child(PATH_SESSIONS).orderByChild("patient_id").equalTo(patientId);
    }

    public static Query getSessionsByTherapist(int therapistId) {
        return rootRef.child(PATH_SESSIONS).orderByChild("therapist_id").equalTo(therapistId);
    }

    // ==================== MESSAGE OPERATIONS ====================

    public static void sendMessage(int messageId, int senderId, int receiverId, String messageText) {
        Map<String, Object> messageData = new HashMap<>();
        messageData.put("messageId", messageId);
        messageData.put("senderId", senderId);
        messageData.put("receiverId", receiverId);
        messageData.put("messageText", messageText);
        messageData.put("messageDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        messageData.put("isRead", false);
        messageData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_MESSAGES).child(String.valueOf(messageId)).setValue(messageData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Message saved: " + messageId);
                    else Log.e(TAG, "Failed to save message", task.getException());
                });
    }

    public static Query getMessages(int userId1, int userId2) {
        return rootRef.child(PATH_MESSAGES).orderByChild("createdAt");
    }

    public static void markMessageAsRead(int messageId) {
        rootRef.child(PATH_MESSAGES).child(String.valueOf(messageId)).child("isRead").setValue(true);
    }

    // ==================== PATIENT–THERAPIST CHAT (conversations) ====================

    /** Deterministic conversation id so both users see the same thread. */
    public static String getConversationId(String uid1, String uid2) {
        if (uid1 == null || uid2 == null) return "";
        int c = uid1.compareTo(uid2);
        return c <= 0 ? uid1 + "_" + uid2 : uid2 + "_" + uid1;
    }

    /** Send a chat message; stored under conversations/{conversationId}/messages/{pushId}. */
    public static void sendChatMessage(String conversationId, String senderId, String senderName,
                                       String receiverId, String text) {
        if (conversationId == null || conversationId.isEmpty()) return;
        String messageId = rootRef.child(PATH_CONVERSATIONS).child(conversationId).child("messages").push().getKey();
        if (messageId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("id", messageId);
        data.put("senderId", senderId);
        data.put("senderName", senderName != null ? senderName : "");
        data.put("receiverId", receiverId);
        data.put("text", text != null ? text : "");
        data.put("timestamp", System.currentTimeMillis());
        data.put("messageDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        data.put("isRead", false);

        rootRef.child(PATH_CONVERSATIONS).child(conversationId).child("messages").child(messageId).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Chat message saved: " + messageId);
                    else Log.e(TAG, "Failed to save chat message", task.getException());
                });
    }

    /** Real-time listener: reference to messages for a conversation. */
    public static DatabaseReference getMessagesRef(String conversationId) {
        return rootRef.child(PATH_CONVERSATIONS).child(conversationId).child("messages");
    }

    /** Query messages in a conversation ordered by timestamp. */
    public static Query getChatMessagesQuery(String conversationId) {
        return rootRef.child(PATH_CONVERSATIONS).child(conversationId).child("messages").orderByChild("timestamp");
    }

    // ==================== AI CHAT OPERATIONS ====================

    public static void saveAIChatMessage(int userId, String userType, String message, String response) {
        String chatId = rootRef.child(PATH_CHATBOT_INTERACTIONS).push().getKey();
        if (chatId == null) return;
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("chatId", chatId);
        chatData.put("userId", userId);
        chatData.put("userType", userType);
        chatData.put("userMessage", message);
        chatData.put("aiResponse", response);
        chatData.put("timestamp", System.currentTimeMillis());
        chatData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_CHATBOT_INTERACTIONS).child(chatId).setValue(chatData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "AI chat saved: " + chatId);
                    else Log.e(TAG, "Failed to save AI chat", task.getException());
                });
    }

    public static void saveAIChatMessage(String userId, String userType, String message, String response) {
        String chatId = rootRef.child(PATH_CHATBOT_INTERACTIONS).push().getKey();
        if (chatId == null) return;
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("chatId", chatId);
        chatData.put("userId", userId);
        chatData.put("userType", userType);
        chatData.put("userMessage", message);
        chatData.put("aiResponse", response);
        chatData.put("timestamp", System.currentTimeMillis());
        chatData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_CHATBOT_INTERACTIONS).child(chatId).setValue(chatData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "AI chat saved: " + chatId);
                    else Log.e(TAG, "Failed to save AI chat", task.getException());
                });
    }

    public static Query getAIChatHistory(int userId) {
        return rootRef.child(PATH_CHATBOT_INTERACTIONS).orderByChild("userId").equalTo(userId);
    }

    public static Query getAIChatHistory(String userId) {
        return rootRef.child(PATH_CHATBOT_INTERACTIONS).orderByChild("userId").equalTo(userId);
    }

    // ==================== PATIENT REQUESTS ====================

    public static void sendPatientRequest(String requestId, String patientId, String patientName,
                                          String patientEmail, int patientAge,
                                          String therapistId, String therapistName) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", requestId);
        data.put("patientId", patientId);
        data.put("patientName", patientName);
        data.put("patientEmail", patientEmail);
        data.put("patientAge", patientAge);
        data.put("therapistId", therapistId);
        data.put("therapistName", therapistName);
        data.put("status", "pending");
        data.put("timestamp", System.currentTimeMillis());

        rootRef.child(PATH_PATIENT_REQUESTS).child(requestId).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient request sent: " + requestId);
                    else Log.e(TAG, "Failed to send request", task.getException());
                });
    }

    public static void sendPatientRequest(String requestId, int patientId, String patientName,
                                          String patientEmail, int therapistId, String therapistName) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", requestId);
        data.put("patientId", patientId);
        data.put("patientName", patientName);
        data.put("patientEmail", patientEmail);
        data.put("therapistId", therapistId);
        data.put("therapistName", therapistName);
        data.put("status", "pending");
        data.put("timestamp", System.currentTimeMillis());

        rootRef.child(PATH_PATIENT_REQUESTS).child(requestId).setValue(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Patient request sent: " + requestId);
                    else Log.e(TAG, "Failed to send request", task.getException());
                });
    }

    public static Query getPatientRequestsByTherapist(String therapistId) {
        return rootRef.child(PATH_PATIENT_REQUESTS).orderByChild("therapistId").equalTo(therapistId);
    }

    public static Query getPatientRequestsByTherapist(int therapistId) {
        return rootRef.child(PATH_PATIENT_REQUESTS).orderByChild("therapistId").equalTo(therapistId);
    }

    public static Query getPatientRequestsByPatient(String patientId) {
        return rootRef.child(PATH_PATIENT_REQUESTS).orderByChild("patientId").equalTo(patientId);
    }

    public static Query getPatientRequestsByPatient(int patientId) {
        return rootRef.child(PATH_PATIENT_REQUESTS).orderByChild("patientId").equalTo(patientId);
    }

    public static void updateRequestStatus(String requestId, String status) {
        rootRef.child(PATH_PATIENT_REQUESTS).child(requestId).child("status").setValue(status)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Request status: " + status);
                    else Log.e(TAG, "Failed to update request status", task.getException());
                });
    }

    public static void getAcceptedRequestForPatient(String patientId, ValueEventListener listener) {
        rootRef.child(PATH_PATIENT_REQUESTS).orderByChild("patientId").equalTo(patientId).addListenerForSingleValueEvent(listener);
    }

    // ==================== PROGRESS REPORTS ====================

    public static void saveProgressReport(ProgressReport report) {
        if (report == null) return;
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("difficulty", report.getDifficulty());
        reportData.put("improvement", report.getImprovement());
        reportData.put("level_score", report.getLevel_score());
        reportData.put("patient_name", report.getPatient_name());
        reportData.put("summary", report.getSummary());
        reportData.put("total_sessions", report.getTotal_sessions());
        reportData.put("exercise_completed", report.getExercise_completed());
        reportData.put("feedback", report.getFeedback());
        reportData.put("patient_id", report.getPatient_id());
        reportData.put("session_id", report.getSession_id());
        reportData.put("therapist_id", report.getTherapist_id());

        if (report.getSessions() != null && !report.getSessions().isEmpty()) {
            List<Map<String, Object>> sessionsList = new java.util.ArrayList<>();
            for (ProgressReport.SessionData session : report.getSessions()) {
                Map<String, Object> sessionMap = new HashMap<>();
                sessionMap.put("avg_score", session.getAvg_score());
                sessionMap.put("data", session.getData());
                sessionsList.add(sessionMap);
            }
            reportData.put("sessions", sessionsList);
        }

        rootRef.child(PATH_PROGRESS_REPORTS).child(report.getSession_id()).setValue(reportData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Progress report saved: " + report.getSession_id());
                    else Log.e(TAG, "Failed to save progress report", task.getException());
                });
    }

    public static Query getProgressReportsByPatient(int patientId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("patient_id").equalTo(patientId);
    }

    public static Query getProgressReportsByPatient(String patientId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("patient_id").equalTo(patientId);
    }

    public static Query getProgressReportsByTherapist(int therapistId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("therapist_id").equalTo(therapistId);
    }

    public static Query getProgressReportsByTherapist(String therapistId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("therapist_id").equalTo(therapistId);
    }

    public static Query getProgressReportBySessionId(String sessionId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("session_id").equalTo(sessionId);
    }

    // ==================== SPEECH DRILLS ====================

    public static void saveSpeechDrill(SpeechDrill drill) {
        if (drill == null) return;
        String drillId = rootRef.child(PATH_SPEECH_DRILLS).push().getKey();
        if (drillId == null) return;
        Map<String, Object> drillData = new HashMap<>();
        drillData.put("drillId", drillId);
        drillData.put("assigned_to", drill.getAssigned_to());
        drillData.put("difficulty", drill.getDifficulty());
        drillData.put("score", drill.getScore());
        drillData.put("target", drill.getTarget());
        drillData.put("word", drill.getWord());
        drillData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_SPEECH_DRILLS).child(drillId).setValue(drillData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Speech drill saved: " + drillId);
                    else Log.e(TAG, "Failed to save speech drill", task.getException());
                });
    }

    public static Query getSpeechDrillsByPatient(String patientName) {
        return rootRef.child(PATH_SPEECH_DRILLS).orderByChild("assigned_to").equalTo(patientName);
    }

    public static Query getSpeechDrillsByDifficulty(String difficulty) {
        return rootRef.child(PATH_SPEECH_DRILLS).orderByChild("difficulty").equalTo(difficulty);
    }

    public static Query getAllSpeechDrills() {
        return rootRef.child(PATH_SPEECH_DRILLS);
    }

    // ==================== ASSIGNED DRILLS (therapist → patient) ====================

    public static void saveAssignedDrill(AssignedDrill drill, Runnable onSuccess) {
        if (drill == null) return;
        String pid = drill.getPatientId() != null ? drill.getPatientId().trim() : "";
        if (pid.isEmpty()) {
            Log.e(TAG, "saveAssignedDrill: missing patientId");
            return;
        }
        String id = rootRef.child(PATH_ASSIGNED_DRILLS).push().getKey();
        if (id == null) return;
        drill.setAssignedDrillId(id);
        Map<String, Object> data = new HashMap<>();
        data.put("assignedDrillId", id);
        data.put("patientId", pid);
        data.put("therapistId", drill.getTherapistId());
        data.put("therapistName", drill.getTherapistName() != null ? drill.getTherapistName() : "");
        data.put("title", drill.getTitle() != null ? drill.getTitle() : "");
        data.put("instructions", drill.getInstructions() != null ? drill.getInstructions() : "");
        data.put("difficulty", drill.getDifficulty() != null ? drill.getDifficulty() : "medium");
        data.put("completed", drill.isCompleted());
        data.put("completedAt", drill.getCompletedAt());
        data.put("dysarthriaScore", drill.getDysarthriaScore() != null ? drill.getDysarthriaScore() : 0.0);
        data.put("dysarthriaPrediction", drill.getDysarthriaPrediction() != null ? drill.getDysarthriaPrediction() : "");
        data.put("assignedAt", drill.getAssignedAt() > 0 ? drill.getAssignedAt() : System.currentTimeMillis());
        data.put("dueDate", drill.getDueDate() != null ? drill.getDueDate() : "");
        data.put("targetWords", drill.getTargetWords() != null ? drill.getTargetWords() : "");
        data.put("transcription", drill.getTranscription() != null ? drill.getTranscription() : "");
        data.put("torgoUtteranceId", drill.getTorgoUtteranceId() != null ? drill.getTorgoUtteranceId() : "");

        Map<String, Object> multi = new HashMap<>();
        multi.put(PATH_ASSIGNED_DRILLS + "/" + id, data);
        multi.put(PATH_PATIENT_DRILL_INDEX + "/" + pid + "/" + id, true);
        rootRef.updateChildren(multi)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Assigned drill saved + index: " + id);
                        if (onSuccess != null) onSuccess.run();
                    } else Log.e(TAG, "Failed to save assigned drill", task.getException());
                });
    }

    /** Patient's drill id list (each child key = assigned_drills key). */
    public static DatabaseReference getPatientDrillIndexRef(String patientId) {
        String p = patientId != null ? patientId.trim() : "";
        return rootRef.child(PATH_PATIENT_DRILL_INDEX).child(p);
    }

    public static Query getAssignedDrillsByPatient(String patientId) {
        return rootRef.child(PATH_ASSIGNED_DRILLS).orderByChild("patientId").equalTo(patientId);
    }

    public static void updateAssignedDrillCompleted(String assignedDrillId, boolean completed, Double dysarthriaScore, String dysarthriaPrediction) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("completed", completed);
        updates.put("completedAt", completed ? System.currentTimeMillis() : 0L);
        if (dysarthriaScore != null) updates.put("dysarthriaScore", dysarthriaScore);
        if (dysarthriaPrediction != null) updates.put("dysarthriaPrediction", dysarthriaPrediction);
        rootRef.child(PATH_ASSIGNED_DRILLS).child(assignedDrillId).updateChildren(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Assigned drill updated: " + assignedDrillId);
                    else Log.e(TAG, "Failed to update assigned drill", task.getException());
                });
    }

    /** Update drill with voice analysis result (accuracy 0-100). */
    public static void updateAssignedDrillWithAnalysis(String assignedDrillId, double accuracyPercent, String prediction) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("dysarthriaScore", accuracyPercent / 100.0);
        updates.put("dysarthriaPrediction", prediction != null ? prediction : "");
        updates.put("accuracyPercent", accuracyPercent);
        rootRef.child(PATH_ASSIGNED_DRILLS).child(assignedDrillId).updateChildren(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Drill analysis updated: " + assignedDrillId);
                    else Log.e(TAG, "Failed to update drill analysis", task.getException());
                });
    }

    /** Get/set patient speech level (easy|medium|hard) for drill progression. */
    public static DatabaseReference getPatientSpeechLevelRef(String patientId) {
        return rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("speechLevel");
    }

    public static void setPatientSpeechLevel(String patientId, String level, Runnable onSuccess) {
        rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("speechLevel").setValue(level != null ? level : "easy")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && onSuccess != null) onSuccess.run();
                });
    }

    public static DatabaseReference getAssignedDrillRef(String assignedDrillId) {
        return rootRef.child(PATH_ASSIGNED_DRILLS).child(assignedDrillId);
    }

    // ==================== PATIENT SESSIONS (count + records) ====================

    /** Storage path for patient recordings: recordings/{patientId}/{filename}.wav */
    private static final String STORAGE_RECORDINGS = "recordings";

    /** Upload WAV bytes to Firebase Storage and return download URL via onUrlReady (or null on failure). Content type set for playback. */
    public static void uploadRecording(String patientId, String filename, byte[] wavBytes, Consumer<String> onUrlReady, Consumer<Exception> onError) {
        if (patientId == null || filename == null || wavBytes == null || wavBytes.length == 0) {
            if (onError != null) onError.accept(new IllegalArgumentException("patientId, filename and wavBytes required"));
            return;
        }
        StorageReference ref = FirebaseStorage.getInstance().getReference()
                .child(STORAGE_RECORDINGS).child(patientId).child(filename);
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType("audio/wav")
                .build();
        UploadTask task = ref.putBytes(wavBytes, metadata);
        task.addOnSuccessListener(t -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
            if (onUrlReady != null) onUrlReady.accept(uri.toString());
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get download URL", e);
            if (onError != null) onError.accept(e);
        })).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to upload recording", e);
            if (onError != null) onError.accept(e);
        });
    }

    public static void addPatientSessionRecord(String patientId, PatientSessionRecord record, Runnable onSuccess) {
        if (patientId == null || record == null) return;
        String sessionId = rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("sessions").push().getKey();
        if (sessionId == null) return;
        record.setSessionId(sessionId);
        record.setPatientId(patientId);
        record.setDateMs(record.getDateMs() > 0 ? record.getDateMs() : System.currentTimeMillis());
        if (record.getDate() == null || record.getDate().isEmpty())
            record.setDate(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("patientId", patientId);
        data.put("therapistId", record.getTherapistId() != null ? record.getTherapistId() : "");
        data.put("assignedDrillId", record.getAssignedDrillId() != null ? record.getAssignedDrillId() : "");
        data.put("drillTitle", record.getDrillTitle() != null ? record.getDrillTitle() : "");
        data.put("dateMs", record.getDateMs());
        data.put("date", record.getDate());
        data.put("durationSeconds", record.getDurationSeconds());
        data.put("dysarthriaScore", record.getDysarthriaScore() != null ? record.getDysarthriaScore() : 0.0);
        data.put("dysarthriaPrediction", record.getDysarthriaPrediction() != null ? record.getDysarthriaPrediction() : "");
        data.put("speechTranscription", record.getSpeechTranscription() != null ? record.getSpeechTranscription() : "");
        data.put("speechPhonemes", record.getSpeechPhonemes() != null ? record.getSpeechPhonemes() : "");
        data.put("note", record.getNote() != null ? record.getNote() : "");
        data.put("recordingUrl", record.getRecordingUrl() != null ? record.getRecordingUrl() : "");

        rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("sessions").child(sessionId).setValue(data)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "Failed to add session record", task.getException());
                        if (onSuccess != null) onSuccess.run();
                        return;
                    }
                    rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("sessionCount").runTransaction(new Transaction.Handler() {
                        @NonNull
                        @Override
                        public Transaction.Result doTransaction(@NonNull MutableData mutableData) {
                            long count = mutableData.getValue() instanceof Number ? ((Number) mutableData.getValue()).longValue() : 0;
                            mutableData.setValue(count + 1);
                            return Transaction.success(mutableData);
                        }
                        @Override
                        public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                            if (error == null && committed) Log.d(TAG, "Session count incremented for " + patientId);
                            if (onSuccess != null) onSuccess.run();
                        }
                    });
                });
    }

    public static DatabaseReference getPatientSessionCountRef(String patientId) {
        return rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("sessionCount");
    }

    public static Query getPatientSessionRecords(String patientId) {
        return rootRef.child(PATH_PATIENT_SESSIONS).child(patientId).child("sessions").orderByChild("dateMs");
    }

    /** Most recent N session records (by dateMs). */
    public static Query getPatientSessionRecordsRecent(String patientId, int lastN) {
        int n = Math.max(1, Math.min(lastN, 100));
        return getPatientSessionRecords(patientId).limitToLast(n);
    }

    public static Query getProgressReportsByPatientId(String patientId) {
        return rootRef.child(PATH_PROGRESS_REPORTS).orderByChild("patient_id").equalTo(patientId);
    }

    // ==================== NOTES ====================

    public static void savePatientNote(int noteId, int patientId, int therapistId, String noteText) {
        Map<String, Object> noteData = new HashMap<>();
        noteData.put("noteId", noteId);
        noteData.put("patient_id", patientId);
        noteData.put("therapist_id", therapistId);
        noteData.put("noteText", noteText);
        noteData.put("noteDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        noteData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_NOTES).child(String.valueOf(noteId)).setValue(noteData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Note saved: " + noteId);
                    else Log.e(TAG, "Failed to save note", task.getException());
                });
    }

    public static Query getPatientNotes(int patientId) {
        return rootRef.child(PATH_NOTES).orderByChild("patient_id").equalTo(patientId);
    }

    public static Query getPatientNotes(String patientId) {
        return rootRef.child(PATH_NOTES).orderByChild("patient_id").equalTo(patientId);
    }

    // ==================== TAGS ====================

    public static void savePatientTag(int tagId, int patientId, String tagName) {
        Map<String, Object> tagData = new HashMap<>();
        tagData.put("tagId", tagId);
        tagData.put("patient_id", patientId);
        tagData.put("tagName", tagName);
        tagData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_TAGS).child(String.valueOf(tagId)).setValue(tagData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Tag saved: " + tagId);
                    else Log.e(TAG, "Failed to save tag", task.getException());
                });
    }

    public static Query getPatientTags(int patientId) {
        return rootRef.child(PATH_TAGS).orderByChild("patient_id").equalTo(patientId);
    }

    public static void deletePatientTag(int tagId) {
        rootRef.child(PATH_TAGS).child(String.valueOf(tagId)).removeValue()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Tag deleted: " + tagId);
                    else Log.e(TAG, "Failed to delete tag", task.getException());
                });
    }

    // ==================== TREATMENT PLANS ====================

    public static void saveTreatmentPlan(int planId, int patientId, int therapistId,
                                         String planName, String goals, String targetDate, String status) {
        Map<String, Object> planData = new HashMap<>();
        planData.put("planId", planId);
        planData.put("patient_id", patientId);
        planData.put("therapist_id", therapistId);
        planData.put("planName", planName);
        planData.put("goals", goals);
        planData.put("targetDate", targetDate);
        planData.put("status", status != null ? status : "active");
        planData.put("createdAt", System.currentTimeMillis());

        rootRef.child(PATH_TREATMENT_PLANS).child(String.valueOf(planId)).setValue(planData)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) Log.d(TAG, "Treatment plan saved: " + planId);
                    else Log.e(TAG, "Failed to save treatment plan", task.getException());
                });
    }

    public static Query getTreatmentPlans(int patientId) {
        return rootRef.child(PATH_TREATMENT_PLANS).orderByChild("patient_id").equalTo(patientId);
    }

    public static Query getTreatmentPlans(String patientId) {
        return rootRef.child(PATH_TREATMENT_PLANS).orderByChild("patient_id").equalTo(patientId);
    }

    // ==================== UTILITY ====================

    public static String generateId() {
        return rootRef.push().getKey();
    }

    public static int generateUniqueId() {
        return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }

    public static int generateUniqueId(String path) {
        return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }

    // ==================== PHONEME PROFILE & A/B TESTING ====================

    /** Firebase path for phoneme profiles: phoneme_profiles/{patientId} */
    public static final String PATH_PHONEME_PROFILES = "phoneme_profiles";

    /** Firebase path for A/B test assignments: ab_test_assignments/{patientId} */
    public static final String PATH_AB_ASSIGNMENTS = "ab_test_assignments";

    /** Get reference to patient's phoneme profile. */
    public static DatabaseReference getPhonemeProfileRef(String patientId) {
        return rootRef.child(PATH_PHONEME_PROFILES).child(patientId);
    }

    /** Save/update patient's weak phoneme profile from API analysis. */
    public static void savePhonemeProfile(String patientId, List<Map<String, Object>> weakPhonemes, Runnable onSuccess) {
        if (patientId == null || weakPhonemes == null) return;
        Map<String, Object> profile = new HashMap<>();
        profile.put("patientId", patientId);
        profile.put("updatedAt", System.currentTimeMillis());
        profile.put("weakPhonemes", weakPhonemes);

        rootRef.child(PATH_PHONEME_PROFILES).child(patientId).setValue(profile)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Phoneme profile saved for " + patientId);
                        if (onSuccess != null) onSuccess.run();
                    } else {
                        Log.e(TAG, "Failed to save phoneme profile", task.getException());
                    }
                });
    }

    /** Get or assign A/B test group for patient. Returns via callback: "A" or "B". */
    public static void getOrAssignAbGroup(String patientId, Consumer<String> callback) {
        if (patientId == null || callback == null) return;
        rootRef.child(PATH_AB_ASSIGNMENTS).child(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists() && snapshot.getValue() != null) {
                    String group = String.valueOf(snapshot.getValue()).trim().toUpperCase();
                    if (group.equals("A") || group.equals("B")) {
                        callback.accept(group);
                        return;
                    }
                }
                // Assign randomly: 50/50 split
                String newGroup = (System.currentTimeMillis() % 2 == 0) ? "A" : "B";
                rootRef.child(PATH_AB_ASSIGNMENTS).child(patientId).setValue(newGroup);
                callback.accept(newGroup);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.accept("A"); // default to personalized on error
            }
        });
    }

    /** Get reference to patient's A/B group assignment. */
    public static DatabaseReference getAbGroupRef(String patientId) {
        return rootRef.child(PATH_AB_ASSIGNMENTS).child(patientId);
    }
}
