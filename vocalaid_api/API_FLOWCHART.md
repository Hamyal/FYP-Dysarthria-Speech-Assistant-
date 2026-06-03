# VocalAid API — Function Flowchart & Interactions

This document maps every function in `app.py`, what it does, and how it connects to other functions.

---

## Function Dependency Map (Overview)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         app.py — Function Call Graph                          │
└─────────────────────────────────────────────────────────────────────────────┘

ENDPOINTS (entry points from Android app)
─────────────────────────────────────────
  GET  /              → index()
  GET  /health        → health()
  POST /analyze       → analyze()
  GET  /dataset/torgo → torgo_list()
  GET  /dataset/torgo/pick → torgo_pick()
  POST /ai/summary    → ai_summary()
  POST /ai/chat       → ai_chat()
  POST /phoneme/profile → phoneme_profile()
  POST /phoneme/drills  → phoneme_drills()
  POST /ab/log        → ab_log()
  GET  /ab/results    → ab_results()

INTERNAL FUNCTIONS (called by endpoints)
────────────────────────────────────────
  load_model()
  transcription_enabled()
  phonemes_enabled()
  normalize_drill_text()
  drill_match_percent()
  get_g2p()
  _ensure_nltk_for_g2p()
  transcription_to_phonemes()
  preprocess_audio_sample()
  get_whisper_model()
  transcribe_with_whisper()
  save_transcription_record()
  load_torgo_entries()
  get_groq_client()
  _strip_stress()
  _extract_phonemes_from_text()
  compute_phoneme_accuracy()
  identify_weak_phonemes()
  generate_targeted_drills()
```

---

## 1. `index()` — GET /

```
┌──────────────┐
│  Browser/    │
│  Client hits │
│  GET /       │
└──────┬───────┘
       │
       ▼
┌──────────────────────────────────┐
│ index()                          │
│                                  │
│ Returns HTML page showing:       │
│ - "VocalAid API is running"      │
│ - Links to /health, /analyze     │
│                                  │
│ Calls: NOTHING                   │
│ Called by: Flask router           │
└──────────────────────────────────┘
```

---

## 2. `health()` — GET /health

```
┌──────────────┐
│  GET /health │
└──────┬───────┘
       │
       ▼
