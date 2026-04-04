# VocalAid – Verification Checklist

This document summarizes what was verified so that **everything progresses correctly** (auth, recording, storage, playback, API).

---

## 1. Code & config verified

| Area | Status | Notes |
|------|--------|------|
| **AndroidManifest** | OK | All activities declared; `INTERNET`, `RECORD_AUDIO`, `CAMERA`, storage permissions present; `FileProvider` for camera. |
| **Firebase Storage rules** | OK | `recordings/{patientId}/{filename}` and `profile_photos/{filename}` allow read/write for authenticated users. |
| **Recording (RecordDrillActivity)** | OK | Uses `AudioRecord.read(byte[], ...)` for raw LE PCM; waits for `RECORDSTATE_RECORDING`; builds 44-byte WAV header; uploads with `audio/wav` content type. |
| **Upload (FirebaseHelper)** | OK | `uploadRecording()` sets `StorageMetadata` with `setContentType("audio/wav")`; path `recordings/{patientId}/{filename}`. |
| **Session record** | OK | `addPatientSessionRecord()` saves `recordingUrl`; session data structure matches what UI and playback expect. |
| **Playback (therapist & patient)** | OK | Download via OkHttp with 15s connect / 30s read timeouts; `prepareAsync()` + `setOnPreparedListener` to start; `isDestroyed()` checks; temp file deleted after play. |
| **API URL** | OK | `BuildConfig.VOCALAID_API_URL` used for `/analyze` and `/ai/chat` (default `http://10.0.2.2:5001` for emulator). |
| **Lint** | OK | No linter errors on `RecordDrillActivity`, `PatientProgressActivity`, `SessionsSummaryActivity`, `FirebaseHelper`, home activities. |

---

## 2. Unit tests added

In **`app/src/test/.../ExampleUnitTest.java`**:

- `formatResultForDisplay_healthyScore_showsClearAndPercent`
- `formatResultForDisplay_dysarthric_showsModerate`
- `formatResultForDisplay_nullPrediction_scoreOnly`
- `formatResultForDisplay_label0_normalized`
- `wavHeaderSize_is44Bytes`

**Run unit tests (with Java/JAVA_HOME set):**

```bash
cd c:\Users\hamya\AndroidStudioProjects\MyA
.\gradlew test
```

---

## 3. What you should run manually

1. **Build the app**  
   In Android Studio: **Build → Make Project** (or run `.\gradlew assembleDebug` in a terminal where Java is available).

2. **VocalAid API**  
   From project root:
   ```bash
   cd vocalaid_api
   python app.py
   ```
   Then open `http://localhost:5001/health` – you should see `{"status":"ok","model_loaded":true}` (or `false` if the model path is not set).

3. **On device/emulator**
   - **Login** as patient and as therapist.
   - **Patient:** Record a drill (Assigned or Personal) → confirm analysis result and “Listen” plays the **local** recording.
   - **Patient:** After saving, open **Sessions** (drawer or nav) → tap **Listen to recording** on the new session → confirm it loads and plays (no silent audio, no stuck “Loading”).
   - **Therapist:** Open a patient → **View progress** → tap **Listen to recording** on a session that has a recording → confirm it loads and plays (timeouts and `prepareAsync` should prevent infinite “Loading”).

4. **Firebase**
   - In Firebase Console → Storage, check that new recordings appear under `recordings/{patientId}/session_*.wav` and that downloading a file and playing it in an external player has sound (not silent).

---

## 4. If something still fails

- **Silent audio:** Ensure device has **microphone permission** and that no other app is exclusively using the mic. New recordings use raw `byte[]` from `AudioRecord`; only new files will reflect the fix.
- **Therapist “Loading” forever:** Check Logcat for tag `PatientProgress` and `MediaPlayer error`; confirm the session’s `recordingUrl` in Firebase Realtime DB is a valid HTTPS URL.
- **API not reached:** Emulator uses `10.0.2.2:5001`; physical device needs your PC’s LAN IP (e.g. `http://192.168.1.x:5001`) and the API must be running and reachable.

---

## 5. Summary

- **Code paths** for recording, upload, session save, and playback are consistent and guarded (timeouts, `isDestroyed()`, content type).
- **Unit tests** cover session display formatting and WAV header size.
- **Manual steps** above confirm end-to-end: record → analyze → save → upload → listen (patient and therapist) and that audio is not silent and loading does not hang.
