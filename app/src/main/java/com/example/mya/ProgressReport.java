package com.example.mya;

import java.util.List;

/**
 * Progress report for a patient session.
 */
public class ProgressReport {
    private String difficulty;
    private String improvement;
    private double level_score;
    private String patient_name;
    private String summary;
    private int total_sessions;
    private int exercise_completed;
    private String feedback;
    private String patient_id;
    private String session_id;
    private String therapist_id;
    private List<SessionData> sessions;

    public static class SessionData {
        private double avg_score;
        private String data;

        public double getAvg_score() { return avg_score; }
        public void setAvg_score(double avg_score) { this.avg_score = avg_score; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getImprovement() { return improvement; }
    public void setImprovement(String improvement) { this.improvement = improvement; }
    public double getLevel_score() { return level_score; }
    public void setLevel_score(double level_score) { this.level_score = level_score; }
    public String getPatient_name() { return patient_name; }
    public void setPatient_name(String patient_name) { this.patient_name = patient_name; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getTotal_sessions() { return total_sessions; }
    public void setTotal_sessions(int total_sessions) { this.total_sessions = total_sessions; }
    public int getExercise_completed() { return exercise_completed; }
    public void setExercise_completed(int exercise_completed) { this.exercise_completed = exercise_completed; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getPatient_id() { return patient_id; }
    public void setPatient_id(String patient_id) { this.patient_id = patient_id; }
    public String getSession_id() { return session_id; }
    public void setSession_id(String session_id) { this.session_id = session_id; }
    public String getTherapist_id() { return therapist_id; }
    public void setTherapist_id(String therapist_id) { this.therapist_id = therapist_id; }
    public List<SessionData> getSessions() { return sessions; }
    public void setSessions(List<SessionData> sessions) { this.sessions = sessions; }
}