┌──────────────────────────────────┐
│ health()                         │
│                                  │
│ Returns JSON status:             │
│ - model_loaded (bool)            │
│ - transcription_enabled (bool)   │
│ - whisper_model_size             │
│ - phonemes_enabled (bool)        │
│ - g2p_loaded (bool)              │
│                                  │
│ Calls:                           │
│   ├── transcription_enabled()    │
│   └── phonemes_enabled()         │
│                                  │
│ Called by: Flask router           │
└──────────────────────────────────┘
```

---

## 3. `analyze()` — POST /analyze (MAIN ENDPOINT)

```
┌──────────────────┐
│ POST /analyze    │
│                  │
│ Input:           │
│ - audio (WAV)    │
│ - patient_id     │
│ - target_text    │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ analyze()                                                            │
│                                                                      │
│  Step 1: load_model()                                                │
│           │                                                          │
│           ▼                                                          │
│  ┌─────────────────────────────────────────────┐                     │
│  │ load_model()                                │                     │
│  │ - Loads wav2vec2 model + processor          │                     │
│  │ - Only loads once (singleton)               │                     │
│  │ - Calls: Wav2Vec2Processor.from_pretrained()│                     │
│  │          Wav2Vec2ForSequenceClassification   │                     │
│  └─────────────────────────────────────────────┘                     │
│                                                                      │
│  Step 2: Read audio, resample to 16kHz                               │
│           │                                                          │
│           ▼                                                          │
│  Step 3: Run wav2vec2 inference                                      │
│           - Get logits → softmax → probabilities                     │
│           - Determine prediction (dysarthric/healthy)                │
│           - Calculate speech_clarity_percent                         │
│           │                                                          │
│           ▼                                                          │
│  Step 4: transcription_enabled()?─────── NO ───► skip transcription  │
│           │                                                          │
│          YES                                                         │
│           │                                                          │
│           ▼                                                          │
│  ┌─────────────────────────────────────────────┐                     │
│  │ transcribe_with_whisper(audio)              │                     │
│  │                                             │                     │
│  │ Calls:                                      │                     │
│  │   ├── get_whisper_model()                   │                     │
│  │   │      └── whisper.load_model()           │                     │
│  │   └── preprocess_audio_sample(audio)        │                     │
│  │          ├── librosa.effects.preemphasis()   │                     │
│  │          └── librosa.effects.trim()          │                     │
│  │                                             │                     │
│  │ Returns: { success, text, error }           │                     │
│  └─────────────────────────────────────────────┘                     │
│           │                                                          │
│           ▼                                                          │
│  Step 5: phonemes_enabled()?─────── NO ───► skip phonemes            │
│           │                                                          │
│          YES                                                         │
│           │                                                          │
│           ▼                                                          │
│  ┌─────────────────────────────────────────────┐                     │
│  │ transcription_to_phonemes(transcription)    │                     │
│  │                                             │                     │
│  │ Calls:                                      │                     │
│  │   └── get_g2p()                             │                     │
│  │          └── _ensure_nltk_for_g2p()         │                     │
│  │                 └── nltk.download(...)       │                     │
│  │                                             │                     │
│  │ Returns: "S AH N B AH S" (ARPAbet string)  │                     │
│  └─────────────────────────────────────────────┘                     │
│           │                                                          │
│           ▼                                                          │
│  Step 6: Has target_text?                                            │
│           │                                                          │
│     ┌─────┴─────┐                                                    │
│    YES          NO                                                   │
│     │            │                                                   │
│     ▼            ▼                                                   │
│  ┌──────────┐  accuracy = speech_clarity_percent                     │
│  │drill_match│                                                       │
│  │_percent() │                                                       │
│  │           │                                                       │
│  │Calls:     │                                                       │
│  │├normalize_│                                                       │
│  ││drill_text│                                                       │
│  │└SequenceM.│                                                       │
│  └──────────┘                                                        │
│           │                                                          │
│           ▼                                                          │
│  Step 6b: compute_phoneme_accuracy(target, transcription)            │
│           (NEW — per-phoneme breakdown)                              │
│           │                                                          │
│           ▼                                                          │
│  Step 7: save_transcription_record(record)                           │
│           - Appends to JSONL log file                                │
│           - Writes individual JSON file                              │
│           │                                                          │
│           ▼                                                          │
│  Return JSON:                                                        │
│  { accuracy, prediction, confidence, transcription,                  │
│    phonemes, phoneme_accuracy, speech_clarity_percent }               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. `load_model()` — Singleton Model Loader

```
┌──────────────────────────────────┐
│ load_model()                     │
│                                  │
│ IF model already loaded → return │
│                                  │
│ ELSE:                            │
│   1. Wav2Vec2Processor.from_     │
│      pretrained(MODEL_PATH)      │
│   2. Wav2Vec2ForSequence         │
│      Classification.from_        │
│      pretrained(MODEL_PATH)      │
│   3. model.to(device)            │
│   4. model.eval()                │
│                                  │
│ Calls: transformers library      │
│ Called by: analyze()             │
└──────────────────────────────────┘
```

---

## 5. `transcribe_with_whisper(audio)` — Speech-to-Text

