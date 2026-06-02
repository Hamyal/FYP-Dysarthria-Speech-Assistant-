# VocalAid — System Flowchart

## 1. Complete System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           VocalAid System                                    │
│                                                                             │
│  ┌───────────────┐         ┌───────────────┐         ┌───────────────┐     │
│  │   Patient     │◄───────►│   Firebase    │◄───────►│  Therapist    │     │
│  │  (Android)    │         │  (Cloud DB)   │         │  (Android)    │     │
│  └───────┬───────┘         └───────────────┘         └───────┬───────┘     │
│          │                                                    │             │
│          │              ┌───────────────┐                     │             │
│          └─────────────►│  VocalAid API │◄────────────────────┘             │
│                         │   (Python)    │                                   │
│                         └───────┬───────┘                                   │
│                                 │                                           │
│                    ┌────────────┼────────────┐                              │
│                    ▼            ▼            ▼                              │
│              ┌──────────┐ ┌─────────┐ ┌──────────┐                         │
│              │ wav2vec2  │ │ Whisper │ │ Groq LLM │                         │
│              │(Dysarth.) │ │  (STT)  │ │  (Chat)  │                         │
│              └──────────┘ └─────────┘ └──────────┘                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. User Authentication & Registration Flow

```
                        ┌─────────────┐
                        │ Splash Screen│
                        └──────┬──────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
            ┌──────────────┐     ┌──────────────┐
            │    Login     │     │   Register   │
            │  (Email/Pass)│     │    Choice    │
            └──────┬───────┘     └──────┬───────┘
                   │                    │
                   │         ┌──────────┴──────────┐
                   │         ▼                     ▼
                   │  ┌──────────────┐     ┌──────────────┐
                   │  │  Register as │     │  Register as │
                   │  │   Patient    │     │  Therapist   │
                   │  └──────┬───────┘     └──────┬───────┘
                   │         │                    │
                   │         ▼                    ▼
                   │  ┌──────────────┐     ┌──────────────┐
                   │  │Enter Therapist│    │ Get Unique   │
                   │  │    Code      │     │  6-Char Code │
                   │  └──────┬───────┘     └──────┬───────┘
                   │         │                    │
                   │         ▼                    │
                   │  ┌──────────────┐            │
                   │  │   Pending    │            │
                   │  │  (Wait for   │            │
                   │  │  acceptance) │            │
                   │  └──────┬───────┘            │
                   │         │                    │
                   │    [Therapist Accepts]        │
                   │         │                    │
                   ▼         ▼                    ▼
            ┌──────────────────┐         ┌──────────────────┐
            │   Patient Home   │         │  Therapist Home  │
            └──────────────────┘         └──────────────────┘
```

---

## 3. Therapist-Patient Connection Flow

```
┌──────────────┐                              ┌──────────────┐
│   Therapist  │                              │   Patient    │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  1. Register                                │  2. Register
       ▼                                             ▼
┌──────────────┐                              ┌──────────────┐
│ Gets unique  │                              │ Enters the   │
│ code: "X3K9M2"│─────── shares code ────────►│ therapist's  │
└──────────────┘          (verbally/           │ code         │
       │                   text/email)         └──────┬───────┘
       │                                             │
       │                                             ▼
       │                                      ┌──────────────┐
       │                                      │  Request     │
       │◄─────────────────────────────────────│  Created in  │
       │            notification               │  Firebase    │
       ▼                                      └──────────────┘
┌──────────────┐
│  Accept or   │
│   Reject     │
└──────┬───────┘
       │
       │ [Accept]
       ▼
┌──────────────────────────────────────────────────┐
│  Patient linked to Therapist                      │
│  - assigned_therapist = therapist UID             │
│  - status = "accepted"                            │
│  - therapist_patients index updated               │
└──────────────────────────────────────────────────┘
```

---

## 4. Speech Drill Workflow (Core Feature)

