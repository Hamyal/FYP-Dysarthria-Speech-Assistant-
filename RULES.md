# Firebase rules update (VocalAid / MyA)

## Files

| File | Purpose |
|------|---------|
| `firebase.json` | Firebase CLI: points **Database** + **Storage** rules at the files below |
| `database.rules.json` | **Realtime Database** security |
| `storage.rules` | **Cloud Storage** security |

## What changed

### Realtime Database (`database.rules.json`)

- **Patient** (`Patient/{uid}`):  
  - **Read:** patient themself **or** therapist whose uid matches `assigned_therapist`.  
  - **Write:** patient themself **or** user who is the **new** `assigned_therapist` (covers registration + therapist accepting a request).  
  - **Note:** `Experience` on therapists lives under `Therapist/{uid}`; no separate rule entry is required.

- **patient_sessions** (`patient_sessions/{patientId}/...`):  
  - **Read/write:** patient **or** therapist assigned to that patient (`Patient/{patientId}/assigned_therapist`).
  - **`speechLevel`:** must be `easy`, `medium`, or `hard` when present.
  - **`sessionCount`:** non‑negative number (used with transactions).
  - **`sessions/{sessionId}`:** validates `sessionId`, `patientId`, and `dateMs` match the path / types; optional fields with size caps:
    - **`speechTranscription`** (Whisper text, ≤ 15 000 chars)
    - **`speechPhonemes`** (G2P string, ≤ 25 000 chars)
    - **`recordingUrl`** (≤ 2048 chars)
    - **`note`**, **`drillTitle`**, **`dysarthriaPrediction`**, etc. (bounded strings / numbers as appropriate)
  - **`.indexOn`:** `sessions` is indexed on **`dateMs`** and **`patientId`** so `orderByChild("dateMs")` (e.g. progress / history) does not fail with “index not defined”.

- **assigned_drills** (`assigned_drills/{drillId}`):  
  - **Read/write:** only if `patientId` or `therapistId` on that drill equals `auth.uid`.  
  - **Create:** therapist must set `therapistId` to their own uid.

- **patient_drill_index** (`patient_drill_index/{patientId}/{drillId}`):  
  - **Read:** only the patient (`auth.uid === patientId`) so they can list drill ids without relying on a filtered query.  
  - **Write:** the patient **or** the therapist currently assigned to that patient (`Patient/{patientId}/assigned_therapist`).  
  - **Validate:** value must be boolean `true` (or delete). The app sets this when saving an assigned drill, alongside `assigned_drills/{drillId}`.

- **Therapist**: unchanged pattern (indexed `code`, any signed-in user can read therapist list for code lookup; only owner can write own node).

- Top-level nodes (`conversations`, `messages`, `patient_requests`, etc.) remain **auth required** for read/write (same as before).

### Storage (`storage.rules`)

- **recordings/{patientId}/{filename}**  
  - **Read:** any signed-in user (therapists need this for playback URLs; Storage rules cannot read Realtime Database to check `assigned_therapist` unless you add **custom claims** or **Cloud Functions** that mint short‑lived URLs).  
  - **Write:** only if `request.auth.uid == patientId`, file under **30 MB**, and `Content-Type` is **`audio/*`** or **`application/octet-stream`** (some clients send WAV as octet-stream).

- **profile_photos/{filename}**  
  - **Read:** signed in.  
  - **Write:** only `{uid}.jpg` for the signed-in user, image type, **&lt; 5 MB**.

## Deploy

This repo includes **`firebase.json`** pointing at `database.rules.json` and `storage.rules`. From the **MyA** project root:

```bash
firebase deploy --only database
firebase deploy --only storage
```

Or deploy both:

```bash
firebase deploy --only database,storage
```

If you do not use Firebase CLI, paste **`database.rules.json`** into **Firebase Console → Realtime Database → Rules** and **`storage.rules`** into **Storage → Rules**.

## Roll back

If something breaks (e.g. a flow not covered by the new rules), temporarily restore the previous “`auth != null` everywhere” style for the affected path, then tighten again.