```
┌──────────────────────────────────────────────────┐
│ transcribe_with_whisper(audio_mono_16k)           │
│                                                  │
│  ┌────────────────────────────────────────┐      │
│  │ get_whisper_model()                    │      │
│  │  - Lazy loads Whisper (once)           │      │
│  │  - Size: tiny/base/small/medium/large  │      │
│  │  - Device: cuda or cpu                 │      │
│  │                                        │      │
│  │  Called by: transcribe_with_whisper()   │      │
│  └────────────────────────────────────────┘      │
│         │                                        │
│         ▼                                        │
│  ┌────────────────────────────────────────┐      │
│  │ preprocess_audio_sample(audio)         │      │
│  │  1. Convert to float32 mono            │      │
│  │  2. Peak normalize (max = 1.0)         │      │
│  │  3. Preemphasis filter                 │      │
│  │  4. Trim silence (top_db=20)           │      │
│  │  5. Optional: pad/trim to length       │      │
│  │                                        │      │
│  │  Calls: librosa.effects.preemphasis()  │      │
│  │         librosa.effects.trim()         │      │
│  │  Called by: transcribe_with_whisper()   │      │
│  └────────────────────────────────────────┘      │
│         │                                        │
│         ▼                                        │
│  model.transcribe(audio,                         │
│    temperature=0, beam_size=1,                   │
│    language="en")                                │
│         │                                        │
│         ▼                                        │
│  Return: { success: True, text: "sun bus" }      │
│                                                  │
│  Called by: analyze()                            │
└──────────────────────────────────────────────────┘
```

---

## 6. `transcription_to_phonemes(text)` — Text to Phonemes

```
┌──────────────────────────────────────────────────┐
│ transcription_to_phonemes(text)                   │
│                                                  │
│  Input: "sun bus"                                │
│         │                                        │
│         ▼                                        │
│  ┌────────────────────────────────────────┐      │
│  │ get_g2p()                              │      │
│  │  - Lazy loads g2p-en model (once)      │      │
│  │  - Calls _ensure_nltk_for_g2p() first  │      │
│  │    (downloads NLTK tagger + cmudict)   │      │
│  │  - Returns G2p() instance              │      │
│  │                                        │      │
│  │  Called by: transcription_to_phonemes() │      │
│  │             _extract_phonemes_from_text()│     │
│  └────────────────────────────────────────┘      │
│         │                                        │
│         ▼                                        │
│  g2p("sun bus")                                  │
│  → ['S', 'AH1', 'N', ' ', 'B', 'AH1', 'S']    │
│         │                                        │
│         ▼                                        │
│  Join & normalize: "S AH1 N B AH1 S"            │
│                                                  │
│  Called by: analyze()                            │
└──────────────────────────────────────────────────┘
```

---

## 7. `drill_match_percent(target, transcription)` — Text Accuracy

```
┌──────────────────────────────────────────────────┐
│ drill_match_percent(target, transcription)        │
│                                                  │
│  Input: target="sun bus miss"                    │
│         transcription="un bu mi"                 │
│         │                                        │
│         ▼                                        │
│  ┌────────────────────────────────────────┐      │
│  │ normalize_drill_text(target)           │      │
│  │ normalize_drill_text(transcription)    │      │
│  │  - Lowercase                           │      │
│  │  - Remove punctuation                  │      │
│  │  - Collapse whitespace                 │      │
│  │                                        │      │
│  │  Called by: drill_match_percent()      │      │
│  └────────────────────────────────────────┘      │
│         │                                        │
│         ▼                                        │
│  word_ratio = SequenceMatcher(words_a, words_b)  │
│  char_ratio = SequenceMatcher(str_a, str_b)      │
│  combined = 0.65 * word_ratio + 0.35 * char_ratio│
│         │                                        │
│         ▼                                        │
│  Return: 72.5 (0-100 score)                      │
│                                                  │
│  Called by: analyze()                            │
└──────────────────────────────────────────────────┘
```

---

## 8. `_extract_phonemes_from_text(text)` — Core of Personalization

