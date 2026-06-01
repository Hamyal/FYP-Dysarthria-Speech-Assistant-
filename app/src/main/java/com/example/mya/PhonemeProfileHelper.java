package com.example.mya;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles phoneme-level personalization:
 * 1. Fetches patient session history from Firebase
 * 2. Sends to /phoneme/profile API to identify weak phonemes
 * 3. Requests /phoneme/drills for targeted drill generation
 * 4. Stores phoneme profile in Firebase for offline access
 * 5. Logs A/B test events via /ab/log
 */
public final class PhonemeProfileHelper {

    private static final String TAG = "PhonemeProfileHelper";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PhonemeProfileCallback {
        void onSuccess(List<WeakPhoneme> weakPhonemes);
        void onError(String message);
    }

    public interface DrillsCallback {
        void onSuccess(List<PersonalizedDrill> drills, String abGroup, boolean personalized);
        void onError(String message);
    }

    /** Represents a weak phoneme identified from patient history. */
    public static class WeakPhoneme {
        public final String phoneme;
        public final String category;
        public final double avgAccuracy;
        public final int occurrences;
        public final List<String> sampleWords;

        public WeakPhoneme(String phoneme, String category, double avgAccuracy, int occurrences, List<String> sampleWords) {
            this.phoneme = phoneme;
            this.category = category;
            this.avgAccuracy = avgAccuracy;
            this.occurrences = occurrences;
            this.sampleWords = sampleWords != null ? sampleWords : new ArrayList<>();
        }
    }

    /** Represents a drill generated targeting weak phonemes. */
    public static class PersonalizedDrill {
        public final String targetText;
        public final List<String> targetPhonemes;
        public final String difficulty;
        public final String rationale;

        public PersonalizedDrill(String targetText, List<String> targetPhonemes, String difficulty, String rationale) {
            this.targetText = targetText;
            this.targetPhonemes = targetPhonemes != null ? targetPhonemes : new ArrayList<>();
            this.difficulty = difficulty;
            this.rationale = rationale;
        }
    }

    private PhonemeProfileHelper() {}

    /**
     * Analyze patient's session history and identify weak phonemes.
     * Fetches last 20 sessions from Firebase, sends to API, stores result.
     */
    public static void analyzePatientPhonemes(@NonNull String patientId, @NonNull PhonemeProfileCallback callback) {
        // Fetch recent sessions from Firebase
        FirebaseHelper.getPatientSessionRecordsRecent(patientId, 20)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<Map<String, String>> sessions = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            Map<String, String> session = new HashMap<>();
                            Object target = child.child("drillTitle").getValue();
                            Object transcription = child.child("speechTranscription").getValue();
                            // Use targetWords from assigned drill if available
                            Object targetWords = child.child("targetWords").getValue();
                            String targetText = targetWords != null ? targetWords.toString() :
                                    (target != null ? target.toString() : "");
                            String spokenText = transcription != null ? transcription.toString() : "";

                            if (!targetText.isEmpty() && !spokenText.isEmpty()) {
                                session.put("target_text", targetText);
                                session.put("transcription", spokenText);
                                sessions.add(session);
                            }
                        }

                        if (sessions.isEmpty()) {
                            mainHandler.post(() -> callback.onError("No sessions with transcription data found"));
                            return;
                        }

