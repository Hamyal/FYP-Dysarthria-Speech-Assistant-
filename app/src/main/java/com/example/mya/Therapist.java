package com.example.mya;

/**
 * Therapist profile. Has a unique code that patients use to send requests.
 */
public class Therapist {
    private String therapist_id;  // Firebase Auth UID
    private String code;          // Unique 6-char code shared with patients
    private String name;
    private String last_name;
    private String email;
    private String experience;  // optional, e.g. years
    private int assigned_patients;

    public Therapist() {}

    public Therapist(String therapist_id, String code, String name, String last_name, String email) {
        this.therapist_id = therapist_id;
        this.code = code;
        this.name = name;
        this.last_name = last_name;
        this.email = email;
        this.assigned_patients = 0;
    }

    public String getTherapist_id() { return therapist_id; }
    public void setTherapist_id(String therapist_id) { this.therapist_id = therapist_id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLast_name() { return last_name; }
    public void setLast_name(String last_name) { this.last_name = last_name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }
    public int getAssigned_patients() { return assigned_patients; }
    public void setAssigned_patients(int assigned_patients) { this.assigned_patients = assigned_patients; }

    public String getFullName() { return name + " " + last_name; }
}