```
┌──────────────────────────────────────────────────┐
│ _extract_phonemes_from_text(text)                │
│                                                  │
│  Input: "sun bus"                                │
│         │                                        │
│         ▼                                        │
│  get_g2p() → G2p instance                       │
│         │                                        │
│         ▼                                        │
│  g2p("sun bus")                                  │
│  → raw tokens: ['S','AH1','N',' ','B','AH1','S']│
│         │                                        │
│         ▼                                        │
│  ┌────────────────────────────────────────┐      │
│  │ _strip_stress(phoneme)                 │      │
│  │  - "AH1" → "AH"                       │      │
│  │  - "EY2" → "EY"                       │      │
│  │  - Removes trailing digits             │      │
│  │                                        │      │
│  │  Called by: _extract_phonemes_from_text()│     │
│  └────────────────────────────────────────┘      │
│         │                                        │
│         ▼                                        │
│  Filter out spaces/punctuation tokens            │
│         │                                        │
│         ▼                                        │
│  Return: ["S", "AH", "N", "B", "AH", "S"]       │
│                                                  │
│  Called by: compute_phoneme_accuracy()           │
└──────────────────────────────────────────────────┘
```

---

## 9. `compute_phoneme_accuracy(target_text, spoken_text)` — Per-Phoneme Scoring

```
┌──────────────────────────────────────────────────────────────────────┐
│ compute_phoneme_accuracy(target_text, spoken_text)                    │
│                                                                      │
│  Input: target="sun bus miss", spoken="un bu mi"                     │
│         │                                                            │
│         ▼                                                            │
│  target_phonemes = _extract_phonemes_from_text("sun bus miss")       │
│  → ["S","AH","N","B","AH","S","M","IH","S"]                         │
│         │                                                            │
│  spoken_phonemes = _extract_phonemes_from_text("un bu mi")           │
│  → ["AH","N","B","AH","M","AY"]                                     │
│         │                                                            │
│         ▼                                                            │
│  SequenceMatcher(target_phonemes, spoken_phonemes)                   │
│  → Find matching blocks (aligned phonemes)                           │
│         │                                                            │
│         ▼                                                            │
│  For each phoneme in target:                                         │
│    Count "expected" occurrences                                      │
│    Count "matched" occurrences (aligned with spoken)                 │
│    accuracy = matched / expected × 100                               │
│         │                                                            │
│         ▼                                                            │
│  Return: {                                                           │
│    "S":  { expected: 3, matched: 0, accuracy: 0.0 },                │
│    "AH": { expected: 2, matched: 2, accuracy: 100.0 },              │
│    "N":  { expected: 1, matched: 1, accuracy: 100.0 },              │
│    "B":  { expected: 1, matched: 1, accuracy: 100.0 },              │
│    "M":  { expected: 1, matched: 1, accuracy: 100.0 },              │
│    "IH": { expected: 1, matched: 0, accuracy: 0.0 }                 │
│  }                                                                   │
│                                                                      │
│  Calls: _extract_phonemes_from_text() × 2                           │
│  Called by: identify_weak_phonemes(), analyze(), phoneme_profile()   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 10. `identify_weak_phonemes(sessions, threshold)` — Patient Profile Builder

```
┌──────────────────────────────────────────────────────────────────────┐
│ identify_weak_phonemes(sessions, threshold=60.0)                     │
│                                                                      │
│  Input: [                                                            │
│    { target_text: "sun bus", transcription: "un bu" },               │
│    { target_text: "sun bus", transcription: "un bu" },               │
│    { target_text: "fish ship", transcription: "fi ip" },             │
│  ]                                                                   │
│         │                                                            │
│         ▼                                                            │
│  FOR EACH session:                                                   │
│    ┌──────────────────────────────────────┐                          │
│    │ compute_phoneme_accuracy(target,     │                          │
│    │                         spoken)      │                          │
│    │                                      │                          │
│    │ Accumulate scores per phoneme:       │                          │
│    │   phoneme_scores["S"] = [0%, 0%, 0%] │                          │
│    │   phoneme_scores["AH"] = [100%, 100%]│                          │
│    │   phoneme_scores["SH"] = [33%, 33%]  │                          │
│    └──────────────────────────────────────┘                          │
│         │                                                            │
│         ▼                                                            │
│  FOR EACH phoneme with 2+ occurrences:                               │
│    avg = mean(scores)                                                │
│    IF avg < threshold (60%):                                         │
│      → Mark as WEAK                                                  │
│      → Look up category from PHONEME_CATEGORIES                      │
│      → Get sample words from PHONEME_WORD_MAP                        │
│         │                                                            │
│         ▼                                                            │
│  Sort by accuracy ascending (weakest first)                          │
│         │                                                            │
│         ▼                                                            │
│  Return: [                                                           │
│    { phoneme: "S",  category: "fricatives", avg_accuracy: 0.0,       │
│      occurrences: 3, sample_words: ["sun","bus","miss","see"] },      │
│    { phoneme: "SH", category: "fricatives", avg_accuracy: 33.0,      │
│      occurrences: 2, sample_words: ["ship","fish","wash","she"] }     │
│  ]                                                                   │
│                                                                      │
│  Calls: compute_phoneme_accuracy() × N sessions                     │
│  Called by: phoneme_profile(), phoneme_drills()                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 11. `generate_targeted_drills(weak_phonemes, difficulty, count)` — Drill Creator

