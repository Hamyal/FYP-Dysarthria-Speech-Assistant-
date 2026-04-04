"""
VocalAid Dysarthria Speech Analysis API
- POST /analyze: speech clarity/dysarthria (wav2vec2).
- GET /dataset/torgo: list TORGO-style phrases (?difficulty=easy|medium|hard).
- GET /dataset/torgo/pick: random phrase with transcription for therapist drill assignment.
- POST /ai/summary: patient history summary and suggestions (Groq).
Run: python app.py
Set GROQ_API_KEY (and optional LLM_MODEL) in env or .env. Emulator: http://10.0.2.2:5001
Replace data/torgo_phrases.json with your TORGO export (id, transcription, difficulty) if needed.
"""
import os
import io
import json
import random
import numpy as np
from dotenv import load_dotenv

# Load environment variables from .env if present
load_dotenv()

# Groq: key and model (e.g. llama-3.1-8b-instant or groq/llama-3.1-8b-instant)
GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "llama-3.1-8b-instant")
from flask import Flask, request, jsonify
from transformers import Wav2Vec2ForSequenceClassification, Wav2Vec2Processor
import torch
import soundfile as sf

MODEL_PATH = os.environ.get(
    "VOCALAID_MODEL_PATH",
    r"C:\Users\hamya\Downloads\wav2vec2_dysarthria_model_wav2vec2-base"
)
app = Flask(__name__)
device = "cuda" if torch.cuda.is_available() else "cpu"

# Load model and processor
processor = None
model = None

def load_model():
    global processor, model
    if model is not None:
        return
    print(f"Loading model from {MODEL_PATH}...")
    processor = Wav2Vec2Processor.from_pretrained(MODEL_PATH)
    model = Wav2Vec2ForSequenceClassification.from_pretrained(MODEL_PATH)
    model.to(device)
    model.eval()
    print("Model loaded.")

@app.route("/", methods=["GET"])
def index():
    """Browser-friendly root so http://127.0.0.1:5001/ is not a blank 404."""
    return (
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>VocalAid API</title></head>"
        "<body style='font-family:system-ui;padding:1.5rem;max-width:40rem'>"
        "<h1>VocalAid API</h1>"
        "<p>Server is running. This is not a website — it is a REST API for the MyA Android app.</p>"
        "<ul>"
        "<li><a href='/health'>GET /health</a> — status JSON</li>"
        "<li>POST /analyze — WAV upload (used by the app)</li>"
        "</ul>"
        "<p><strong>Android emulator:</strong> use <code>http://10.0.2.2:5001</code> as the API base URL.</p>"
        "</body></html>",
        200,
        {"Content-Type": "text/html; charset=utf-8"},
    )

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "model_loaded": model is not None})

