# VocalAid – Dysarthria Speech Therapy Assistant

## Abstract

VocalAid is an AI-powered speech therapy assistant designed for individuals with dysarthria. It offers personalized drills, real-time feedback, and therapist support through an intelligent chatbot. Using artificial intelligence and natural language processing, the system analyzes speech patterns, identifies errors, and suggests targeted exercises. It maintains patient history, tracks progress, and generates performance reports. By enhancing accessibility and automating monitoring, VocalAid provides continuous, effective, and engaging speech rehabilitation beyond traditional therapy sessions.

---

## Vision Document

VocalAid is an AI-powered mobile health application for speech rehabilitation in individuals with dysarthria. It integrates speech analysis, personalized therapy, and progress tracking to improve treatment effectiveness. Using artificial intelligence, machine learning, and speech recognition, VocalAid provides accessible, consistent, and engaging therapy, enhancing communication and overall quality of life for patients.

### Problem Statement

- Limited and inconsistent speech therapy sessions for dysarthria patients
- Decline in patient motivation due to lack of personalized and engaging therapy
- Absence of real-time feedback and automated progress evaluation
- Difficulty for therapists to objectively monitor patient improvement

### Objectives

- **Personalized Therapy Drills**: Create adaptive speech exercises tailored to each patient's specific challenges
- **Automated Scoring & Feedback**: Implement scoring for speech clarity, fluency, and correctness with instant patient feedback
- **Comprehensive History Maintenance**: Log all therapy sessions, scores, and progress data
- **AI Agent Support**: Interactive AI agent for therapy guidance and dysarthria-related information
- **Therapist Guidance**: Enable therapists to access progress summaries and insights
- **Accessible Therapy Beyond Clinics**: Reliable, AI-powered therapy companion for continuous practice

### Project Scope

- **Speech Analysis and Recognition**: NLP to analyze articulation, pronunciation, and fluency
- **Personalized Therapy and Adaptive Drills**: Custom exercises based on each user's progress
- **Automated Scoring and Feedback System**: Instant scores and real-time feedback
- **Progress Tracking and Reporting**: Detailed records of sessions, scores, and audio logs
- **AI Agent and Therapist Support Tools**: Integrated AI assistant for guidance and summarized insights

---

## System Features

1. **Mobile App Dashboard**: Real-time overview of patient progress and system analytics
2. **Speech Input and Analysis**: Record speech; analyze articulation, pronunciation, and fluency via NLP
3. **Personalized Therapy Drills**: Custom drills that adjust in complexity based on performance
4. **Real-Time Scoring and Feedback**: Immediate feedback on speech clarity and accuracy
5. **Progress Tracking and Reporting**: Detailed logs with visual graphs and reports
6. **AI Agent and Assistance**: Conversational AI for therapy guidance and educational content

---

## Functional Requirements

- **Speech Recognition**: Accurate capture and transcription using ASR
- **Error Detection and Evaluation**: Identify mispronunciations; calculate accuracy scores
- **Adaptive Drill Generation**: Dynamic exercises based on performance and improvement areas
- **AI Chatbot Interaction**: Interactive sessions for motivation, reminders, and instructions
- **Progress Logging and Reporting**: Secure database of sessions; visual reports and summaries

---

## Stakeholders

- **Therapists**: Speech-Language Pathologists (SLPs) for therapy planning and monitoring
- **Patients**: Individuals with dysarthria performing daily exercises
- **Rehabilitation Centers**: Institutions adopting the platform
- **Caregivers and Family**: Supporting home-based therapy routines

---

## Database Schema (Key Paths)

- **Patient**: `Patient/{patientId}`
- **Therapist**: `Therapist/{therapistId}`
- **Assigned Drills**: `assigned_drills/{assignedDrillId}` (indexed by `patientId`)
- **Patient Sessions**: `patient_sessions/{patientId}/sessions`, `sessionCount`
- **Reports**: `progress_Reports`
- **AI Agent**: `chatbot_interaction`

---

## References

- UASpeech Dataset; TORGO Database
- Rudzicz et al., TORGO database of acoustic and articulatory speech
- Vinotha et al., Dysarthric speech recognition (SepFormer, hierarchical attention)
- Mulfari, Deep learning in telerehabilitation speech therapy
- Alrajhi et al., Recent Advances in Dysarthric Speech Recognition