```
┌────────────────────────────────────────────────────────────────────────┐
│                    SPEECH DRILL WORKFLOW                                 │
└────────────────────────────────────────────────────────────────────────┘

  THERAPIST SIDE                              PATIENT SIDE
  ─────────────                               ────────────

┌──────────────┐                         ┌──────────────────┐
│ Assign Drill │                         │ View Assigned    │
│ - Title      │                         │ Drills List      │
│ - Difficulty │                         └────────┬─────────┘
│ - Target     │                                  │
│   Words      │                                  ▼
│ - TORGO pick │                         ┌──────────────────┐
└──────┬───────┘                         │ Select a Drill   │
       │                                 └────────┬─────────┘
       │   [saved to Firebase]                    │
       │   assigned_drills/{id}                   ▼
       └─────────────────────────────►   ┌──────────────────┐
                                         │ Record Voice     │
                                         │ (16kHz PCM Mono) │
                                         └────────┬─────────┘
                                                  │
                                                  ▼
                                         ┌──────────────────┐
                                         │ Upload to        │
                                         │ Firebase Storage │
                                         └────────┬─────────┘
                                                  │
                                                  ▼
                                         ┌──────────────────┐
                                         │ Send to VocalAid │
                                         │ API /analyze     │
                                         └────────┬─────────┘
                                                  │
                              ┌────────────────────┼────────────────────┐
                              ▼                    ▼                    ▼
                     ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
                     │  wav2vec2    │    │   Whisper    │    │   g2p-en     │
                     │  Dysarthria  │    │ Transcribe   │    │  Phonemes    │
                     │  Score       │    │  Speech      │    │  (from text) │
                     └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
                            │                   │                   │
                            └───────────────────┼───────────────────┘
                                                │
                                                ▼
                                    ┌───────────────────────┐
                                    │   API Response:       │
                                    │   - accuracy (0-100)  │
                                    │   - prediction        │
                                    │   - transcription     │
                                    │   - phonemes          │
                                    │   - phoneme_accuracy  │
                                    └───────────┬───────────┘
                                                │
                                                ▼
                                    ┌───────────────────────┐
                                    │  Save Session Record  │
                                    │  to Firebase          │
                                    │  patient_sessions/    │
                                    └───────────┬───────────┘
                                                │
                                                ▼
                                    ┌───────────────────────┐
                                    │ Update Speech Level   │
                                    │ (if accuracy >= 80%)  │
                                    │ easy → medium → hard  │
                                    └───────────────────────┘
```

---

## 5. Phoneme-Level Personalization Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│              PHONEME PERSONALIZATION SYSTEM                              │
└────────────────────────────────────────────────────────────────────────┘

┌──────────────────┐
│ Patient opens    │
│ "Personal Drills"│
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     ┌──────────────────┐
│ Load phoneme     │────►│ Display weak     │
│ profile from     │     │ phonemes:        │
│ Firebase (cache) │     │ "/S/ (40%),      │
└────────┬─────────┘     │  /SH/ (35%)"    │
         │               └──────────────────┘
         ▼
┌──────────────────┐
│ Get A/B Group    │
│ assignment       │
│ (A or B, 50/50) │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Patient taps     │
│ "Start Practice" │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Fetch last 20    │
│ sessions from    │
│ Firebase         │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Call API:        │
│ POST /phoneme/   │
│ drills           │
└────────┬─────────┘
         │
         ├─────────────────────────────────────┐
         │                                     │
    [Group A]                             [Group B]
         │                                     │
         ▼                                     ▼
┌──────────────────┐              ┌──────────────────┐
│ PERSONALIZED:    │              │ CONTROL:         │
│                  │              │                  │
│ 1. Analyze       │              │ Random words     │
│    sessions      │              │ from static      │
│ 2. Find weak     │              │ word lists       │
│    phonemes      │              │ (no targeting)   │
│ 3. Generate      │              │                  │
│    words that    │              │                  │
│    target weak   │              │                  │
│    sounds        │              │                  │
└────────┬─────────┘              └────────┬─────────┘
         │                                 │
         └────────────────┬────────────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │ Launch RecordDrill    │
              │ with targeted words   │
              │ e.g. "sun, bus, miss" │
              └───────────┬───────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │ Patient records voice │
              │ → Analyze → Save     │
              └───────────┬───────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │ Log A/B event:        │
              │ POST /ab/log          │
              │ {group, accuracy,     │
              │  phonemes_targeted}   │
              └───────────┬───────────┘
                          │
                          ▼
              ┌───────────────────────┐
              │ Refresh phoneme       │
              │ profile (re-analyze)  │
              └───────────────────────┘