                        // Call API in background
                        new Thread(() -> callPhonemeProfileApi(patientId, sessions, callback)).start();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        mainHandler.post(() -> callback.onError("Firebase error: " + error.getMessage()));
                    }
                });
    }

    private static void callPhonemeProfileApi(String patientId, List<Map<String, String>> sessions, PhonemeProfileCallback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("patient_id", patientId);
            JSONArray sessionsArray = new JSONArray();
            for (Map<String, String> s : sessions) {
                JSONObject sessionObj = new JSONObject();
                sessionObj.put("target_text", s.get("target_text"));
                sessionObj.put("transcription", s.get("transcription"));
                sessionsArray.put(sessionObj);
            }
            body.put("sessions", sessionsArray);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(BuildConfig.VOCALAID_API_URL + "/phoneme/profile")
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                if (result.has("error")) {
                    String error = result.getString("error");
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }

                JSONArray weakArray = result.optJSONArray("weak_phonemes");
                List<WeakPhoneme> weakPhonemes = new ArrayList<>();
                List<Map<String, Object>> firebaseData = new ArrayList<>();

                if (weakArray != null) {
                    for (int i = 0; i < weakArray.length(); i++) {
                        JSONObject wp = weakArray.getJSONObject(i);
                        List<String> sampleWords = new ArrayList<>();
                        JSONArray wordsArr = wp.optJSONArray("sample_words");
                        if (wordsArr != null) {
                            for (int j = 0; j < wordsArr.length(); j++) {
                                sampleWords.add(wordsArr.getString(j));
                            }
                        }
                        weakPhonemes.add(new WeakPhoneme(
                                wp.getString("phoneme"),
                                wp.optString("category", "other"),
                                wp.optDouble("avg_accuracy", 0),
                                wp.optInt("occurrences", 0),
                                sampleWords
                        ));

                        // Prepare Firebase-friendly map
                        Map<String, Object> fbMap = new HashMap<>();
                        fbMap.put("phoneme", wp.getString("phoneme"));
                        fbMap.put("category", wp.optString("category", "other"));
                        fbMap.put("avgAccuracy", wp.optDouble("avg_accuracy", 0));
                        fbMap.put("occurrences", wp.optInt("occurrences", 0));
                        fbMap.put("sampleWords", sampleWords);
                        firebaseData.add(fbMap);
                    }
                }

                // Save to Firebase
                FirebaseHelper.savePhonemeProfile(patientId, firebaseData, null);

                mainHandler.post(() -> callback.onSuccess(weakPhonemes));
            }
        } catch (Exception e) {
            Log.e(TAG, "Phoneme profile API error", e);
            mainHandler.post(() -> callback.onError("API error: " + e.getMessage()));
        }
    }

    /**
     * Request personalized drills targeting weak phonemes.
     * Uses A/B test group assignment from Firebase.
     */
    public static void getPersonalizedDrills(@NonNull String patientId, @NonNull String difficulty,
                                             int count, @NonNull DrillsCallback callback) {
        // Get A/B group first
        FirebaseHelper.getOrAssignAbGroup(patientId, abGroup -> {
            // Fetch sessions for analysis
            FirebaseHelper.getPatientSessionRecordsRecent(patientId, 20)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            List<Map<String, String>> sessions = new ArrayList<>();
                            for (DataSnapshot child : snapshot.getChildren()) {
                                Object target = child.child("drillTitle").getValue();
                                Object transcription = child.child("speechTranscription").getValue();
                                Object targetWords = child.child("targetWords").getValue();
                                String targetText = targetWords != null ? targetWords.toString() :
                                        (target != null ? target.toString() : "");
                                String spokenText = transcription != null ? transcription.toString() : "";
                                if (!targetText.isEmpty() && !spokenText.isEmpty()) {
                                    Map<String, String> session = new HashMap<>();
                                    session.put("target_text", targetText);
                                    session.put("transcription", spokenText);
                                    sessions.add(session);
                                }
                            }

                            new Thread(() -> callDrillsApi(patientId, sessions, difficulty, count, abGroup, callback)).start();
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            mainHandler.post(() -> callback.onError("Firebase error: " + error.getMessage()));
                        }
                    });
        });
    }

    private static void callDrillsApi(String patientId, List<Map<String, String>> sessions,
                                       String difficulty, int count, String abGroup, DrillsCallback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("patient_id", patientId);
            body.put("difficulty", difficulty);
            body.put("count", count);
            body.put("ab_group", abGroup);

            JSONArray sessionsArray = new JSONArray();
            for (Map<String, String> s : sessions) {
                JSONObject sessionObj = new JSONObject();
                sessionObj.put("target_text", s.get("target_text"));
                sessionObj.put("transcription", s.get("transcription"));
                sessionsArray.put(sessionObj);
            }
            body.put("sessions", sessionsArray);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(BuildConfig.VOCALAID_API_URL + "/phoneme/drills")
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject result = new JSONObject(responseBody);

                if (result.has("error")) {
                    mainHandler.post(() -> callback.onError(result.optString("error")));
                    return;
                }

                boolean personalized = result.optBoolean("personalized", false);
                String group = result.optString("ab_group", abGroup);
                JSONArray drillsArray = result.optJSONArray("drills");
                List<PersonalizedDrill> drills = new ArrayList<>();

                if (drillsArray != null) {
                    for (int i = 0; i < drillsArray.length(); i++) {
                        JSONObject d = drillsArray.getJSONObject(i);
                        List<String> phonemes = new ArrayList<>();
                        JSONArray phArr = d.optJSONArray("target_phonemes");
                        if (phArr != null) {
                            for (int j = 0; j < phArr.length(); j++) {
                                phonemes.add(phArr.getString(j));
                            }
                        }
                        drills.add(new PersonalizedDrill(
                                d.optString("target_text", ""),
                                phonemes,
                                d.optString("difficulty", difficulty),
                                d.optString("rationale", "")
                        ));
                    }
                }

                mainHandler.post(() -> callback.onSuccess(drills, group, personalized));
            }
        } catch (Exception e) {
            Log.e(TAG, "Drills API error", e);
            mainHandler.post(() -> callback.onError("API error: " + e.getMessage()));
        }
    }

    /**
     * Log an A/B test event (drill completion, session result) for later analysis.
     */
    public static void logAbEvent(@NonNull String patientId, @NonNull String abGroup,
                                   @NonNull String event, double accuracy, List<String> phonemesTargeted) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("patient_id", patientId);
                body.put("ab_group", abGroup);
                body.put("event", event);
                body.put("accuracy", accuracy);
                if (phonemesTargeted != null) {
                    body.put("phonemes_targeted", new JSONArray(phonemesTargeted));
                }

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(BuildConfig.VOCALAID_API_URL + "/ab/log")
                        .post(RequestBody.create(body.toString(), JSON_TYPE))
                        .build();

                client.newCall(request).execute().close();
            } catch (Exception e) {
                Log.e(TAG, "A/B log failed (non-critical)", e);
            }
        }).start();
    }
}
