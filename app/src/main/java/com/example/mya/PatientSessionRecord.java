package com.example.mya;

/**
 * One session record for a patient (drill completion or analysis). Stored under patient_sessions/{patientId}/sessions.
 */
public class PatientSessionRecord {
    private String sessionId;
    private String patientId;
    private String therapistId;
    private String assignedDrillId;
    private String drillTitle;
    private long dateMs;
    private String date;           // yyyy-MM-dd
    private int durationSeconds;
    private Double dysarthriaScore;
    private String dysarthriaPrediction;
    private String note;
    private String recordingUrl;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getTherapistId() { return therapistId; }
    public void setTherapistId(String therapistId) { this.therapistId = therapistId; }
    public String getAssignedDrillId() { return assignedDrillId; }
    public void setAssignedDrillId(String assignedDrillId) { this.assignedDrillId = assignedDrillId; }
    public String getDrillTitle() { return drillTitle; }
    public void setDrillTitle(String drillTitle) { this.drillTitle = drillTitle; }
    public long getDateMs() { return dateMs; }
    public void setDateMs(long dateMs) { this.dateMs = dateMs; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public Double getDysarthriaScore() { return dysarthriaScore; }
    public void setDysarthriaScore(Double dysarthriaScore) { this.dysarthriaScore = dysarthriaScore; }
    public String getDysarthriaPrediction() { return dysarthriaPrediction; }
    public void setDysarthriaPrediction(String dysarthriaPrediction) { this.dysarthriaPrediction = dysarthriaPrediction; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String recordingUrl) { this.recordingUrl = recordingUrl; }

    /** Format prediction + score for UI. Hides raw labels like LABEL_0/LABEL_1 and shows friendly text or just %. */
    public static String formatResultForDisplay(String prediction, Double score) {
        String pred = prediction != null ? prediction.trim() : "";
        if (pred.matches("LABEL_\\d+")) pred = "";
        if (pred.equalsIgnoreCase("healthy")) pred = "Clear";
        if (pred.equalsIgnoreCase("dysarthric")) pred = "Moderate";
        StringBuilder sb = new StringBuilder();
        if (!pred.isEmpty()) sb.append(pred).append(" ");
        if (score != null) sb.append(String.format("%.0f%%", score * 100));
        return sb.toString().trim();
    }
}