```

---

## 6. Phoneme Analysis Algorithm

```
┌────────────────────────────────────────────────────────────────────────┐
│                HOW WEAK PHONEMES ARE IDENTIFIED                          │
└────────────────────────────────────────────────────────────────────────┘

    Input: Patient's session history
    ┌────────────────────────────────────┐
    │ Session 1: target="sun bus miss"   │
    │            spoken="un bu mi"       │
    │ Session 2: target="fish ship wash" │
    │            spoken="fi ip wa"       │
    │ Session 3: target="sun bus miss"   │
    │            spoken="un bu mi"       │
    └──────────────────┬─────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────┐
    │ Step 1: Convert to phonemes (g2p)  │
    │                                    │
    │ "sun" → /S AH N/                  │
    │ "un"  → /AH N/                    │
    │                                    │
    │ "fish" → /F IH SH/               │
    │ "fi"   → /F AY/                  │
    └──────────────────┬─────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────┐
    │ Step 2: Align & compare            │
    │                                    │
    │ Target:  S  AH  N  B  AH  S       │
    │ Spoken:  _  AH  N  B  AH  _       │
    │          ✗  ✓   ✓  ✓  ✓   ✗       │
    │                                    │
    │ /S/: 0/2 matched = 0% accuracy    │
    │ /AH/: 2/2 matched = 100%         │
    │ /N/: 1/1 matched = 100%          │
    │ /B/: 1/1 matched = 100%          │
    └──────────────────┬─────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────┐
    │ Step 3: Aggregate across sessions  │
    │                                    │
    │ /S/:  [0%, 0%, 0%] → avg = 0%    │
    │ /SH/: [33%, 33%]   → avg = 33%   │
    │ /AH/: [100%, 100%] → avg = 100%  │
    │ /N/:  [100%, 100%] → avg = 100%  │
    └──────────────────┬─────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────┐
    │ Step 4: Filter below threshold     │
    │ (default: 60%)                     │
    │                                    │
    │ WEAK PHONEMES:                     │
    │ ┌────────────────────────────────┐ │
    │ │ /S/  → 0%  (fricatives)       │ │
    │ │ /SH/ → 33% (fricatives)       │ │
    │ └────────────────────────────────┘ │
    └──────────────────┬─────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────┐
    │ Step 5: Generate targeted drills   │
    │                                    │
    │ For /S/: "sun", "bus", "miss"     │
    │ For /SH/: "ship", "fish", "wash"  │
    └────────────────────────────────────┘
```

---

## 7. A/B Testing Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                       A/B TESTING DESIGN                                 │
└────────────────────────────────────────────────────────────────────────┘

                    ┌──────────────────┐
                    │  New Patient     │
                    │  First Drill     │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ Random 50/50     │
                    │ Assignment       │
                    │ (stored in       │
                    │  Firebase)       │
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
    ┌──────────────────┐          ┌──────────────────┐
    │   GROUP A        │          │   GROUP B        │
    │  (Treatment)     │          │  (Control)       │
    │                  │          │                  │
    │ Personalized     │          │ Random drills    │
    │ phoneme-targeted │          │ (current default │
    │ drills           │          │  behavior)       │
    └────────┬─────────┘          └────────┬─────────┘
             │                             │
             ▼                             ▼
    ┌──────────────────┐          ┌──────────────────┐
    │ Complete drills  │          │ Complete drills  │
    │ over weeks       │          │ over weeks       │
    └────────┬─────────┘          └────────┬─────────┘
             │                             │
             │    Log: accuracy,           │    Log: accuracy,
             │    phonemes, group          │    group
             │                             │
             └──────────────┬──────────────┘
                            │
                            ▼
                   ┌──────────────────┐
                   │  GET /ab/results │
                   └────────┬─────────┘
                            │
                            ▼
              ┌──────────────────────────┐
              │  Statistical Analysis:   │
              │                          │
              │  Group A avg: 78.1%      │
              │  Group B avg: 62.4%      │
              │  Diff: +15.7%            │
              │  t-stat: 3.21            │
              │  Significant: YES        │
              │                          │
              │  → Personalization       │
              │    improves outcomes!     │
              └──────────────────────────┘
```

---