@app.route("/analyze", methods=["POST"])
def analyze():
    load_model()
    if "audio" not in request.files:
        return jsonify({"error": "No audio file"}), 400
    file = request.files["audio"]
    if file.filename == "":
        return jsonify({"error": "Empty filename"}), 400

    try:
        audio_bytes = file.read()
        audio, sr = sf.read(io.BytesIO(audio_bytes))
        if sr != 16000:
            try:
                import librosa
                audio = librosa.resample(audio.astype(float), orig_sr=sr, target_sr=16000)
            except ImportError:
                from scipy import signal
                num_samples = int(len(audio) * 16000 / sr)
                audio = signal.resample(audio.astype(float), num_samples)
            sr = 16000
        if len(audio.shape) > 1:
            audio = audio.mean(axis=1)
        inputs = processor(audio, sampling_rate=16000, return_tensors="pt", padding=True)
        inputs = {k: v.to(device) for k, v in inputs.items()}
        with torch.no_grad():
            logits = model(**inputs).logits
        probs = torch.softmax(logits, dim=-1).cpu().numpy()[0]
        id2label = model.config.id2label if hasattr(model.config, 'id2label') else {0: "dysarthric", 1: "healthy"}
        if not isinstance(id2label, dict):
            id2label = {int(k): str(v) for k, v in id2label.items()}
        pred_id = int(np.argmax(probs))
        prediction = id2label.get(pred_id, "healthy" if pred_id == 1 else "dysarthric")
        confidence = float(probs[pred_id])

        # Accuracy = probability of "healthy" / "normal" / "clear" class (fine-tuned wav2vec convention: clearer speech = higher accuracy)
        healthy_id = None
        for k, v in id2label.items():
            v_str = str(v).lower()
            if "healthy" in v_str or "normal" in v_str or "clear" in v_str or v_str == "1":
                healthy_id = int(k)
                break
        if healthy_id is None and len(probs) > 1:
            healthy_id = 1  # fallback: often class 1 is healthy in binary dysarthria models
        accuracy = (float(probs[healthy_id]) * 100.0) if healthy_id is not None else (float(probs[pred_id]) * 100.0)
        accuracy = max(0.0, min(100.0, round(accuracy, 1)))

        return jsonify({
            "accuracy": float(accuracy),
            "prediction": str(prediction),
            "confidence": round(confidence, 4)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ---------- TORGO-style phrase dataset (transcriptions for therapist-assigned drills) ----------
TORGO_DATA_PATH = os.environ.get(
    "TORGO_DATA_JSON",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "torgo_phrases.json"),
)
_torgo_entries = None


def load_torgo_entries():
    """Load list of {id, transcription, difficulty} from JSON. Replace file with your TORGO export if needed."""
    global _torgo_entries
    if _torgo_entries is not None:
        return _torgo_entries
    _torgo_entries = []
    try:
        with open(TORGO_DATA_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, list):
            for row in data:
                if not isinstance(row, dict):
                    continue
                tid = str(row.get("id", "")).strip()
                trans = str(row.get("transcription", "")).strip()
                diff = str(row.get("difficulty", "medium")).strip().lower()
                if diff not in ("easy", "medium", "hard"):
                    diff = "medium"
                if tid and trans:
                    _torgo_entries.append({"id": tid, "transcription": trans, "difficulty": diff})
    except FileNotFoundError:
        print(f"TORGO dataset not found at {TORGO_DATA_PATH} — create it or set TORGO_DATA_JSON.")
    except Exception as e:
        print(f"Failed to load TORGO dataset: {e}")
    return _torgo_entries


@app.route("/dataset/torgo", methods=["GET"])
def torgo_list():
    """List phrases, optional ?difficulty=easy|medium|hard"""
    entries = load_torgo_entries()
    diff = (request.args.get("difficulty") or "").strip().lower()
    if diff in ("easy", "medium", "hard"):
        entries = [e for e in entries if e["difficulty"] == diff]
    return jsonify({"count": len(entries), "items": entries})


@app.route("/dataset/torgo/pick", methods=["GET"])
def torgo_pick():
    """Pick one random phrase for the selected difficulty (for therapist drill assignment)."""
    entries = load_torgo_entries()
    if not entries:
        return jsonify({"error": "TORGO dataset empty or missing. Check data/torgo_phrases.json"}), 503
    diff = (request.args.get("difficulty") or "medium").strip().lower()
    if diff not in ("easy", "medium", "hard"):
        diff = "medium"
    pool = [e for e in entries if e["difficulty"] == diff]
    if not pool:
        pool = entries
    choice = random.choice(pool)
    return jsonify({
        "id": choice["id"],
        "transcription": choice["transcription"],
        "difficulty": choice["difficulty"],
    })


# ---------- Groq AI: patient history summary & suggestions ----------
groq_client = None

def get_groq_client():
    global groq_client
    if groq_client is None and GROQ_API_KEY:
        try:
            from groq import Groq
            groq_client = Groq(api_key=GROQ_API_KEY)
        except Exception as e:
            print("Groq init failed:", e)
    return groq_client

@app.route("/ai/summary", methods=["POST"])
def ai_summary():
    """POST JSON: patientId, role (patient|therapist), question (optional), patient, sessions, drills, reports."""
    if not get_groq_client():
        return jsonify({"error": "Groq API not configured. Set GROQ_API_KEY."}), 503
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400
    patient_id = data.get("patientId") or ""
    role = (data.get("role") or "patient").lower()
    if role not in ("patient", "therapist"):
        role = "patient"
    question = (data.get("question") or "").strip()
    patient = data.get("patient") or {}
    sessions = data.get("sessions") or []
    drills = data.get("drills") or []
    reports = data.get("reports") or []

    role_instruction = (
        "Speak directly to the patient in simple, supportive language. Focus on encouragement and clear next steps."
        if role == "patient"
        else "Speak to the therapist as a professional summary. Include clinical observations, trends, and actionable suggestions."
    )
    question_line = f'\nThe user asked: "{question}".' if question else "\nProvide a useful summary and 3–5 personalized suggestions."

    prompt = f"""You are a speech-language pathology assistant for dysarthria therapy (VocalAid app).

{role_instruction}

Patient identifier: {patient_id}
Patient info (name, age, etc.): {json.dumps(patient, indent=2)}

Recent sessions (date, drill, dysarthria score/prediction): {json.dumps(sessions[:40], indent=2)}

Assigned drills (title, difficulty, completed, scores): {json.dumps(drills[:30], indent=2)}

Progress reports / therapist notes (if any): {json.dumps(reports[:20], indent=2)}
{question_line}

Respond with:
1. A short history summary (sessions attended, trends in speech clarity/dysarthria).
2. Main strengths and current challenges.
3. 3–5 concrete suggestions for practice or for the therapist.
Keep the reply concise and readable (use short paragraphs or bullet points). Do not use markdown code blocks."""

    try:
        response = get_groq_client().chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {"role": "system", "content": "You are an expert speech-language pathology assistant. Be concise and practical."},
                {"role": "user", "content": prompt},
            ],
            temperature=0.3,
        )
        summary = (response.choices[0].message.content or "").strip()
        return jsonify({"summary": summary})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ---------- Groq AI: conversational chat about dysarthria (separate from summary) ----------
