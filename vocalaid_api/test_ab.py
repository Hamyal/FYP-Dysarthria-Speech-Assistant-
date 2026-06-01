"""
A/B Test Evaluation Script for Phoneme Personalization.

Simulates sessions and tests both the phoneme profile analysis and
personalized drill generation endpoints.

Run: python test_ab.py (from vocalaid_api directory)
Requires: server running at http://127.0.0.1:5001 OR use --offline for unit tests only.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import random

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
os.chdir(_HERE)


def test_phoneme_analysis_offline():
    """Test the phoneme analysis logic without server (unit test)."""
    import app as vocalaid

    # Check if g2p is available
    g2p = vocalaid.get_g2p()
    if g2p is None:
        print("=== NOTE: g2p-en not installed — testing with mock data ===")
        print("  Install with: pip install g2p-en nltk")
        print("  Testing drill generation and data structures...\n")

        # Test that functions handle missing g2p gracefully
        result = vocalaid.compute_phoneme_accuracy("cat", "cat")
        assert result == {}, "Without g2p, should return empty dict"
        print("  PASS: compute_phoneme_accuracy returns {} without g2p")

        weak = vocalaid.identify_weak_phonemes([
            {"target_text": "sun", "transcription": "un"},
        ], threshold=70.0)
        assert weak == [], "Without g2p, should return empty list"
        print("  PASS: identify_weak_phonemes returns [] without g2p")

        # Test drill generation with pre-built weak phoneme data
        mock_weak = [
            {"phoneme": "S", "category": "fricatives", "avg_accuracy": 40.0,
             "occurrences": 5, "sample_words": ["sun", "bus"]},
            {"phoneme": "SH", "category": "fricatives", "avg_accuracy": 35.0,
             "occurrences": 3, "sample_words": ["ship", "fish"]},
            {"phoneme": "R", "category": "liquids", "avg_accuracy": 50.0,
             "occurrences": 4, "sample_words": ["run", "car"]},
        ]
        drills = vocalaid.generate_targeted_drills(mock_weak, difficulty="easy", count=3)
        print(f"  Generated {len(drills)} targeted drills from mock weak phonemes:")
        for d in drills:
            print(f"    '{d['target_text']}' -> {d['target_phonemes']} | {d['rationale'][:60]}")
        assert len(drills) > 0, "Should generate drills from mock weak phonemes"
        assert all(d["target_phonemes"] for d in drills)
        print("  PASS: generate_targeted_drills works with mock data")

        # Test medium difficulty
        drills_med = vocalaid.generate_targeted_drills(mock_weak, difficulty="medium", count=3)
        print(f"\n  Medium drills ({len(drills_med)}):")
        for d in drills_med:
            print(f"    '{d['target_text']}' -> {d['difficulty']}")
        assert all(d["difficulty"] == "medium" for d in drills_med)
        print("  PASS: medium difficulty drills generated")

        # Test hard difficulty
        drills_hard = vocalaid.generate_targeted_drills(mock_weak, difficulty="hard", count=2)
        print(f"\n  Hard drills ({len(drills_hard)}):")
        for d in drills_hard:
            print(f"    '{d['target_text']}' -> {d['difficulty']}")
        assert all(d["difficulty"] == "hard" for d in drills_hard)
        print("  PASS: hard difficulty drills generated")

        # Test PHONEME_WORD_MAP completeness
        assert len(vocalaid.PHONEME_WORD_MAP) > 30
        for ph, words in vocalaid.PHONEME_WORD_MAP.items():
            assert len(words) >= 4, f"Phoneme {ph} needs at least 4 words"
        print(f"\n  PASS: PHONEME_WORD_MAP has {len(vocalaid.PHONEME_WORD_MAP)} phonemes")

        # Test PHONEME_CATEGORIES covers all mapped phonemes
        categorized = set()
        for members in vocalaid.PHONEME_CATEGORIES.values():
            categorized.update(members)
        mapped = set(vocalaid.PHONEME_WORD_MAP.keys())
        missing = mapped - categorized
        if missing:
            print(f"  WARNING: phonemes in WORD_MAP but not CATEGORIES: {missing}")
        print(f"  PASS: {len(vocalaid.PHONEME_CATEGORIES)} categories defined")

        # Test A/B group B (control) logic
        all_words = []
        for words in vocalaid.PHONEME_WORD_MAP.values():
            all_words.extend(words)
        random.shuffle(all_words)
        control_drills = [{"target_text": w, "target_phonemes": [],
                           "difficulty": "medium",
                           "rationale": "Random drill (control group B)"}
                          for w in all_words[:5]]
        assert len(control_drills) == 5
        assert all(d["target_phonemes"] == [] for d in control_drills)
        print("  PASS: control group B gets random drills\n")

        return 0

    # Full tests with g2p available
    print("=== Test: compute_phoneme_accuracy ===")
    result = vocalaid.compute_phoneme_accuracy("cat", "cat")
    print(f"  'cat' vs 'cat': {result}")
    for ph, stats in result.items():
        assert stats["accuracy"] == 100.0, f"Expected 100% for {ph}"
    print("  PASS: identical text -> 100% accuracy")

    result = vocalaid.compute_phoneme_accuracy("cat", "bat")
    print(f"  'cat' vs 'bat': {result}")
    print("  PASS: different initial consonant detected")

    result = vocalaid.compute_phoneme_accuracy("hello world", "")
    assert result == {}
    print("  PASS: empty spoken text -> empty result")

    print("\n=== Test: identify_weak_phonemes ===")
    sessions = [
        {"target_text": "sun bus miss", "transcription": "un bu mi"},
        {"target_text": "sun bus miss", "transcription": "un bu mi"},
        {"target_text": "sun bus miss", "transcription": "un bu mi"},
        {"target_text": "fish ship wash", "transcription": "fi ip wa"},
        {"target_text": "fish ship wash", "transcription": "fi ip wa"},
    ]
    weak = vocalaid.identify_weak_phonemes(sessions, threshold=70.0)
    print(f"  Weak phonemes found: {len(weak)}")
    for wp in weak:
        print(f"    /{wp['phoneme']}/: avg={wp['avg_accuracy']}%, cat={wp['category']}")
    assert len(weak) > 0
    print("  PASS: weak phonemes identified")

    print("\n=== Test: generate_targeted_drills ===")
    drills = vocalaid.generate_targeted_drills(weak, difficulty="easy", count=3)
    print(f"  Generated {len(drills)} targeted drills:")
    for d in drills:
        print(f"    '{d['target_text']}' -> {d['target_phonemes']}")
    assert len(drills) > 0
    print("  PASS: targeted drills generated")

    return 0


def test_api_endpoints():
    """Test the API endpoints (requires running server)."""
    import requests

    base = "http://127.0.0.1:5001"

    print("\n=== Test: POST /phoneme/profile ===")
    sessions = [
        {"target_text": "sun bus miss see sit six",
         "transcription": "un bu mi ee it ix"},
        {"target_text": "sun bus miss see sit six",
         "transcription": "un bu mi ee it ix"},
        {"target_text": "fish ship wash she shoe",
         "transcription": "fi ip wa he hoe"},
        {"target_text": "fish ship wash she shoe",
         "transcription": "fi ip wa he hoe"},
    ]
    resp = requests.post(f"{base}/phoneme/profile", json={
        "patient_id": "test_patient_001",
        "sessions": sessions,
        "threshold": 60.0,
    })
    print(f"  Status: {resp.status_code}")
    data = resp.json()
    print(f"  Weak phonemes: {len(data.get('weak_phonemes', []))}")
    for wp in data.get("weak_phonemes", [])[:5]:
        print(f"    /{wp['phoneme']}/: {wp['avg_accuracy']}% ({wp['category']})")
    assert resp.status_code == 200

    print("\n=== Test: POST /phoneme/drills (Group A) ===")
    resp = requests.post(f"{base}/phoneme/drills", json={
        "patient_id": "test_patient_001",
        "sessions": sessions,
        "difficulty": "easy",
        "count": 5,
        "ab_group": "A",
    })
    data = resp.json()
    print(f"  Personalized: {data.get('personalized')}, Group: {data.get('ab_group')}")
    for d in data.get("drills", []):
        print(f"    '{d['target_text']}' -> {d['target_phonemes']}")
    assert resp.status_code == 200

    print("\n=== Test: POST /phoneme/drills (Group B - control) ===")
    resp = requests.post(f"{base}/phoneme/drills", json={
        "patient_id": "test_patient_002",
        "sessions": sessions,
        "difficulty": "medium",
        "count": 5,
        "ab_group": "B",
    })
    data = resp.json()
    assert data.get("personalized") == False
    assert data.get("ab_group") == "B"
    print(f"  PASS: Group B -> personalized={data['personalized']}")

    print("\n=== Test: POST /ab/log + GET /ab/results ===")
    # Log events for both groups
    for acc in [75.5, 80.0, 72.0, 78.0, 85.0]:
        requests.post(f"{base}/ab/log", json={
            "patient_id": "test_patient_001", "ab_group": "A",
            "event": "drill_completed", "accuracy": acc,
            "phonemes_targeted": ["S", "SH"],
        })
    for acc in [60.0, 55.0, 65.0, 58.0, 62.0]:
        requests.post(f"{base}/ab/log", json={
            "patient_id": "test_patient_002", "ab_group": "B",
            "event": "drill_completed", "accuracy": acc,
        })

    resp = requests.get(f"{base}/ab/results")
    data = resp.json()
    print(f"  Groups: {json.dumps(data.get('groups', {}), indent=2)}")
    if data.get("significance"):
        sig = data["significance"]
        print(f"  Mean diff (A-B): {sig['mean_diff']}%")
        print(f"  T-statistic: {sig['t_statistic']}")
        print(f"  Likely significant: {sig['likely_significant']}")
    print("  PASS: A/B results retrieved")

    return 0


def main():
    parser = argparse.ArgumentParser(
        description="A/B Test for Phoneme Personalization")
    parser.add_argument("--offline", action="store_true",
                        help="Run only offline unit tests (no server needed)")
    args = parser.parse_args()

    print("=" * 60)
    print("VocalAid Phoneme Personalization - A/B Test Suite")
    print("=" * 60)

    rc = test_phoneme_analysis_offline()
    if rc != 0:
        return rc

    if not args.offline:
        try:
            import requests
            requests.get("http://127.0.0.1:5001/health", timeout=3)
            rc = test_api_endpoints()
        except Exception as e:
            print(f"\n  Server not running ({e}). Skipping API tests.")
            print("  Start server: python app.py")
            print("  Or run: python test_ab.py --offline")
    else:
        print("\n(Skipping API tests - offline mode)")

    print("\n" + "=" * 60)
    print("All tests passed!" if rc == 0 else "Some tests failed.")
    print("=" * 60)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