## 8. AI Features Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                        AI INTEGRATION                                    │
└────────────────────────────────────────────────────────────────────────┘

  ┌───────────────────────────────────────────────────────────────┐
  │                    AI SUMMARY                                   │
  │                                                               │
  │  ┌──────────┐    ┌──────────────┐    ┌──────────────┐        │
  │  │ Patient  │    │  Collect:    │    │ POST /ai/    │        │
  │  │ or       │───►│ - sessions   │───►│ summary      │        │
  │  │Therapist │    │ - drills     │    │              │        │
  │  │ taps AI  │    │ - reports    │    │ Groq LLM    │        │
  │  │ Summary  │    │ - patient    │    │ analyzes     │        │
  │  └──────────┘    │   info       │    │ all data     │        │
  │                  └──────────────┘    └──────┬───────┘        │
  │                                             │                 │
  │                                             ▼                 │
  │                                    ┌──────────────┐           │
  │                                    │ Returns:     │           │
  │                                    │ - History    │           │
  │                                    │   summary   │           │
  │                                    │ - Strengths  │           │
  │                                    │ - Challenges │           │
  │                                    │ - 3-5 next  │           │
  │                                    │   steps     │           │
  │                                    └──────────────┘           │
  └───────────────────────────────────────────────────────────────┘

  ┌───────────────────────────────────────────────────────────────┐
  │                    AI CHAT                                      │
  │                                                               │
  │  ┌──────────┐    ┌──────────────┐    ┌──────────────┐        │
  │  │ Patient  │    │ Conversation │    │ POST /ai/    │        │
  │  │ asks:    │───►│ history +    │───►│ chat         │        │
  │  │"How can I│    │ system       │    │              │        │
  │  │ improve  │    │ prompt       │    │ Groq LLM    │        │
  │  │ my /r/?" │    │ (dysarthria  │    │ responds     │        │
  │  └──────────┘    │  expert)     │    └──────┬───────┘        │
  │                  └──────────────┘           │                 │
  │                                             ▼                 │
  │                                    ┌──────────────┐           │
  │                                    │ "Try these   │           │
  │                                    │  exercises   │           │
  │                                    │  for /r/..." │           │
  │                                    └──────────────┘           │
  └───────────────────────────────────────────────────────────────┘
```

---

## 9. Adaptive Difficulty Progression

```
┌────────────────────────────────────────────────────────────────────────┐
│              DIFFICULTY LEVEL PROGRESSION                                │
└────────────────────────────────────────────────────────────────────────┘

                        START
                          │
                          ▼
               ┌─────────────────────┐
               │      EASY           │
               │                     │
               │ Content: Single     │
               │ phonemes/syllables  │
               │ "ah", "pa", "ba"    │
               └──────────┬──────────┘
                          │
                    accuracy >= 80%?
                          │
                   YES    │    NO
                   ┌──────┴──────┐
                   │             │
                   ▼             ▼
          ┌──────────────┐   [Stay at EASY]
          │   MEDIUM     │
          │              │
          │ Content:     │
          │ Words/short  │
          │ phrases      │
          │ "hot dog",   │
          │ "big cat"    │
          └──────┬───────┘
                 │
           accuracy >= 80%?
                 │
          YES    │    NO
          ┌──────┴──────┐
          │             │
          ▼             ▼
 ┌──────────────┐  [Stay at MEDIUM]
 │    HARD      │
 │              │
 │ Content:     │
 │ Complex words│
 │ & sentences  │
 │ "Practice    │
 │  makes       │
 │  perfect."   │
 └──────────────┘

 Note: Therapist can override level at any time
       from PatientProgressActivity
```

---

## 10. Chat System Flow

```
┌──────────────┐                              ┌──────────────┐
│   Patient    │                              │  Therapist   │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  1. Opens ChatActivity                      │
       ▼                                             │
┌──────────────┐                                     │
│ Types message│                                     │
└──────┬───────┘                                     │
       │                                             │
       ▼                                             │
┌──────────────────────────────────────────────────┐ │
│  Firebase: conversations/{conversationId}/       │ │
│            messages/{messageId}                   │ │
│                                                  │ │
│  conversationId = sorted(patientUID, therapistUID)│
│  (ensures unique conversation per pair)          │ │
└──────────────────────────────────────────────────┘ │
       │                                             │
       │          [Real-time Firebase listener]      │
       │                                             ▼
       │                                     ┌──────────────┐
       │                                     │ Sees message │
       │                                     │ instantly    │
       │                                     └──────┬───────┘
       │                                            │
       │                                            ▼
       │                                     ┌──────────────┐
       │◄────────────────────────────────────│ Replies      │
       │         [Firebase listener]         └──────────────┘
       ▼
