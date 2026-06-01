# VocalAid — Dysarthria Speech Therapy Assistant

<p align="center">
  <img src="vocalaid_outer_circle_icon_transparent.png" alt="VocalAid Logo" width="150"/>
</p>

A mobile speech therapy platform for patients with **dysarthria** (a motor speech disorder affecting articulation and clarity). VocalAid connects patients with speech-language therapists, provides AI-powered speech analysis, phoneme-level personalization, and gamified exercises — all in one Android app.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Setup & Installation](#setup--installation)
- [API Endpoints](#api-endpoints)
- [Phoneme Personalization & A/B Testing](#phoneme-personalization--ab-testing)
- [Firebase Database Structure](#firebase-database-structure)
- [Screenshots](#screenshots)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Dysarthria is a neurological speech disorder that affects motor control of speech muscles, making speech slurred, slow, or difficult to understand. VocalAid provides:

- **For Patients**: Personalized speech drills targeting their specific weak phonemes, games for cognitive-linguistic practice, AI chat for guidance, and progress tracking.
- **For Therapists**: Patient management, custom drill assignment, progress monitoring, AI-generated summaries, and direct messaging.

The app uses a fine-tuned **wav2vec2** model for dysarthria detection, **Whisper** for speech transcription, **g2p-en** for phoneme extraction, and **Groq LLM** for AI-powered summaries and chat.

---

## Features

### Patient Features
- 🎙️ **Speech Drills** — Record voice, receive AI analysis with dysarthria confidence score
- 🧠 **Phoneme-Level Personalization** — System identifies weak phonemes and generates targeted drills
- 🎮 **Games** — Memory matching and word scramble for engaging practice
- 💬 **Chat** — Direct messaging with assigned therapist
- 🤖 **AI Agent Chat** — Ask questions about dysarthria and get speech therapy tips
- 📊 **Progress Tracking** — View session history, scores, and improvement trends
- 📈 **Adaptive Difficulty** — Automatic level progression (easy → medium → hard) at 80%+ accuracy

### Therapist Features
- 👥 **Patient Management** — Accept/reject patient requests, view roster
- 📝 **Drill Assignment** — Create custom drills with target words, difficulty, and instructions
- 📋 **TORGO Dataset Integration** — Assign drills from dysarthria research dataset
- 📊 **Patient Progress** — View session records, dysarthria scores, and trends
- 🤖 **AI Summaries** — Generate patient history summaries and therapy suggestions
- 💬 **Messaging** — Communicate directly with patients
- 🔧 **Level Override** — Manually set patient difficulty level

### A/B Testing
- 🔬 **Randomized Assignment** — Patients split 50/50 into Group A (personalized) vs Group B (random)
- 📊 **Statistical Analysis** — Track accuracy per group with t-test significance
- 📝 **Event Logging** — Every drill completion logged for later analysis

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android App (Java)                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│  │  Patient  │  │Therapist │  │  Games   │  │  Chat  │ │
│  │   Home    │  │   Home   │  │ Activity │  │Activity│ │
│  └─────┬────┘  └─────┬────┘  └──────────┘  └────────┘ │
│        │              │                                   │
│  ┌─────┴──────────────┴─────────────────────────────┐   │
│  │          FirebaseHelper / PhonemeProfileHelper     │   │
│  └─────────────────────┬────────────────────────────┘   │
└────────────────────────┼────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
┌──────────────┐  ┌───────────┐  ┌──────────────┐
│   Firebase   │  │ VocalAid  │  │   Groq API   │
│  (Auth, DB,  │  │    API    │  │  (LLM Chat,  │
│   Storage)   │  │ (Python)  │  │  Summaries)  │
└──────────────┘  └─────┬─────┘  └──────────────┘
                        │
              ┌─────────┼─────────┐
              ▼         ▼         ▼
        ┌─────────┐ ┌───────┐ ┌───────┐
        │wav2vec2 │ │Whisper│ │g2p-en │
        │(dysarth)│ │(STT)  │ │(phone)│
        └─────────┘ └───────┘ └───────┘
```

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Mobile App | Android (Java), Min SDK 24, Target SDK 36 |
| Backend API | Python, Flask |
| Speech Model | wav2vec2 (fine-tuned for dysarthria detection) |
| Transcription | OpenAI Whisper (tiny/base/small) |
| Phoneme Analysis | g2p-en (grapheme-to-phoneme) |
| LLM | Groq API (LLaMA 3.1 8B Instant) |
| Database | Firebase Realtime Database |
| Auth | Firebase Authentication |
| Storage | Firebase Storage (recordings, photos) |
| HTTP Client | OkHttp 4.12 |
| UI | Material Design 3 |

---

## Project Structure

```
├── app/                          # Android application
│   ├── src/main/java/com/example/mya/
│   │   ├── FirebaseHelper.java          # Firebase operations (auth, DB, storage)
│   │   ├── PhonemeProfileHelper.java    # Phoneme personalization + A/B testing
│   │   ├── DrillWordProvider.java       # Static word lists by difficulty
│   │   ├── GroqHelper.java             # Groq LLM API client
│   │   ├── PatientHomeActivity.java     # Patient dashboard
│   │   ├── TherapistHomeActivity.java   # Therapist dashboard
│   │   ├── PersonalDrillsActivity.java  # Self-practice with phoneme targeting
│   │   ├── RecordDrillActivity.java     # Voice recording + API analysis
│   │   ├── AssignDrillActivity.java     # Therapist assigns drills
│   │   ├── ChatActivity.java           # Patient-therapist messaging
│   │   ├── AgentChatActivity.java       # AI chat about dysarthria
│   │   ├── GamesActivity.java          # Game selection
│   │   ├── MemoryGameActivity.java      # Memory matching game
│   │   ├── WordScrambleActivity.java    # Word scramble game
│   │   └── ...                          # Other activities & models
│   └── src/main/res/                    # Layouts, drawables, strings
├── vocalaid_api/                 # Python backend API
│   ├── app.py                           # Flask API (analyze, phoneme, AI, TORGO)
│   ├── requirements.txt                 # Python dependencies
│   ├── test_smoke.py                    # Basic smoke tests
│   ├── test_ab.py                       # A/B testing validation script
│   └── data/
│       └── torgo_phrases.json           # TORGO dataset phrases
├── database.rules.json           # Firebase security rules
├── build.gradle                  # Root Gradle config
└── settings.gradle               # Gradle settings
```

---

## Setup & Installation

### Prerequisites

- Android Studio (Hedgehog or newer)
- Python 3.10+
- Firebase project with Realtime Database, Auth, and Storage enabled
- Groq API key (free at [console.groq.com](https://console.groq.com))

### 1. Clone the Repository

```bash
git clone https://github.com/Hamyal/FYP-Dysarthria-Speech-Assistant-.git
cd FYP-Dysarthria-Speech-Assistant-
```

### 2. Android App Setup

1. Open the project in Android Studio
2. Place your `google-services.json` in `app/` (from Firebase Console)
3. Add your Groq API key to `local.properties`:
   ```properties
   GROQ_API_KEY=gsk_your_key_here
   ```
4. Build and run on emulator or device (API 24+)

### 3. VocalAid API Setup

```bash
cd vocalaid_api

# Create virtual environment
python -m venv venv
venv\Scripts\activate        # Windows
# source venv/bin/activate   # macOS/Linux

# Install dependencies
pip install -r requirements.txt

# Set environment variables (or create .env file)
echo GROQ_API_KEY=gsk_your_key_here > .env

# Download/place your wav2vec2 model
# Set VOCALAID_MODEL_PATH in .env or environment

# Run the API
python app.py
```

The API runs at `http://0.0.0.0:5001`. Android emulator accesses it via `http://10.0.2.2:5001`.

### 4. Firebase Setup

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Enable Email/Password authentication
3. Create a Realtime Database
4. Deploy security rules from `database.rules.json`
5. Enable Firebase Storage

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Server status and model info |
| POST | `/analyze` | Analyze speech audio (dysarthria score + transcription + phonemes) |
| GET | `/dataset/torgo` | List TORGO phrases (optional `?difficulty=easy\|medium\|hard`) |
| GET | `/dataset/torgo/pick` | Random phrase for drill assignment |
| POST | `/phoneme/profile` | Analyze patient sessions → identify weak phonemes |
| POST | `/phoneme/drills` | Generate targeted drills for weak phonemes (A/B aware) |
| POST | `/ai/summary` | AI-generated patient progress summary |
| POST | `/ai/chat` | Conversational AI chat about dysarthria |
| POST | `/ab/log` | Log A/B test event |
| GET | `/ab/results` | A/B test comparison statistics |

### Example: Analyze Speech

```bash
curl -X POST http://localhost:5001/analyze \
  -F "audio=@recording.wav" \
  -F "patient_id=abc123" \
  -F "target_text=sun bus miss"
```

Response:
```json
{
  "accuracy": 72.5,
  "prediction": "dysarthric",
  "confidence": 0.8234,
  "transcription": "un bu mi",
  "phonemes": "AH1 N B UW1 M IH1",
  "speech_clarity_percent": 45.2,
  "drill_match_percent": 72.5,
  "phoneme_accuracy": {
    "S": {"expected": 3, "matched": 0, "accuracy": 0.0},
    "AH": {"expected": 2, "matched": 2, "accuracy": 100.0}
  }
}
```

---

## Phoneme Personalization & A/B Testing

### How It Works

1. **Data Collection**: Each time a patient completes a drill, the system records what they were supposed to say (target) and what they actually said (Whisper transcription).

2. **Phoneme Analysis**: The `/phoneme/profile` endpoint converts both target and spoken text to ARPAbet phonemes using g2p-en, then aligns them to find which phonemes the patient consistently misses.

3. **Weak Phoneme Identification**: Phonemes with average accuracy below 60% across 2+ sessions are flagged as "weak."

4. **Targeted Drill Generation**: The `/phoneme/drills` endpoint generates practice words that heavily exercise the patient's weak phonemes (e.g., if /S/ is weak → "sun, bus, miss, see, six").

5. **Adaptive Difficulty**:
   - Easy: single words targeting the weak phoneme
   - Medium: two-word combinations
   - Hard: short sentences containing the target sound

### A/B Testing Design

| Group | Experience | Purpose |
|-------|-----------|---------|
| A (Treatment) | Drills target weak phonemes | Test if personalization improves outcomes |
| B (Control) | Random drills (current behavior) | Baseline comparison |

**Metrics tracked**: accuracy per drill, phonemes targeted, timestamp, patient ID.

**Statistical analysis**: `GET /ab/results` computes mean/median accuracy per group and a two-sample t-test. With 5+ sessions per group, it reports whether the difference is statistically significant (p < 0.05).

### Running A/B Tests

```bash
# Run offline validation
cd vocalaid_api
python test_ab.py --offline

# Run with server (start server first: python app.py)
python test_ab.py
```

---

## Firebase Database Structure

```
├── users/{uid}                    # User profiles (name, email, type)
├── Patient/{uid}                  # Patient details (age, therapist, status)
├── Therapist/{uid}                # Therapist details (code, experience)
├── therapist_patients/{tid}/{pid} # Therapist → patient index
├── patient_requests/{id}          # Pending connection requests
├── conversations/{id}/messages/   # Patient-therapist chat
├── assigned_drills/{id}           # Therapist-assigned drills
├── patient_drill_index/{pid}/{id} # Patient drill lookup index
├── patient_sessions/{pid}/
│   ├── speechLevel                # Current level (easy/medium/hard)
│   ├── sessionCount               # Total sessions completed
│   └── sessions/{sid}             # Individual session records
├── phoneme_profiles/{pid}         # Weak phoneme analysis results
├── ab_test_assignments/{pid}      # A/B group assignment ("A" or "B")
├── progress_Reports/{id}          # AI-generated progress reports
└── chatbot_interaction/{id}       # AI chat history
```

---

## Screenshots

*Screenshots can be added here showing the app's key screens.*

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit changes (`git commit -m 'Add your feature'`)
4. Push to branch (`git push origin feature/your-feature`)
5. Open a Pull Request

---

## License

This project is developed as a Final Year Project (FYP) for academic purposes.

---

## Acknowledgments

- [TORGO Database](http://www.cs.toronto.edu/~comp} — Dysarthria speech dataset
- [Hugging Face Transformers](https://huggingface.co/transformers/) — wav2vec2 model
- [OpenAI Whisper](https://github.com/openai/whisper) — Speech transcription
- [Groq](https://groq.com) — LLM inference API
- [Firebase](https://firebase.google.com) — Backend services
- [g2p-en](https://github.com/Kyubyong/g2p) — English grapheme-to-phoneme
