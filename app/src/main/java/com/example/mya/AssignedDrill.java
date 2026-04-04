package com.example.mya;

import java.io.Serializable;

/**
 * Drill assigned by therapist to a patient. Stored in Firebase under assigned_drills.
 */
public class AssignedDrill implements Serializable {
    private String assignedDrillId;
    private String patientId;
    private String therapistId;
    private String therapistName;
    private String title;
    private String instructions;
    private String difficulty;
    private boolean completed;
    private long completedAt;
    private Double dysarthriaScore;      // 0–1 confidence from model (if analyzed)
    private String dysarthriaPrediction; // "healthy" or "dysarthric"
    private long assignedAt;
    private String dueDate;
    /** Phoneme words to speak (comma-separated), e.g. "cat,dog,run" */
    private String targetWords;
    /** Reference transcription from TORGO-style dataset (same text patient speaks if from dataset). */
    private String transcription;
    /** Dataset utterance id when assigned from TORGO API, e.g. TORGO_M01 */
    private String torgoUtteranceId;

    public String getAssignedDrillId() { return assignedDrillId; }
    public void setAssignedDrillId(String assignedDrillId) { this.assignedDrillId = assignedDrillId; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getTherapistId() { return therapistId; }
    public void setTherapistId(String therapistId) { this.therapistId = therapistId; }
    public String getTherapistName() { return therapistName; }
    public void setTherapistName(String therapistName) { this.therapistName = therapistName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public Double getDysarthriaScore() { return dysarthriaScore; }
    public void setDysarthriaScore(Double dysarthriaScore) { this.dysarthriaScore = dysarthriaScore; }
    public String getDysarthriaPrediction() { return dysarthriaPrediction; }
    public void setDysarthriaPrediction(String dysarthriaPrediction) { this.dysarthriaPrediction = dysarthriaPrediction; }
    public long getAssignedAt() { return assignedAt; }
    public void setAssignedAt(long assignedAt) { this.assignedAt = assignedAt; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public String getTargetWords() { return targetWords; }
    public void setTargetWords(String targetWords) { this.targetWords = targetWords; }
    public String getTranscription() { return transcription; }
    public void setTranscription(String transcription) { this.transcription = transcription; }
    public String getTorgoUtteranceId() { return torgoUtteranceId; }
    public void setTorgoUtteranceId(String torgoUtteranceId) { this.torgoUtteranceId = torgoUtteranceId; }
}
