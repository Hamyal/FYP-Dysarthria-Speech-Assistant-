"""
Smoke tests for VocalAid API (no server required).
Run from repo root:  py -3 vocalaid_api/test_smoke.py
Or from vocalaid_api:  py -3 test_smoke.py
"""
from __future__ import annotations

import io
import json
import os
import sys

# Ensure vocalaid_api is on path when run from repo root
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
os.chdir(_HERE)

import numpy as np


def main() -> int:
    import app as vocalaid

    print("=== GET /health (Flask test client) ===")
    client = vocalaid.app.test_client()
    r = client.get("/health")
    print("status", r.status_code, r.json)
    if r.status_code != 200:
        return 1

    print("\n=== preprocess_audio_sample (1s 440Hz tone) ===")
    sr = 16000
    t = np.linspace(0, 1.0, sr, dtype=np.float32)
    audio = (0.25 * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32)
    proc, sr2 = vocalaid.preprocess_audio_sample(audio, 16000, None)
    print("output samples:", proc.shape[0], "sr:", sr2)
    if proc.size == 0:
        print("FAIL: trim removed all audio")
        return 1
    print("OK")

    if not vocalaid.transcription_enabled():
        print("\nENABLE_TRANSCRIPTION is off — skipping Whisper")
        return 0

    print("\n=== transcribe_with_whisper (may download model on first run) ===")
    tr = vocalaid.transcribe_with_whisper(audio)
    print(json.dumps(tr, indent=2))
    if tr.get("success"):
        print("Whisper OK, text length:", len(tr.get("text") or ""))
    else:
        print("Whisper reported failure:", tr.get("error"))
        # Still exit 0 if whisper simply not installed — health documents it
        if tr.get("error") == "whisper_not_available":
            print("(Install: pip install openai-whisper)")
            return 0
        if tr.get("error") == "transcription_disabled":
            return 0
        return 1

    print("\n=== save_transcription_record ===")
    path = vocalaid.save_transcription_record(
        {
            "timestamp_utc": "2099-01-01T00:00:00Z",
            "patient_id": "smoke_test",
            "transcription": tr.get("text") or "",
            "transcription_success": True,
            "transcription_error": None,
            "accuracy": 50.0,
            "prediction": "test",
            "confidence": 0.5,
            "whisper_model": vocalaid.WHISPER_MODEL_SIZE,
        }
    )
    print("saved:", path)
    if path and os.path.isfile(path):
        os.unlink(path)
        print("cleaned temp json")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
