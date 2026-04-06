package com.example.mya;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * Record voice for assigned drill, send to dysarthria API, show accuracy and update Firebase.
 */
public class RecordDrillActivity extends AppCompatActivity {

    private static final String TAG = "RecordDrillActivity";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int REQUEST_RECORD_AUDIO = 101;

    private AssignedDrill drill;
    private String patientId;
    private boolean isPersonalized;
    private TextView wordsText;
    private TextView statusText;
    private TextView transcriptionSectionLabel;
    private TextView transcriptionResult;
    private TextView phonemesSectionLabel;
    private TextView phonemesResult;
    private MaterialButton btnRecord;
    private MaterialButton btnListen;
    private MaterialButton btnDone;
    private ProgressBar progress;
    private boolean isRecording;
    private AudioRecord audioRecord;
    private String lastRecordingPath;
    /** Last full WAV bytes (voice); used if analysis fails and for duration. */
    private byte[] lastWavBytes;
    /** Set after upload to Firebase Storage (before or independent of API result). */
    private String pendingStorageUrl;
    /** Copy for playback when local file is missing (personalized flow). */
    private String lastFirebaseRecordingUrl;
    private MediaPlayer mediaPlayer;
    /** Mic capture only — never block this with HTTP analyze. */
    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
    /** Dysarthria API calls — separate from recording to avoid queued “stuck analyzing”. */
    private final ExecutorService analyzeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record_drill);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        drill = (AssignedDrill) getIntent().getSerializableExtra("drill");
        patientId = getIntent().getStringExtra("patient_id");
        isPersonalized = getIntent().getBooleanExtra("is_personalized", false)
                || (drill != null && (drill.getAssignedDrillId() == null || drill.getAssignedDrillId().isEmpty()));
        if (drill == null || patientId == null) {
            Toast.makeText(this, "Missing drill or patient.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        wordsText = findViewById(R.id.wordsToSpeak);
        statusText = findViewById(R.id.statusText);
        transcriptionSectionLabel = findViewById(R.id.transcriptionSectionLabel);
        transcriptionResult = findViewById(R.id.transcriptionResult);
        phonemesSectionLabel = findViewById(R.id.phonemesSectionLabel);
        phonemesResult = findViewById(R.id.phonemesResult);
        btnRecord = findViewById(R.id.btnRecord);
        btnListen = findViewById(R.id.btnListen);
        btnDone = findViewById(R.id.btnDone);
        progress = findViewById(R.id.progress);
        btnListen.setVisibility(View.GONE);
        btnDone.setVisibility(View.GONE);
        btnListen.setOnClickListener(v -> playSavedRecording());
        btnDone.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        String words = (drill.getTargetWords() != null && !drill.getTargetWords().isEmpty())
                ? drill.getTargetWords()
                : DrillWordProvider.getRandomWordsAsString(drill.getDifficulty(), 5, ", ");
        wordsText.setText(getString(R.string.speak_these_words) + "\n\n" + words);

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        checkPermission();
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.record_audio_permission_required, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * Open mic for speech. MIC / VOICE_COMMUNICATION first — VOICE_RECOGNITION often records silence
     * on many phones unless the system speech pipeline is active.
     */
    private AudioRecord createSpeechAudioRecord(int bufSize) {
        int[] sources = {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
        };
        for (int source : sources) {
            try {
                AudioRecord ar = new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufSize);
                if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Using AudioSource=" + source);
                    return ar;
                }
                ar.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioSource " + source + " failed", e);
            }
        }
        return null;
    }

    private void startRecording() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuf <= 0) {
            Toast.makeText(this, R.string.recording_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        // 16-bit mono: frame size 2 bytes — keep buffer length even (must be final for executor lambda)
        final int recordBufSize = (Math.max(minBuf * 2, minBuf + 2048) + 1) & ~1;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermission();
            return;
        }
        audioRecord = createSpeechAudioRecord(recordBufSize);
        if (audioRecord == null) {
            Toast.makeText(this, R.string.recording_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingStorageUrl = null;
        lastFirebaseRecordingUrl = null;
        lastWavBytes = null;
        clearResultPanels();
        audioRecord.startRecording();
        isRecording = true;
        btnRecord.setText(R.string.stop_recording);
        statusText.setText(R.string.recording_speak_now);

        recordExecutor.execute(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // read(short[]) + explicit LE bytes — read(byte[]) is unreliable for PCM16 on some devices (silent WAVs)
            int shortBufLen = recordBufSize / 2;
            short[] shortBuf = new short[shortBufLen];
            for (int i = 0; i < 50 && audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING; i++) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
            long start = System.currentTimeMillis();
            while (isRecording && (System.currentTimeMillis() - start) < 10000) {
                int read = audioRecord.read(shortBuf, 0, shortBufLen);
                if (read > 0) {
                    for (int i = 0; i < read; i++) {
                        short s = shortBuf[i];
                        baos.write(s & 0xFF);
                        baos.write((s >>> 8) & 0xFF);
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error code: " + read);
                }
            }
            byte[] pcm = baos.toByteArray();
            if (audioRecord != null) {
                try { audioRecord.stop(); audioRecord.release(); } catch (Exception e) {}
                audioRecord = null;
            }
            // ~0.1s minimum at 16 kHz mono 16-bit (3200 bytes PCM) — keep short clips but reject noise-only
            if (pcm.length > 3200) {
                try {
                    byte[] wav = addWavHeader(pcm, SAMPLE_RATE, 1);
                    String path = saveRecordingToFile(wav);
                    mainHandler.post(() -> {
                        lastWavBytes = wav;
                        lastRecordingPath = path;
                        if (path != null) {
                            btnListen.setVisibility(View.VISIBLE);
                            Toast.makeText(RecordDrillActivity.this, R.string.recording_saved, Toast.LENGTH_SHORT).show();
                        }
                        uploadVoiceToStorageThenAnalyze(wav);
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create WAV", e);
                    mainHandler.post(() -> onAnalysisError(e.getMessage()));
                }
            } else {
                mainHandler.post(() -> {
                    isRecording = false;
                    btnRecord.setText(R.string.record_and_analyze);
                    statusText.setText(R.string.recording_too_short);
                });
            }
        });
    }

    private void stopRecording() {
        isRecording = false;
        btnRecord.setText(R.string.record_and_analyze);
        statusText.setText(R.string.processing);
    }

    /** Save WAV bytes to app storage; returns file path or null. */
    private String saveRecordingToFile(byte[] wavData) {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (dir == null) dir = getFilesDir();
            if (dir == null) return null;
            File file = new File(dir, "last_recording.wav");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(wavData);
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save recording", e);
            return null;
        }
    }

    /** Build a valid WAV file (little-endian header). */
    private byte[] addWavHeader(byte[] pcm, int sampleRate, int channels) throws IOException {
        int dataLen = pcm.length;
        int byteRate = sampleRate * channels * 2;
        int blockAlign = channels * 2;
        int headerSize = 44;
        ByteBuffer buf = ByteBuffer.allocate(headerSize + dataLen);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(headerSize - 8 + dataLen);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(dataLen);
        buf.put(pcm);
        return buf.array();
    }

    /** Minimum WAV file size to store (header + a few ms of voice). */
    private static final int MIN_WAV_STORAGE_BYTES = 800;

    /** Save voice to Firebase Storage first, then run dysarthria API (voice is always stored when upload succeeds). */
    private void uploadVoiceToStorageThenAnalyze(byte[] wavData) {
        progress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.analyzing);
        btnRecord.setEnabled(false);
        pendingStorageUrl = null;

        if (wavData == null || wavData.length < MIN_WAV_STORAGE_BYTES) {
            mainHandler.post(() -> onAnalysisError("recording_too_short"));
            return;
        }

        String filename = "session_" + System.currentTimeMillis() + ".wav";
        FirebaseHelper.uploadRecording(patientId, filename, wavData,
                url -> {
                    pendingStorageUrl = url;
                    runAnalyzeOnBackgroundThread(wavData);
                },
                err -> {
                    Log.e(TAG, "Voice upload to Storage failed", err);
                    pendingStorageUrl = null;
                    runAnalyzeOnBackgroundThread(wavData);
                });
    }

    private void clearResultPanels() {
        transcriptionSectionLabel.setVisibility(View.GONE);
        transcriptionResult.setVisibility(View.GONE);
        phonemesSectionLabel.setVisibility(View.GONE);
        phonemesResult.setVisibility(View.GONE);
        btnDone.setVisibility(View.GONE);
        btnListen.setVisibility(View.GONE);
    }

    private void runAnalyzeOnBackgroundThread(byte[] wavData) {
        analyzeExecutor.execute(() -> {
            try {
                String url = BuildConfig.VOCALAID_API_URL + "/analyze";
                OkHttpClient client = new OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build();
                MultipartBody.Builder mp = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("audio", "recording.wav",
                                RequestBody.create(MediaType.parse("audio/wav"), wavData));
                if (patientId != null && !patientId.isEmpty())
                    mp.addFormDataPart("patient_id", patientId);
                if (isPersonalized && drill.getTargetWords() != null && !drill.getTargetWords().trim().isEmpty())
                    mp.addFormDataPart("target_text", drill.getTargetWords().trim());
                RequestBody body = mp.build();
                Request req = new Request.Builder().url(url).post(body).build();
                Response resp = client.newCall(req).execute();
                String json = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) {
                    String errMsg = json;
                    try {
                        JSONObject eo = new JSONObject(json);
                        if (eo.has("error")) errMsg = eo.optString("error", errMsg);
                    } catch (Exception ignored) {}
                    final String display = "HTTP " + resp.code() + (errMsg.isEmpty() ? "" : ": " + errMsg);
                    mainHandler.post(() -> onAnalysisError(display));
                    return;
                }
                JSONObject obj = new JSONObject(json);
                if (obj.has("error")) {
                    mainHandler.post(() -> onAnalysisError(obj.optString("error")));
                    return;
                }
                double accuracy = obj.optDouble("accuracy", 0);
                String prediction = obj.optString("prediction", "");
                String transcription = obj.optString("transcription", "");
                String phonemes = obj.optString("phonemes", "");
                mainHandler.post(() -> onAnalysisResult(accuracy, prediction, wavData, transcription, phonemes));
            } catch (Exception e) {
                Log.e(TAG, "API error", e);
                String msg = e.getMessage();
                boolean isConnectionError = msg != null && (msg.contains("connect") || msg.contains("timeout") || msg.contains("Connection"));
                mainHandler.post(() -> onAnalysisError(isConnectionError ? "connection_failed" : msg));
            }
        });
    }

    private static int wavDurationSeconds(byte[] wav) {
        if (wav == null || wav.length <= 44) return 0;
        int pcmBytes = wav.length - 44;
        return Math.max(1, pcmBytes / 2 / SAMPLE_RATE);
    }

    private void onAnalysisResult(double accuracy, String prediction, byte[] wavBytesFromApi, String transcription, String phonemes) {
        progress.setVisibility(View.GONE);
        btnRecord.setEnabled(true);
        if (isPersonalized) {
            statusText.setText(getString(R.string.personalized_match_result, String.format("%.1f", accuracy)));
        } else {
            statusText.setText(getString(R.string.accuracy_result, String.format("%.1f", accuracy)));
        }

        lastFirebaseRecordingUrl = (pendingStorageUrl != null && !pendingStorageUrl.isEmpty()) ? pendingStorageUrl : "";
        if (isPersonalized) {
            if (transcription != null && !transcription.trim().isEmpty()) {
                transcriptionSectionLabel.setVisibility(View.VISIBLE);
                transcriptionResult.setVisibility(View.VISIBLE);
                transcriptionResult.setText(transcription.trim());
            } else {
                transcriptionSectionLabel.setVisibility(View.GONE);
                transcriptionResult.setVisibility(View.GONE);
            }
            if (phonemes != null && !phonemes.trim().isEmpty()) {
                phonemesSectionLabel.setVisibility(View.VISIBLE);
                phonemesResult.setVisibility(View.VISIBLE);
                phonemesResult.setText(phonemes.trim());
            } else {
                phonemesSectionLabel.setVisibility(View.GONE);
                phonemesResult.setVisibility(View.GONE);
            }
            btnListen.setVisibility(View.VISIBLE);
        }

        if (!isPersonalized && drill.getAssignedDrillId() != null && !drill.getAssignedDrillId().isEmpty()) {
            FirebaseHelper.updateAssignedDrillWithAnalysis(drill.getAssignedDrillId(), accuracy, prediction);
        }

        FirebaseHelper.getPatientSpeechLevelRef(patientId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String currentLevel = snapshot.getValue(String.class);
                if (currentLevel == null) currentLevel = "easy";
                String next = DrillWordProvider.computeNextLevel(currentLevel, accuracy);
                if (!next.equals(currentLevel)) {
                    FirebaseHelper.setPatientSpeechLevel(patientId, next, null);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });

        PatientSessionRecord record = new PatientSessionRecord();
        record.setTherapistId(drill.getTherapistId() != null ? drill.getTherapistId() : "");
        record.setAssignedDrillId(drill.getAssignedDrillId() != null ? drill.getAssignedDrillId() : "");
        String title = drill.getTitle();
        if (isPersonalized && drill.getTargetWords() != null)
            title = "Personalized: " + drill.getTargetWords();
        record.setDrillTitle(title != null ? title : "Personalized");
        record.setDateMs(System.currentTimeMillis());
        record.setDate(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date()));
        record.setDysarthriaScore(accuracy / 100.0);
        record.setDysarthriaPrediction(prediction);
        if (transcription != null && !transcription.isEmpty())
            record.setSpeechTranscription(transcription);
        if (phonemes != null && !phonemes.isEmpty())
            record.setSpeechPhonemes(phonemes);
        byte[] wavForDuration = wavBytesFromApi != null ? wavBytesFromApi : lastWavBytes;
        record.setDurationSeconds(wavDurationSeconds(wavForDuration));

        Runnable saveAndFinish = () -> FirebaseHelper.addPatientSessionRecord(patientId, record, () -> mainHandler.post(() -> {
            Toast.makeText(this, R.string.analysis_saved, Toast.LENGTH_SHORT).show();
            if (isPersonalized) {
                btnDone.setVisibility(View.VISIBLE);
            } else {
                setResult(RESULT_OK);
                finish();
            }
        }));

        // Voice already in Storage from uploadVoiceToStorageThenAnalyze when pendingStorageUrl is set
        if (pendingStorageUrl != null && !pendingStorageUrl.isEmpty()) {
            record.setRecordingUrl(pendingStorageUrl);
            saveAndFinish.run();
            return;
        }
        // Fallback: upload now if early upload did not run or failed
        byte[] wavToUpload = wavBytesFromApi != null ? wavBytesFromApi : lastWavBytes;
        if (wavToUpload != null && wavToUpload.length >= MIN_WAV_STORAGE_BYTES) {
            String filename = "session_" + System.currentTimeMillis() + ".wav";
            FirebaseHelper.uploadRecording(patientId, filename, wavToUpload,
                    url -> {
                        record.setRecordingUrl(url);
                        saveAndFinish.run();
                    },
                    err -> saveAndFinish.run());
        } else if (lastRecordingPath != null) {
            analyzeExecutor.execute(() -> {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(new File(lastRecordingPath));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                    fis.close();
                    byte[] wavBytes = baos.toByteArray();
                    if (wavBytes.length < MIN_WAV_STORAGE_BYTES) {
                        mainHandler.post(saveAndFinish);
                        return;
                    }
                    String filename = "session_" + System.currentTimeMillis() + ".wav";
                    mainHandler.post(() -> FirebaseHelper.uploadRecording(patientId, filename, wavBytes,
                            url -> {
                                record.setRecordingUrl(url);
                                saveAndFinish.run();
                            },
                            err -> saveAndFinish.run()));
                } catch (Exception e) {
                    Log.e(TAG, "Read recording for upload", e);
                    mainHandler.post(saveAndFinish);
                }
            });
        } else {
            saveAndFinish.run();
        }
    }

    private void onAnalysisError(String msg) {
        progress.setVisibility(View.GONE);
        btnRecord.setEnabled(true);
        if ("recording_too_short".equals(msg)) {
            statusText.setText(R.string.recording_too_short);
            Toast.makeText(this, R.string.recording_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        String displayMsg = "connection_failed".equals(msg) ? getString(R.string.analysis_error_connection) : (msg != null ? msg : "Unknown");
        statusText.setText(getString(R.string.analysis_error, displayMsg));

        // If voice is already in Storage, still save a session so playback works from history
        if (pendingStorageUrl != null && !pendingStorageUrl.isEmpty()) {
            lastFirebaseRecordingUrl = pendingStorageUrl;
            btnListen.setVisibility(View.VISIBLE);
            PatientSessionRecord record = new PatientSessionRecord();
            record.setTherapistId(drill.getTherapistId() != null ? drill.getTherapistId() : "");
            record.setAssignedDrillId(drill.getAssignedDrillId() != null ? drill.getAssignedDrillId() : "");
            String title = drill.getTitle();
            if (isPersonalized && drill.getTargetWords() != null)
                title = "Personalized: " + drill.getTargetWords();
            record.setDrillTitle(title != null ? title : "Personalized");
            record.setDateMs(System.currentTimeMillis());
            record.setDate(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date()));
            record.setDurationSeconds(wavDurationSeconds(lastWavBytes));
            record.setDysarthriaScore(0.0);
            record.setDysarthriaPrediction("");
            record.setNote("Analysis unavailable");
            record.setRecordingUrl(pendingStorageUrl);
            FirebaseHelper.addPatientSessionRecord(patientId, record, () -> mainHandler.post(() -> {
                Toast.makeText(this, R.string.recording_saved_cloud_analysis_failed, Toast.LENGTH_LONG).show();
                if (isPersonalized) btnDone.setVisibility(View.VISIBLE);
            }));
            return;
        }
        Toast.makeText(this, R.string.analysis_error_toast, Toast.LENGTH_LONG).show();

        // Retry Storage if early upload failed but we still have WAV
        if (lastWavBytes != null && lastWavBytes.length >= MIN_WAV_STORAGE_BYTES) {
            String filename = "session_" + System.currentTimeMillis() + ".wav";
            FirebaseHelper.uploadRecording(patientId, filename, lastWavBytes,
                    url -> {
                        PatientSessionRecord record = new PatientSessionRecord();
                        record.setTherapistId(drill.getTherapistId() != null ? drill.getTherapistId() : "");
                        record.setAssignedDrillId(drill.getAssignedDrillId() != null ? drill.getAssignedDrillId() : "");
                        String title = drill.getTitle();
                        if (isPersonalized && drill.getTargetWords() != null)
                            title = "Personalized: " + drill.getTargetWords();
                        record.setDrillTitle(title != null ? title : "Personalized");
                        record.setDateMs(System.currentTimeMillis());
                        record.setDate(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date()));
                        record.setDurationSeconds(wavDurationSeconds(lastWavBytes));
                        record.setDysarthriaScore(0.0);
                        record.setDysarthriaPrediction("");
                        record.setNote("Analysis unavailable");
                        record.setRecordingUrl(url);
                        lastFirebaseRecordingUrl = url;
                        btnListen.setVisibility(View.VISIBLE);
                        FirebaseHelper.addPatientSessionRecord(patientId, record, () -> mainHandler.post(() -> {
                            Toast.makeText(this, R.string.recording_saved_cloud_analysis_failed, Toast.LENGTH_LONG).show();
                            if (isPersonalized) btnDone.setVisibility(View.VISIBLE);
                        }));
                    },
                    err -> Log.e(TAG, "Could not save voice after analysis failure", err));
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
        } catch (Exception e) {
            Log.w(TAG, "releaseMediaPlayer", e);
        }
        mediaPlayer = null;
    }

    private void playSavedRecording() {
        releaseMediaPlayer();
        boolean useLocalFile = lastRecordingPath != null && new File(lastRecordingPath).exists();
        boolean useCloud = lastFirebaseRecordingUrl != null && !lastFirebaseRecordingUrl.isEmpty();
        boolean useBytes = lastWavBytes != null && lastWavBytes.length > MIN_WAV_STORAGE_BYTES;
        if (!useLocalFile && !useCloud && !useBytes) {
            Toast.makeText(this, R.string.no_recording_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mp.setOnCompletionListener(m -> releaseMediaPlayer());
            mp.setOnErrorListener((m, what, extra) -> {
                releaseMediaPlayer();
                Toast.makeText(RecordDrillActivity.this, R.string.playback_error, Toast.LENGTH_SHORT).show();
                return true;
            });

            if (useLocalFile) {
                mp.setDataSource(lastRecordingPath);
                mp.prepare();
                mediaPlayer = mp;
                mp.start();
                return;
            }
            if (useBytes) {
                File tmp = new File(getCacheDir(), "playback_last.wav");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(lastWavBytes);
                }
                mp.setDataSource(tmp.getAbsolutePath());
                mp.prepare();
                mediaPlayer = mp;
                mp.start();
                return;
            }
            mp.setDataSource(this, Uri.parse(lastFirebaseRecordingUrl));
            mp.prepareAsync();
            mp.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer = mp;
        } catch (Exception e) {
            Log.e(TAG, "Playback failed", e);
            releaseMediaPlayer();
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Exception e) {}
        }
        releaseMediaPlayer();
        recordExecutor.shutdown();
        analyzeExecutor.shutdown();
        super.onDestroy();
    }
}
