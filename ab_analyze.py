r"""
Analyze in-app A/B test results logged by the Android app to Firebase RTDB (ab_events/).

Reads ab_events/<experiment> over the public RTDB REST endpoint and compares variant A vs B:
  - participants (unique patients per variant)
  - drills completed (event count)         <- the Experiment-1 "winner" metric
  - avg score per variant

Usage:
  python ab_analyze.py                       # experiment: feedback_style
  python ab_analyze.py --experiment drill_source
  python ab_analyze.py --db https://YOUR-db.firebaseio.com

Note: needs public read on ab_events in your RTDB rules. If you get null/permission denied,
temporarily allow read on that node, or export the node from the Firebase console.
"""
import argparse
import json
import urllib.error
import urllib.request

DEFAULT_DB = "https://fypproject-487fb-default-rtdb.firebaseio.com"

# Human-readable variant labels per experiment, so the "winner" line reads correctly.
VARIANT_LABELS = {
    "feedback_style": {"A": "numeric accuracy", "B": "gamified feedback"},
    "phoneme_display": {"A": "phoneme breakdown shown", "B": "phoneme breakdown hidden"},
}


def fetch(db, experiment, auth=None, file=None):
    if file:
        with open(file, "r", encoding="utf-8") as f:
            return json.loads(f.read() or "null")
    url = f"{db}/ab_events/{experiment}.json"
    if auth:
        url += f"?auth={auth}"
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            return json.loads(r.read().decode("utf-8") or "null")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            print("HTTP 401: RTDB read needs auth. Either:")
            print("  - Firebase console > Realtime Database > ab_events > Export JSON, then:")
            print("      python ab_analyze.py --file ab_events_export.json")
            print("  - or pass a token/DB-secret:  python ab_analyze.py --auth <token>")
        else:
            print(f"HTTP {e.code}: {e.reason}")
        raise SystemExit(1)


def main():
    ap = argparse.ArgumentParser(description="Compare A/B variants from Firebase ab_events")
    ap.add_argument("--experiment", default="feedback_style")
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--auth", default=None, help="RTDB auth token or DB secret (for read)")
    ap.add_argument("--file", default=None, help="local JSON exported from the Firebase console")
    args = ap.parse_args()

    data = fetch(args.db, args.experiment, auth=args.auth, file=args.file)
    if not data:
        print(f"No events at ab_events/{args.experiment} (got: {data!r}).")
        print("Record a few drills in the app first, or check RTDB read rules.")
        return

    rows = list(data.values()) if isinstance(data, dict) else list(data)
    by = {"A": [], "B": []}
    for row in rows:
        if not isinstance(row, dict):
            continue
        v = row.get("variant")
        if v in by:
            by[v].append(row)

    print(f"Experiment: {args.experiment}   (total events: {len(rows)})\n")
    print(f"{'variant':<9}{'participants':>14}{'drills_done':>14}{'avg_score':>12}")
    summary = {}
    for v in ("A", "B"):
        evs = by[v]
        users = {e.get("uid") for e in evs if e.get("uid")}
        scores = [float(e.get("value", 0) or 0) for e in evs if e.get("event") == "drill_completed"]
        completed = len(scores)
        avg = sum(scores) / len(scores) if scores else 0.0
        per_user = completed / len(users) if users else 0.0
        summary[v] = {"users": len(users), "completed": completed, "avg": avg, "per_user": per_user}
        print(f"{v:<9}{len(users):>14}{completed:>14}{avg:>12.1f}")

    a, b = summary["A"], summary["B"]
    labels = VARIANT_LABELS.get(args.experiment, {"A": "Variant A", "B": "Variant B"})
    print(f"\nDrills completed per participant:  A={a['per_user']:.2f}   B={b['per_user']:.2f}")
    if a["users"] and b["users"]:
        if b["per_user"] > a["per_user"]:
            print(f">>> Variant B ({labels['B']}) leads on engagement.")
        elif a["per_user"] > b["per_user"]:
            print(f">>> Variant A ({labels['A']}) leads on engagement.")
        else:
            print(">>> Tie so far.")
    print("\nNOTE: needs many participants to be statistically meaningful - small N is only a mechanism check.")


if __name__ == "__main__":
    main()
