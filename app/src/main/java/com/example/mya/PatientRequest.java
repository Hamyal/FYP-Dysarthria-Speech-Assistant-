package com.example.mya;

/**
 * Request from a patient to join a therapist. Therapist can accept or reject.
 */
public class PatientRequest {
    private String requestId;
    private String patientId;
    private String patientName;
    private String patientEmail;
    private int patientAge;
    private String therapistId;
    private String therapistName;
    private String status;  // "pending", "accepted", "rejected"
    private long timestamp;

    public PatientRequest() {}

    public PatientRequest(String requestId, String patientId, String patientName,
                          String patientEmail, int patientAge, String therapistId, String therapistName) {
        this.requestId = requestId;
        this.patientId = patientId;
        this.patientName = patientName;
        this.patientEmail = patientEmail;
        this.patientAge = patientAge;
        this.therapistId = therapistId;
        this.therapistName = therapistName;
        this.status = "pending";
        this.timestamp = System.currentTimeMillis();
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getPatientEmail() { return patientEmail; }
    public void setPatientEmail(String patientEmail) { this.patientEmail = patientEmail; }
    public int getPatientAge() { return patientAge; }
    public void setPatientAge(int patientAge) { this.patientAge = patientAge; }
    public String getTherapistId() { return therapistId; }
    public void setTherapistId(String therapistId) { this.therapistId = therapistId; }
    public String getTherapistName() { return therapistName; }
    public void setTherapistName(String therapistName) { this.therapistName = therapistName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