```
┌──────────────────────────────────────────────────────────────────────┐
│ generate_targeted_drills(weak_phonemes, difficulty="medium", count=5)│
│                                                                      │
│  Input: weak_phonemes = [                                            │
│    { phoneme: "S", avg_accuracy: 0.0, ... },                         │
│    { phoneme: "SH", avg_accuracy: 33.0, ... },                       │
│  ]                                                                   │
│         │                                                            │
│         ▼                                                            │
│  FOR EACH weak phoneme (up to count):                                │
│    1. Look up PHONEME_WORD_MAP[phoneme]                              │
│       "S" → ["sun","bus","miss","see","sit","six","say","some"]      │
│                                                                      │
│    2. Pick word(s) not yet used                                      │
│                                                                      │
│    3. Based on difficulty:                                           │
│       ┌──────────────────────────────────────────┐                   │
│       │ EASY:   Single word                      │                   │
│       │         → "sun"                          │                   │
│       │                                          │                   │
│       │ MEDIUM: Two-word combo                   │                   │
│       │         → "sun miss"                     │                   │
│       │                                          │                   │
│       │ HARD:   Short sentence                   │                   │
│       │         → "Please say sun clearly"       │                   │
│       └──────────────────────────────────────────┘                   │
│         │                                                            │
│         ▼                                                            │
│  Return: [                                                           │
│    { target_text: "sun", target_phonemes: ["S"],                     │
│      difficulty: "easy",                                             │
│      rationale: "Targets weak phoneme /S/ (avg accuracy: 0.0%)" },   │
│    { target_text: "ship", target_phonemes: ["SH"],                   │
│      difficulty: "easy",                                             │
│      rationale: "Targets weak phoneme /SH/ (avg accuracy: 33.0%)" } │
│  ]                                                                   │
│                                                                      │
│  Calls: PHONEME_WORD_MAP lookup, random.choice()                    │
│  Called by: phoneme_drills()                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 12. `phoneme_profile()` — POST /phoneme/profile

```
┌──────────────────┐
│ POST /phoneme/   │
│ profile          │
│                  │
│ Input JSON:      │
│ - patient_id     │
│ - sessions[]     │
│ - threshold      │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────┐
│ phoneme_profile()                                │
│                                                  │
│  1. Parse request JSON                           │
│  2. Validate sessions array exists               │
│         │                                        │
│         ▼                                        │
│  3. identify_weak_phonemes(sessions, threshold)  │
│         │                                        │
│         ├─── calls compute_phoneme_accuracy()    │
│         │         └─── calls _extract_phonemes() │
│         │                    └─── calls get_g2p()│
│         │                                        │
│         ▼                                        │
│  4. Count total unique phonemes analyzed         │
│         │                                        │
│         ▼                                        │
│  Return JSON:                                    │
│  { patient_id, weak_phonemes: [...],             │
│    total_phonemes_analyzed, threshold, ab_group } │
│                                                  │
│  Called by: Android PhonemeProfileHelper          │
└──────────────────────────────────────────────────┘
```

---

## 13. `phoneme_drills()` — POST /phoneme/drills

```
┌──────────────────┐
│ POST /phoneme/   │
│ drills           │
│                  │
│ Input JSON:      │
│ - patient_id     │
│ - sessions[]     │
│ - difficulty     │
│ - count          │
│ - ab_group       │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ phoneme_drills()                                                     │
│                                                                      │
│  1. Parse ab_group ("A" or "B")                                      │
│         │                                                            │
│    ┌────┴────┐                                                       │
│    │         │                                                       │
│ [Group B] [Group A]                                                  │
│    │         │                                                       │
│    ▼         ▼                                                       │
│ ┌────────┐ ┌────────────────────────────────────────┐                │
│ │CONTROL:│ │ PERSONALIZED:                          │                │
│ │        │ │                                        │                │
│ │ Pick   │ │ 2. identify_weak_phonemes(sessions)    │                │
│ │ random │ │        │                               │                │
│ │ words  │ │        ▼                               │                │
│ │ from   │ │ 3. generate_targeted_drills(           │                │
│ │ all    │ │        weak_phonemes, difficulty,       │                │
│ │ pools  │ │        count)                          │                │
│ │        │ │        │                               │                │
│ └────┬───┘ │        ▼                               │                │
│      │     │    Drills targeting weak sounds         │                │
│      │     └────────────────────────────────────────┘                │
│      │              │                                                │
│      └──────────────┴──────────────────┐                             │
│                                        │                             │
│                                        ▼                             │
│  Return JSON:                                                        │
│  { patient_id, drills: [...], ab_group,                              │
│    personalized: true/false,                                         │
│    weak_phonemes_targeted: ["S","SH"] }                              │
│                                                                      │
│  Called by: Android PhonemeProfileHelper                              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 14. `torgo_list()` — GET /dataset/torgo