┌──────────────┐
│ Sees reply   │
└──────────────┘
```

---

## 11. Data Flow Summary (End-to-End)

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   PATIENT                     SYSTEM                   THERAPIST    │
│                                                                     │
│   Register ──────────────► Firebase Auth ◄──────────── Register    │
│       │                        │                           │        │
│   Enter Code ────────────► patient_requests ──────────► Accept     │
│       │                        │                           │        │
│   [Linked] ◄─────────────── Patient/ ◄────────────── [Linked]     │
│       │                   assigned_therapist               │        │
│       │                                                    │        │
│       │                                              Assign Drill  │
│       │                                                    │        │
│       │◄──────────── assigned_drills/{id} ◄────────────────┘       │
│       │                                                             │
│   Record Voice                                                      │
│       │                                                             │
│       ├──────────────► Firebase Storage (WAV)                       │
│       │                                                             │
│       └──────────────► VocalAid API /analyze                        │
│                              │                                      │
│                    ┌─────────┼─────────┐                            │
│                    │         │         │                             │
│                wav2vec2   Whisper    g2p-en                          │
│                    │         │         │                             │
│                    └─────────┼─────────┘                            │
│                              │                                      │
│   See Results ◄──────────────┘                                      │
│       │                                                             │
│       └──────────────► patient_sessions/ ──────────────► View      │
│                              │                          Progress    │
│                              │                             │        │
│                              ▼                             │        │
│                     phoneme_profiles/ ◄── /phoneme/profile │        │
│                              │                             │        │
│                              ▼                             ▼        │
│   Personalized ◄──── /phoneme/drills              AI Summary       │
│   Drills                                          /ai/summary      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 12. Games Flow

```
┌──────────────┐
│ Patient Home │
│ → Games tile │
└──────┬───────┘
       │
       ▼
┌──────────────────┐
│  Games Activity  │
│  Choose game:    │
└──────┬───────────┘
       │
       ├─────────────────────────┐
       │                         │
       ▼                         ▼
┌──────────────┐         ┌──────────────┐
│Memory Match  │         │Word Scramble │
│              │         │              │
│ Flip cards   │         │ Unscramble   │
│ to find      │         │ letters to   │
│ matching     │         │ form words   │
│ pairs        │         │              │
│              │         │ Cognitive +  │
│ Cognitive    │         │ linguistic   │
│ exercise     │         │ practice     │
└──────────────┘         └──────────────┘
```

---

## 13. Firebase Database Structure (Visual)

```
Firebase Realtime Database
│
├── users/{uid}
│   ├── name, email, userType, createdAt
│
├── Patient/{uid}
│   ├── name, last_name, email, age
│   ├── assigned_therapist (therapist UID)
│   ├── status ("pending" | "accepted")
│   ├── progress_score, Last_session
│
├── Therapist/{uid}
│   ├── name, last_name, email, experience
│   ├── code (unique 6-char), assigned_patients
│
├── therapist_patients/{therapistUid}/{patientUid}: true
│
├── patient_requests/{requestId}
│   ├── patientId, therapistId, status, timestamp
│
├── conversations/{conversationId}/messages/{msgId}
│   ├── senderId, text, timestamp, isRead
│
├── assigned_drills/{drillId}
│   ├── patientId, therapistId, title, difficulty
│   ├── targetWords, transcription, completed
│   ├── dysarthriaScore, dysarthriaPrediction
│
├── patient_sessions/{patientId}
│   ├── speechLevel ("easy"|"medium"|"hard")
│   ├── sessionCount (number)
│   └── sessions/{sessionId}
│       ├── drillTitle, dateMs, durationSeconds
│       ├── dysarthriaScore, dysarthriaPrediction
│       ├── speechTranscription, speechPhonemes
│       ├── recordingUrl
│
├── phoneme_profiles/{patientId}
│   ├── updatedAt
│   └── weakPhonemes: [{phoneme, category, avgAccuracy, ...}]
│
├── ab_test_assignments/{patientId}: "A" | "B"
│
├── progress_Reports/{reportId}
│   ├── patient_id, content, timestamp
│
└── chatbot_interaction/{id}
    ├── messages, timestamp
```
