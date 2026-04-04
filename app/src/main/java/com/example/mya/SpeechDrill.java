package com.example.mya;

/**
 * Speech drill assigned to a patient.
 */
public class SpeechDrill {
    private String drillId;
    private String assigned_to;
    private String difficulty;
    private double score;
    private String target;
    private String word;

    public String getDrillId() { return drillId; }
    public void setDrillId(String drillId) { this.drillId = drillId; }
    public String getAssigned_to() { return assigned_to; }
    public void setAssigned_to(String assigned_to) { this.assigned_to = assigned_to; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
}