```
┌──────────────────────────────────┐
│ torgo_list()                     │
│                                  │
│  1. load_torgo_entries()         │
│     - Reads data/torgo_phrases   │
│       .json (once, cached)       │
│     - Parses id, transcription,  │
│       difficulty                 │
│                                  │
│  2. Filter by ?difficulty param  │
│                                  │
│  Return: { count, items: [...] } │
│                                  │
│  Calls: load_torgo_entries()     │
│  Called by: Flask router         │
└──────────────────────────────────┘
```

---

## 15. `torgo_pick()` — GET /dataset/torgo/pick

```
┌──────────────────────────────────┐
│ torgo_pick()                     │
│                                  │
│  1. load_torgo_entries()         │
│  2. Filter by difficulty         │
│  3. random.choice(pool)          │
│                                  │
│  Return: { id, transcription,    │
│            difficulty }          │
│                                  │
│  Calls: load_torgo_entries()     │
│  Called by: Android              │
│    AssignDrillActivity           │
└──────────────────────────────────┘
```

---

## 16. `ai_summary()` — POST /ai/summary

```
┌──────────────────┐
│ POST /ai/summary │
│                  │
│ Input JSON:      │
│ - patientId      │
│ - role           │
│ - question       │
│ - patient {}     │
│ - sessions []    │
│ - drills []      │
│ - reports []     │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────┐
│ ai_summary()                                     │
│                                                  │
│  1. get_groq_client()                            │
│     - Initializes Groq SDK (once)                │
│     - Uses GROQ_API_KEY from env                 │
│                                                  │
│  2. Build prompt with:                           │
│     - Role instruction (patient vs therapist)    │
│     - Patient info, sessions, drills, reports    │
│     - Optional user question                     │
│                                                  │
│  3. groq.chat.completions.create(                │
│       model="llama-3.1-8b-instant",              │
│       messages=[system, user_prompt],            │
│       temperature=0.3                            │
│     )                                            │
│                                                  │
│  Return: { summary: "..." }                      │
│                                                  │
│  Calls: get_groq_client()                        │
│  Called by: Android AiSummaryActivity            │
└──────────────────────────────────────────────────┘
```

---

## 17. `ai_chat()` — POST /ai/chat

