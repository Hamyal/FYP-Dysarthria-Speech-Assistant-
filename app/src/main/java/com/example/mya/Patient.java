package com.example.mya;

/**
 * Patient profile. Status is "pending" until a therapist accepts their request.
 */
public class Patient {
    private String patient_id;  // Firebase Auth UID
    private String name;
    private String last_name;
    private String email;
    private int age;
    private String assigned_therapist;  // Therapist UID after acceptance
    private String status;             // "pending" or "accepted"
    private String Last_session;
    private int progress_score;

    public Patient() {}

    public Patient(String patient_id, String name, String last_name, String email, int age) {
        this.patient_id = patient_id;
        this.name = name;
        this.last_name = last_name;
        this.email = email;
        this.age = age;
        this.assigned_therapist = "";
        this.status = "pending";
        this.Last_session = "";
        this.progress_score = 0;
    }

    public String getPatient_id() { return patient_id; }
    public void setPatient_id(String patient_id) { this.patient_id = patient_id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLast_name() { return last_name; }
    public void setLast_name(String last_name) { this.last_name = last_name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
    public String getAssigned_therapist() { return assigned_therapist; }
    public void setAssigned_therapist(String assigned_therapist) { this.assigned_therapist = assigned_therapist; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLast_session() { return Last_session; }
    public void setLast_session(String last_session) { Last_session = last_session; }
    public int getProgress_score() { return progress_score; }
    public void setProgress_score(int progress_score) { this.progress_score = progress_score; }

    public String getFullName() { return name + " " + last_name; }
}
