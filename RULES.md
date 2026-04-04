# Firebase rules update (VocalAid / MyA)

## Files

| File | Purpose |
|------|---------|
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

- **assigned_drills** (`assigned_drills/{drillId}`):  
  - **Read/write:** only if `patientId` or `therapistId` on that drill equals `auth.uid`.  
  - **Create:** therapist must set `therapistId` to their own uid.

- **Therapist**: unchanged pattern (indexed `code`, any signed-in user can read therapist list for code lookup; only owner can write own node).

- Top-level nodes (`conversations`, `messages`, `patient_requests`, etc.) remain **auth required** for read/write (same as before).

### Storage (`storage.rules`)

- **recordings/{patientId}/***  
  - **Read:** any signed-in user (so therapists can open download URLs for playback).  
  - **Write:** only if `request.auth.uid == patientId` (patients upload their own WAVs).

- **profile_photos/{filename}**  
  - **Read:** signed in.  
  - **Write:** only `{uid}.jpg` for the signed-in user.

## Deploy

From the folder that contains `firebase.json` (or pass paths explicitly):

```bash
firebase deploy --only database
firebase deploy --only storage
```

If you do not use Firebase CLI, paste the JSON into **Firebase Console → Realtime Database → Rules** and the storage rules into **Storage → Rules**.

## Roll back

If something breaks (e.g. a flow not covered by the new rules), temporarily restore the previous “`auth != null` everywhere” style for the affected path, then tighten again.