```
┌──────────────────┐
│ POST /ai/chat    │
│                  │
│ Input JSON:      │
│ - messages []    │
│ - role           │
│ - therapist_     │
│   context        │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────┐
│ ai_chat()                                        │
│                                                  │
│  1. get_groq_client()                            │
│                                                  │
│  2. Build messages:                              │
│     - System: DYSPHONIA_AGENT_SYSTEM prompt      │
│       (+ therapist_context if provided)          │
│     - Conversation history from input            │
│                                                  │
│  3. groq.chat.completions.create(                │
│       model="llama-3.1-8b-instant",              │
│       messages=[system + history],               │
│       temperature=0.5                            │
│     )                                            │
│                                                  │
│  Return: { reply: "..." }                        │
│                                                  │
│  Calls: get_groq_client()                        │
│  Called by: Android AgentChatActivity            │
└──────────────────────────────────────────────────┘
```

---

## 18. `ab_log()` — POST /ab/log

```
┌──────────────────────────────────┐
│ ab_log()                         │
│                                  │
│  Input: { patient_id, ab_group,  │
│    event, accuracy,              │
│    phonemes_targeted }           │
│                                  │
│  1. Build record with timestamp  │
│  2. Append to ab_test_log.jsonl  │
│                                  │
│  Return: { status: "logged" }    │
│                                  │
│  Calls: NOTHING (file I/O only)  │
│  Called by: Android              │
│    RecordDrillActivity           │
│    (via PhonemeProfileHelper)    │
└──────────────────────────────────┘
```

---

## 19. `ab_results()` — GET /ab/results

```
┌──────────────────────────────────────────────────┐
│ ab_results()                                     │
│                                                  │
│  1. Read ab_test_log.jsonl                       │
│  2. Group by ab_group (A vs B)                   │
│  3. Filter to "drill_completed" events           │
│  4. Compute per group:                           │
│     - sessions count                             │
│     - avg_accuracy                               │
│     - median_accuracy                            │
│     - min/max                                    │
│  5. If 5+ samples per group:                     │
│     - Compute t-statistic                        │
│     - mean_diff (A - B)                          │
│     - likely_significant (|t| > 1.96)            │
│                                                  │
│  Return: { groups: {A:{...}, B:{...}},           │
│            significance: {...} }                  │
│                                                  │
│  Calls: NOTHING (file I/O + math)               │
│  Called by: test_ab.py, researcher/therapist     │
└──────────────────────────────────────────────────┘
```

---

## 20. Complete Function Interaction Diagram

```
                          ANDROID APP
                              │
          ┌───────────────────┼───────────────────────┐
          │                   │                       │
          ▼                   ▼                       ▼
    ┌───────────┐      ┌───────────┐          ┌───────────┐
    │  /analyze │      │ /phoneme/ │          │  /ai/     │
    │           │      │  profile  │          │  summary  │
    └─────┬─────┘      │  drills   │          │  chat     │
          │            └─────┬─────┘          └─────┬─────┘
          │                  │                      │
          │                  │                      │
  ┌───────┴────────┐   ┌────┴──────────┐     ┌────┴────────┐
  │                │   │               │     │             │
  ▼                ▼   ▼               ▼     ▼             │
load_model()  transcribe_  identify_   generate_  get_groq_ │
  │           with_whisper  weak_       targeted   client()  │
  │               │        phonemes()  _drills()      │     │
  │               │            │           │          │     │
  │          ┌────┴────┐       │           │          ▼     │
  │          │         │       │           │     Groq API   │
  │          ▼         ▼       ▼           │    (LLaMA 3.1) │
  │    get_whisper  preprocess compute_    │               │
  │    _model()     _audio_    phoneme_    │               │
  │                 sample()   accuracy()  │               │
  │                                │       │               │
  │                                ▼       │               │
  │                    _extract_phonemes   │               │
  │                    _from_text()        │               │
  │                          │             │               │
  │                          ▼             │               │
  │                      get_g2p()         │               │
  │                          │             │               │
  │                          ▼             ▼               │
  │                  _ensure_nltk_    PHONEME_WORD_MAP     │
  │                  _for_g2p()       (lookup table)       │
  │                                                       │
  ▼                                                       │
wav2vec2 model                                            │
(transformers)                                            │
                                                          │
  Also called by /analyze:                                │
  ┌─────────────────────────────────────────┐             │
  │ drill_match_percent()                   │             │
  │    └── normalize_drill_text()           │             │
  │                                         │             │
  │ transcription_to_phonemes()             │             │
  │    └── get_g2p()                        │             │
  │                                         │             │
  │ save_transcription_record()             │             │
  │    (writes .jsonl + .json files)        │             │
  └─────────────────────────────────────────┘             │
                                                          │
  Also: load_torgo_entries()                              │
        └── called by torgo_list(), torgo_pick()          │
            └── reads data/torgo_phrases.json             │
```