DYSPHONIA_AGENT_SYSTEM = """You are a friendly, knowledgeable assistant inside the VocalAid app, focused on dysarthria (motor speech disorder) and speech therapy.

Your role:
- Answer questions about dysarthria: what it is, causes, how it affects speech, and what helps.
- Explain speech therapy concepts in simple terms (for patients) or more clinical terms when appropriate (for therapists).
- Suggest simple exercises, tips for clearer speech, and ways to practice at home.
- Be supportive and encouraging. Avoid medical diagnoses; encourage users to work with their speech-language pathologist.
- Keep replies concise (a few short paragraphs or bullet points). Use clear language. Do not use markdown code blocks."""


@app.route("/ai/chat", methods=["POST"])
def ai_chat():
    """POST JSON: messages = [ {"role": "user"|"assistant", "content": "..."}, ... ], role = "patient"|"therapist" (optional).
    Returns { "reply": "..." }. Conversational chat about dysarthria via Groq (separate from /ai/summary)."""
    if not get_groq_client():
        return jsonify({"error": "Groq API not configured. Set GROQ_API_KEY."}), 503
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400
    messages_in = data.get("messages") or []
    role = (data.get("role") or "patient").lower()
    if role not in ("patient", "therapist"):
        role = "patient"

    # Build message list for Groq: system + conversation history
    groq_messages = [{"role": "system", "content": DYSPHONIA_AGENT_SYSTEM}]
    for m in messages_in:
        r = (m.get("role") or "user").lower()
        c = (m.get("content") or "").strip()
        if not c:
            continue
        if r in ("user", "assistant"):
            groq_messages.append({"role": r, "content": c})

    if not groq_messages or groq_messages[-1].get("role") != "user":
        return jsonify({"error": "messages must end with a user message"}), 400

    try:
        response = get_groq_client().chat.completions.create(
            model=LLM_MODEL,
            messages=groq_messages,
            temperature=0.5,
        )
        reply = (response.choices[0].message.content or "").strip()
        return jsonify({"reply": reply})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    load_dotenv()  # ensure .env is loaded
    print("Environment setup complete. LLM model:", LLM_MODEL or "(none)")
    load_model()
    port = int(os.environ.get("VOCALAID_PORT", "5001"))  # 5001 avoids Windows port 5000 conflict
    print(f"VocalAid API running at http://0.0.0.0:{port} (emulator: http://10.0.2.2:{port})")
    app.run(host="0.0.0.0", port=port, debug=False)