---

## Summary Table: Who Calls Whom

| Function | Called By | Calls |
|----------|-----------|-------|
| `index()` | GET / | nothing |
| `health()` | GET /health | `transcription_enabled()`, `phonemes_enabled()` |
| `analyze()` | POST /analyze | `load_model()`, `transcribe_with_whisper()`, `transcription_to_phonemes()`, `drill_match_percent()`, `compute_phoneme_accuracy()`, `save_transcription_record()` |
| `load_model()` | `analyze()` | transformers library |
| `transcribe_with_whisper()` | `analyze()` | `get_whisper_model()`, `preprocess_audio_sample()` |
| `get_whisper_model()` | `transcribe_with_whisper()` | whisper library |
| `preprocess_audio_sample()` | `transcribe_with_whisper()` | librosa |
| `transcription_to_phonemes()` | `analyze()` | `get_g2p()` |
| `get_g2p()` | `transcription_to_phonemes()`, `_extract_phonemes_from_text()` | `_ensure_nltk_for_g2p()`, g2p_en library |
| `_ensure_nltk_for_g2p()` | `get_g2p()` | nltk.download() |
| `drill_match_percent()` | `analyze()` | `normalize_drill_text()` |
| `normalize_drill_text()` | `drill_match_percent()` | nothing |
| `save_transcription_record()` | `analyze()` | file I/O |
| `_strip_stress()` | `_extract_phonemes_from_text()` | nothing |
| `_extract_phonemes_from_text()` | `compute_phoneme_accuracy()` | `get_g2p()`, `_strip_stress()` |
| `compute_phoneme_accuracy()` | `identify_weak_phonemes()`, `analyze()`, `phoneme_profile()` | `_extract_phonemes_from_text()` × 2 |
| `identify_weak_phonemes()` | `phoneme_profile()`, `phoneme_drills()` | `compute_phoneme_accuracy()` |
| `generate_targeted_drills()` | `phoneme_drills()` | PHONEME_WORD_MAP lookup |
| `phoneme_profile()` | POST /phoneme/profile | `identify_weak_phonemes()`, `compute_phoneme_accuracy()` |
| `phoneme_drills()` | POST /phoneme/drills | `identify_weak_phonemes()`, `generate_targeted_drills()` |
| `load_torgo_entries()` | `torgo_list()`, `torgo_pick()` | file I/O (JSON) |
| `torgo_list()` | GET /dataset/torgo | `load_torgo_entries()` |
| `torgo_pick()` | GET /dataset/torgo/pick | `load_torgo_entries()` |
| `get_groq_client()` | `ai_summary()`, `ai_chat()` | groq library |
| `ai_summary()` | POST /ai/summary | `get_groq_client()` |
| `ai_chat()` | POST /ai/chat | `get_groq_client()` |
| `ab_log()` | POST /ab/log | file I/O |
| `ab_results()` | GET /ab/results | file I/O + math |
| `transcription_enabled()` | `analyze()`, `health()` | os.environ |
| `phonemes_enabled()` | `analyze()`, `health()`, `get_g2p()` | os.environ |
