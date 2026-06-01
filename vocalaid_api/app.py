"""
VocalAid Dysarthria Speech Analysis API
- POST /analyze: wav2vec dysarthria score + optional Whisper text + English ARPAbet-style phonemes (g2p-en from transcript).
  Form fields: audio (file), optional patient_id, optional target_text (if set, accuracy = how close transcription matches target, 0–100; speech_clarity_percent = wav2vec clarity).
  JSON: transcription, phonemes, transcription_error, saved paths.
- GET /dataset/torgo: list TORGO-style phrases (?difficulty=easy|medium|hard).
- GET /dataset/torgo/pick: random phrase with transcription for therapist drill assignment.
- POST /ai/summary: patient history summary and suggestions (Groq).
- POST /phoneme/profile: analyze patient session history → identify weak phonemes.
- POST /phoneme/drills: generate targeted drills for weak phonemes.
Run: python app.py
Set GROQ_API_KEY (and optional LLM_MODEL) in env or .env. Emulator: http://10.0.2.2:5001
Whisper: pip install openai-whisper (see requirements.txt).
  WHISPER_MODEL_SIZE=tiny|base|small|...  WHISPER_LANGUAGE=en (or empty/auto for detection).
  WHISPER_BEAM_SIZE=1 (fast, default) or 5 (slower, often better). ENABLE_TRANSCRIPTION=0 to disable.
  ENABLE_PHONEMES=0 to skip g2p-en (English phonemes derived from Whisper text, not direct acoustic alignment).
  Transcripts append to vocalaid_api/transcriptions/transcriptions.jsonl + per-request .json.
Replace data/torgo_phrases.json with your TORGO export (id, transcription, difficulty) if needed.
A/B Testing: Set AB_TEST_GROUP env var or pass ab_group in requests. "A" = personalized phoneme drills, "B" = random (control).
"""
import os
import io
import json
import random
import re
import difflib
import numpy as np
from collections import Counter, defaultdict
from datetime import datetime, timezone
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

# Whisper transcription (preprocess like notebook; decode tuned for latency on CPU/GPU)
WHISPER_MODEL_SIZE = os.environ.get("WHISPER_MODEL_SIZE", "tiny").strip().lower()
_raw_lang = os.environ.get("WHISPER_LANGUAGE", "en").strip().lower()
WHISPER_LANGUAGE = None if _raw_lang in ("", "auto", "detect", "none") else _raw_lang


def _int_env(name: str, default: int, min_v: int = 1, max_v: int = 16) -> int:
    try:
        v = int(os.environ.get(name, str(default)))
        return max(min_v, min(max_v, v))
    except ValueError:
        return default


WHISPER_BEAM_SIZE = _int_env("WHISPER_BEAM_SIZE", 1, 1, 16)
WHISPER_BEST_OF = _int_env("WHISPER_BEST_OF", 1, 1, 8)
TRANSCRIPTIONS_DIR = os.environ.get(
    "TRANSCRIPTIONS_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "transcriptions"),
)
TRANSCRIPTIONS_JSONL = os.path.join(TRANSCRIPTIONS_DIR, "transcriptions.jsonl")
_whisper_model = None
_whisper_unavailable = False

# English G2P: ARPAbet-like phones from transcribed text (g2p-en)
_g2p_model = None
_g2p_unavailable = False

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


def transcription_enabled():
    v = os.environ.get("ENABLE_TRANSCRIPTION", "1").strip().lower()
    return v not in ("0", "false", "no", "off")


def phonemes_enabled():
    v = os.environ.get("ENABLE_PHONEMES", "1").strip().lower()
    return v not in ("0", "false", "no", "off")


def normalize_drill_text(s: str) -> str:
    """Lowercase, turn punctuation/hyphens into spaces, collapse whitespace (for drill vs transcript compare)."""
    if not s:
        return ""
    t = s.lower().strip()
    t = re.sub(r"[^\w\s]", " ", t, flags=re.UNICODE)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def drill_match_percent(target: str, transcription: str) -> float:
    """
    0–100 similarity of spoken transcription to expected drill text (word order + spelling).
    Not acoustic — uses text only so wrong words score low even if speech is clear.
    """
    a = normalize_drill_text(target)
    b = normalize_drill_text(transcription)
    if not a or not b:
        return 0.0
    wa, wb = a.split(), b.split()
    word_ratio = difflib.SequenceMatcher(None, wa, wb).ratio() if wa and wb else 0.0
    char_ratio = difflib.SequenceMatcher(None, a, b).ratio()
    combined = 0.65 * word_ratio + 0.35 * char_ratio
    return max(0.0, min(100.0, round(100.0 * combined, 1)))


def _ensure_nltk_for_g2p():
    """Ensure NLTK resources for g2p-en (NLTK 3.9+ uses averaged_perceptron_tagger_eng)."""
    import nltk

    def _grab(resource_path: str, download_id: str) -> None:
        try:
            nltk.data.find(resource_path)
        except LookupError:
            try:
                nltk.download(download_id, quiet=True)
            except Exception:
                pass

    _grab("taggers/averaged_perceptron_tagger_eng", "averaged_perceptron_tagger_eng")
    try:
        nltk.data.find("taggers/averaged_perceptron_tagger_eng")
    except LookupError:
        _grab("taggers/averaged_perceptron_tagger", "averaged_perceptron_tagger")
    _grab("corpora/cmudict", "cmudict")


def get_g2p():
    """Lazy-load g2p-en (English only). First call may download NLTK data."""
    global _g2p_model, _g2p_unavailable
    if not phonemes_enabled():
        return None
    if _g2p_unavailable:
        return None
    if _g2p_model is not None:
        return _g2p_model
    try:
        _ensure_nltk_for_g2p()
        from g2p_en import G2p
        print("Loading g2p-en (phonemes from text)...")
        _g2p_model = G2p()
        print("g2p-en ready.")
        return _g2p_model
    except Exception as e:
        print(f"g2p-en not available: {e}")
        _g2p_unavailable = True
        return None


def transcription_to_phonemes(text):
    """
    Convert Whisper transcript to a space-separated ARPAbet-style phone string.
    This is grapheme-to-phoneme on the hypothesis text, not forced alignment from audio.
    """
    if not text or not str(text).strip():
        return ""
    g2p = get_g2p()
    if g2p is None:
        return ""
    try:
        raw = str(text).strip()
        tokens = g2p(raw)
        if not tokens:
            return ""
        # g2p-en emits ' ' tokens between words; normalize runs of spaces to single spaces
        return " ".join(" ".join(str(t) for t in tokens if t is not None).split())
    except Exception:
        return ""


def preprocess_audio_sample(audio_data, sample_rate=16000, target_length=None):
    """
    Aligns with final_evaluation/audio_processing.ipynb: mono, peak normalize,
    preemphasis, trim (top_db=20). Optional fixed-length pad/trim if target_length is set.
    """
    import librosa
    audio_data = np.asarray(audio_data, dtype=np.float32)
    if audio_data.ndim > 1:
        audio_data = np.mean(audio_data, axis=1)
    peak = float(np.max(np.abs(audio_data)))
    if peak > 0:
        audio_data = audio_data / peak
    audio_data = librosa.effects.preemphasis(audio_data)
    audio_data, _ = librosa.effects.trim(audio_data, top_db=20)
    if target_length is not None:
        if len(audio_data) > target_length:
            audio_data = audio_data[:target_length]
        else:
            audio_data = np.pad(
                audio_data,
                (0, target_length - len(audio_data)),
                mode="constant",
            )
    return audio_data, sample_rate


def get_whisper_model():
    global _whisper_model, _whisper_unavailable
    if not transcription_enabled():
        return None
    if _whisper_unavailable:
        return None
    if _whisper_model is not None:
        return _whisper_model
    try:
        import whisper
        wdev = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"Loading Whisper '{WHISPER_MODEL_SIZE}' on {wdev} (first use)...")
        _whisper_model = whisper.load_model(WHISPER_MODEL_SIZE, device=wdev)
        print("Whisper ready.")
        return _whisper_model
    except Exception as e:
        print(f"Whisper not available: {e}")
        _whisper_unavailable = True
        return None


def transcribe_with_whisper(audio_mono_16k):
    """
    Preprocess (notebook-style), then Whisper on in-memory float32 audio (no temp WAV I/O).
    Decode options favor speed: temperature 0, small beam, no conditioning on prior text.
    """
    model = get_whisper_model()
    if model is None:
        if not transcription_enabled():
            return {"success": False, "text": "", "error": "transcription_disabled"}
        return {"success": False, "text": "", "error": "whisper_not_available"}

    try:
        processed, _sr = preprocess_audio_sample(
            np.asarray(audio_mono_16k, dtype=np.float32), 16000, None
        )
        if processed.size == 0:
            return {"success": False, "text": "", "error": "no_audio_after_trim"}
        # Whisper expects fp32 mono waveform ~16 kHz in roughly [-1, 1]
        audio_in = np.clip(processed.astype(np.float32), -1.0, 1.0)
        use_fp16 = torch.cuda.is_available()
        decode_kwargs = {
            "fp16": use_fp16,
            "verbose": False,
            "temperature": 0.0,
            "beam_size": WHISPER_BEAM_SIZE,
            "best_of": WHISPER_BEST_OF,
            "condition_on_previous_text": False,
        }
        if WHISPER_LANGUAGE:
            decode_kwargs["language"] = WHISPER_LANGUAGE
        try:
            result = model.transcribe(audio_in, **decode_kwargs)
        except TypeError:
            # Older whisper may not accept all decode flags — minimal path
            result = model.transcribe(
                audio_in,
                fp16=torch.cuda.is_available(),
                verbose=False,
                temperature=0.0,
            )
        text = (result.get("text") or "").strip()
        return {"success": True, "text": text, "error": None}
    except Exception as e:
        return {"success": False, "text": "", "error": str(e)}


def save_transcription_record(record):
    """Append JSONL + one pretty-printed JSON file under transcriptions/."""
    os.makedirs(TRANSCRIPTIONS_DIR, exist_ok=True)
    with open(TRANSCRIPTIONS_JSONL, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
    ts = (record.get("timestamp_utc") or "unknown").replace(":", "-")
    pid = record.get("patient_id") or "anonymous"
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in str(pid))[:64]
    fname = f"{ts}_{safe}.json".replace(" ", "_")
    fpath = os.path.join(TRANSCRIPTIONS_DIR, fname)
    try:
        with open(fpath, "w", encoding="utf-8") as f:
            json.dump(record, f, ensure_ascii=False, indent=2)
        return fpath
    except OSError:
        return None


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
        "<li>POST /analyze — WAV; optional <code>target_text</code> for drill match score. Returns accuracy, prediction, <code>transcription</code>, <code>phonemes</code></li>"
        "</ul>"
        "<p><strong>Android emulator:</strong> use <code>http://10.0.2.2:5001</code> as the API base URL.</p>"
        "</body></html>",
        200,
        {"Content-Type": "text/html; charset=utf-8"},
    )

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "model_loaded": model is not None,
        "transcription_enabled": transcription_enabled(),
        "whisper_model_size": WHISPER_MODEL_SIZE if transcription_enabled() else None,
        "whisper_language": WHISPER_LANGUAGE or "auto",
        "whisper_beam_size": WHISPER_BEAM_SIZE if transcription_enabled() else None,
        "whisper_loaded": _whisper_model is not None,
        "whisper_unavailable": _whisper_unavailable,
        "phonemes_enabled": phonemes_enabled(),
        "g2p_loaded": _g2p_model is not None,
        "g2p_unavailable": _g2p_unavailable,
    })

@app.route("/analyze", methods=["POST"])
def analyze():
    load_model()
    if "audio" not in request.files:
        return jsonify({"error": "No audio file"}), 400
    file = request.files["audio"]
    if file.filename == "":
        return jsonify({"error": "Empty filename"}), 400

    patient_id = (request.form.get("patient_id") or request.form.get("patientId") or "").strip()

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
        with torch.inference_mode():
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
        speech_clarity_percent = max(0.0, min(100.0, round(float(accuracy), 1)))

        target_text = (request.form.get("target_text") or request.form.get("drill_target") or "").strip()
        if len(target_text) > 2000:
            target_text = target_text[:2000]

        transcription = ""
        phonemes = ""
        transcription_error = None
        transcription_saved_json = None
        transcription_saved_jsonl = os.path.relpath(TRANSCRIPTIONS_JSONL, os.path.dirname(__file__))

        tr = None
        if transcription_enabled():
            tr = transcribe_with_whisper(audio)
            transcription = tr.get("text") or ""
            transcription_error = tr.get("error") if not tr.get("success") else None
            if transcription.strip() and phonemes_enabled():
                phonemes = transcription_to_phonemes(transcription)
        else:
            transcription_error = "transcription_disabled"

        if target_text:
            response_accuracy = (
                float(drill_match_percent(target_text, transcription))
                if transcription.strip()
                else 0.0
            )
        else:
            response_accuracy = float(speech_clarity_percent)

        if transcription_enabled() and tr is not None:
            ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            rec = {
                "timestamp_utc": ts,
                "patient_id": patient_id or None,
                "transcription": transcription,
                "phonemes": phonemes,
                "transcription_success": bool(tr.get("success")),
                "transcription_error": transcription_error,
                "accuracy": float(response_accuracy),
                "speech_clarity_percent": float(speech_clarity_percent),
                "drill_target": target_text or None,
                "drill_match_percent": float(response_accuracy) if target_text else None,
                "prediction": str(prediction),
                "confidence": round(confidence, 4),
                "whisper_model": WHISPER_MODEL_SIZE,
                "whisper_language": WHISPER_LANGUAGE or "auto",
                "whisper_beam_size": WHISPER_BEAM_SIZE,
            }
            path_saved = save_transcription_record(rec)
            if path_saved:
                transcription_saved_json = os.path.relpath(
                    path_saved, os.path.dirname(__file__)
                ).replace("\\", "/")
        else:
            transcription_saved_json = None

        out = {
            "accuracy": response_accuracy,
            "prediction": str(prediction),
            "confidence": round(confidence, 4),
            "transcription": transcription,
            "phonemes": phonemes,
        }
        if target_text:
            out["speech_clarity_percent"] = float(speech_clarity_percent)
            out["drill_match_percent"] = float(response_accuracy)
            # Per-phoneme accuracy breakdown (for phoneme personalization)
            if transcription.strip() and phonemes_enabled():
                per_phoneme = compute_phoneme_accuracy(target_text, transcription)
                if per_phoneme:
                    out["phoneme_accuracy"] = per_phoneme
        if transcription_error:
            out["transcription_error"] = transcription_error
        if transcription_enabled() and transcription_saved_json:
            out["transcription_saved_file"] = transcription_saved_json
            out["transcription_log"] = transcription_saved_jsonl.replace("\\", "/")
        return jsonify(out)
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

    therapist_context = (data.get("therapist_context") or "").strip()

    # Build message list for Groq: system + conversation history
    system_content = DYSPHONIA_AGENT_SYSTEM
    if therapist_context:
        system_content = DYSPHONIA_AGENT_SYSTEM + "\n\n" + therapist_context
    groq_messages = [{"role": "system", "content": system_content}]
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


# ---------- Phoneme-Level Personalization & A/B Testing ----------

# Default A/B test group: "A" = personalized phoneme drills, "B" = random (control)
DEFAULT_AB_GROUP = os.environ.get("AB_TEST_GROUP", "A").strip().upper()

# Phoneme-to-word mapping: words that heavily exercise each ARPAbet phoneme
PHONEME_WORD_MAP = {
    # Plosives
    "P": ["pop", "paper", "pepper", "happy", "apple", "cap", "tap", "sip"],
    "B": ["baby", "bubble", "rabbit", "cab", "tub", "big", "ball", "bat"],
    "T": ["top", "butter", "cat", "hat", "sit", "time", "table", "tent"],
    "D": ["dog", "daddy", "ladder", "bed", "red", "door", "day", "did"],
    "K": ["cat", "cake", "cookie", "back", "kick", "cup", "key", "kite"],
    "G": ["go", "game", "bigger", "bag", "dog", "gate", "girl", "good"],
    # Fricatives
    "F": ["fish", "coffee", "leaf", "off", "fun", "five", "four", "fast"],
    "V": ["van", "river", "love", "give", "very", "voice", "vine", "vest"],
    "TH": ["think", "bath", "tooth", "three", "thick", "thin", "thumb", "thank"],
    "DH": ["this", "mother", "breathe", "the", "that", "them", "there", "those"],
    "S": ["sun", "bus", "miss", "see", "sit", "six", "say", "some"],
    "Z": ["zoo", "buzz", "nose", "zip", "zero", "zone", "zap", "fizz"],
    "SH": ["ship", "fish", "wash", "she", "shoe", "shop", "shut", "shell"],
    "ZH": ["measure", "vision", "treasure", "pleasure", "usual", "beige"],
    "HH": ["hat", "hello", "behind", "hot", "house", "hand", "head", "home"],
    # Affricates
    "CH": ["church", "kitchen", "match", "chair", "cheese", "child", "chin", "chop"],
    "JH": ["jump", "bridge", "judge", "jar", "job", "join", "joy", "just"],
    # Nasals
    "M": ["mom", "hammer", "swim", "man", "map", "milk", "moon", "more"],
    "N": ["no", "dinner", "sun", "new", "name", "nine", "nose", "net"],
    "NG": ["sing", "ring", "long", "king", "song", "thing", "young", "bang"],
    # Liquids
    "L": ["love", "hello", "ball", "let", "look", "like", "long", "last"],
    "R": ["run", "carry", "car", "red", "rain", "road", "room", "right"],
    # Glides
    "W": ["water", "away", "win", "walk", "want", "week", "well", "wide"],
    "Y": ["yes", "beyond", "you", "year", "yet", "young", "your", "yell"],
    # Vowels (common ones patients struggle with)
    "AA": ["hot", "father", "car", "box", "top", "stop", "rock", "lot"],
    "AE": ["cat", "bat", "hat", "man", "bad", "sad", "map", "back"],
    "AH": ["but", "cup", "sun", "run", "fun", "up", "bus", "cut"],
    "AO": ["all", "ball", "call", "fall", "tall", "walk", "talk", "wall"],
    "AW": ["how", "now", "cow", "out", "down", "town", "house", "mouse"],
    "AY": ["my", "time", "like", "five", "nine", "ride", "side", "wide"],
    "EH": ["bed", "red", "head", "said", "ten", "pen", "let", "set"],
    "ER": ["her", "bird", "turn", "first", "word", "work", "girl", "hurt"],
    "EY": ["day", "say", "play", "make", "take", "name", "came", "game"],
    "IH": ["sit", "big", "did", "his", "is", "it", "six", "this"],
    "IY": ["see", "me", "he", "she", "tree", "free", "key", "be"],
    "OW": ["go", "no", "so", "home", "bone", "phone", "show", "know"],
    "OY": ["boy", "toy", "joy", "coin", "join", "oil", "point", "voice"],
    "UH": ["book", "good", "look", "put", "foot", "cook", "wood", "took"],
    "UW": ["too", "food", "moon", "room", "blue", "true", "new", "do"],
}

# Phoneme categories for grouping in reports
PHONEME_CATEGORIES = {
    "plosives": ["P", "B", "T", "D", "K", "G"],
    "fricatives": ["F", "V", "TH", "DH", "S", "Z", "SH", "ZH", "HH"],
    "affricates": ["CH", "JH"],
    "nasals": ["M", "N", "NG"],
    "liquids": ["L", "R"],
    "glides": ["W", "Y"],
    "vowels": ["AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"],
}


def _strip_stress(phoneme: str) -> str:
    """Remove stress digits from ARPAbet phoneme (e.g. 'AH0' → 'AH', 'EY1' → 'EY')."""
    return re.sub(r"\d+$", "", phoneme.upper().strip())


def _extract_phonemes_from_text(text: str) -> list:
    """Run g2p on text and return list of stripped ARPAbet phonemes (no spaces/punctuation tokens)."""
    g2p = get_g2p()
    if g2p is None or not text or not text.strip():
        return []
    try:
        tokens = g2p(text.strip())
        phonemes = []
        for t in tokens:
            if t is None:
                continue
            t_str = str(t).strip()
            if not t_str or t_str == " " or not t_str[0].isalpha():
                continue
            phonemes.append(_strip_stress(t_str))
        return phonemes
    except Exception:
        return []


def compute_phoneme_accuracy(target_text: str, spoken_text: str) -> dict:
    """
    Compare target vs spoken at phoneme level.
    Returns dict: { phoneme: { "expected": int, "matched": int, "accuracy": float } }
    """
    target_phonemes = _extract_phonemes_from_text(target_text)
    spoken_phonemes = _extract_phonemes_from_text(spoken_text)

    if not target_phonemes:
        return {}

    # Align using SequenceMatcher on phoneme sequences
    matcher = difflib.SequenceMatcher(None, target_phonemes, spoken_phonemes)
    matched_indices = set()
    for block in matcher.get_matching_blocks():
        for i in range(block.size):
            matched_indices.add(block.a + i)

    # Count per-phoneme stats
    phoneme_stats = defaultdict(lambda: {"expected": 0, "matched": 0})
    for i, ph in enumerate(target_phonemes):
        phoneme_stats[ph]["expected"] += 1
        if i in matched_indices:
            phoneme_stats[ph]["matched"] += 1

    # Compute accuracy per phoneme
    result = {}
    for ph, stats in phoneme_stats.items():
        acc = (stats["matched"] / stats["expected"] * 100.0) if stats["expected"] > 0 else 0.0
        result[ph] = {
            "expected": stats["expected"],
            "matched": stats["matched"],
            "accuracy": round(acc, 1),
        }
    return result


def identify_weak_phonemes(sessions: list, threshold: float = 60.0) -> list:
    """
    Analyze multiple session records to find consistently weak phonemes.
    Each session should have: target_text (or drillTitle/targetWords), transcription.
    Returns sorted list of { phoneme, category, avg_accuracy, occurrences, sample_words }.
    """
    phoneme_scores = defaultdict(list)  # phoneme → list of accuracy scores

    for session in sessions:
        target = (session.get("target_text") or session.get("targetWords")
                  or session.get("drillTitle") or "").strip()
        spoken = (session.get("transcription") or session.get("speechTranscription") or "").strip()
        if not target or not spoken:
            continue

        per_phoneme = compute_phoneme_accuracy(target, spoken)
        for ph, stats in per_phoneme.items():
            phoneme_scores[ph].append(stats["accuracy"])

    # Compute averages and filter by threshold
    weak = []
    for ph, scores in phoneme_scores.items():
        if len(scores) < 2:
            # Need at least 2 occurrences to be meaningful
            continue
        avg = sum(scores) / len(scores)
        if avg < threshold:
            category = "other"
            for cat, members in PHONEME_CATEGORIES.items():
                if ph in members:
                    category = cat
                    break
            sample_words = PHONEME_WORD_MAP.get(ph, [])[:4]
            weak.append({
                "phoneme": ph,
                "category": category,
                "avg_accuracy": round(avg, 1),
                "occurrences": len(scores),
                "sample_words": sample_words,
            })

    # Sort by accuracy ascending (weakest first)
    weak.sort(key=lambda x: x["avg_accuracy"])
    return weak


def generate_targeted_drills(weak_phonemes: list, difficulty: str = "medium", count: int = 5) -> list:
    """
    Generate drill words/phrases that target the patient's weak phonemes.
    Returns list of { target_text, target_phonemes, difficulty, rationale }.
    """
    if not weak_phonemes:
        return []

    drills = []
    used_words = set()

    for wp in weak_phonemes[:count]:
        ph = wp["phoneme"]
        word_pool = PHONEME_WORD_MAP.get(ph, [])
        if not word_pool:
            continue

        # Pick words not yet used
        available = [w for w in word_pool if w not in used_words]
        if not available:
            available = word_pool

        if difficulty == "easy":
            # Single word
            word = random.choice(available)
            used_words.add(word)
            drills.append({
                "target_text": word,
                "target_phonemes": [ph],
                "difficulty": "easy",
                "rationale": f"Targets weak phoneme /{ph}/ (avg accuracy: {wp['avg_accuracy']}%)",
            })
        elif difficulty == "hard":
            # Short sentence using the word
            word = random.choice(available)
            used_words.add(word)
            phrases = [
                f"Please say {word} clearly",
                f"I want to say {word} today",
                f"Can you hear me say {word}",
                f"Practice saying {word} slowly",
            ]
            drills.append({
                "target_text": random.choice(phrases),
                "target_phonemes": [ph],
                "difficulty": "hard",
                "rationale": f"Sentence drill targeting /{ph}/ (avg accuracy: {wp['avg_accuracy']}%)",
            })
        else:
            # Medium: two-word combo
            picks = random.sample(available, min(2, len(available)))
            used_words.update(picks)
            drills.append({
                "target_text": " ".join(picks),
                "target_phonemes": [ph],
                "difficulty": "medium",
                "rationale": f"Word pair targeting /{ph}/ (avg accuracy: {wp['avg_accuracy']}%)",
            })

    return drills


@app.route("/phoneme/profile", methods=["POST"])
def phoneme_profile():
    """
    POST JSON: { patient_id, sessions: [ { target_text, transcription }, ... ], threshold (optional, default 60) }
    Returns: { weak_phonemes: [...], total_phonemes_analyzed, ab_group }
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    patient_id = (data.get("patient_id") or data.get("patientId") or "").strip()
    sessions = data.get("sessions") or []
    threshold = float(data.get("threshold", 60.0))
    ab_group = (data.get("ab_group") or DEFAULT_AB_GROUP).strip().upper()

    if not sessions:
        return jsonify({"error": "sessions array required (each with target_text and transcription)"}), 400

    weak = identify_weak_phonemes(sessions, threshold)

    # Count total unique phonemes analyzed
    all_phonemes = set()
    for session in sessions:
        target = (session.get("target_text") or session.get("targetWords")
                  or session.get("drillTitle") or "").strip()
        spoken = (session.get("transcription") or session.get("speechTranscription") or "").strip()
        if target and spoken:
            per_ph = compute_phoneme_accuracy(target, spoken)
            all_phonemes.update(per_ph.keys())

    return jsonify({
        "patient_id": patient_id,
        "weak_phonemes": weak,
        "total_phonemes_analyzed": len(all_phonemes),
        "threshold": threshold,
        "ab_group": ab_group,
    })


@app.route("/phoneme/drills", methods=["POST"])
def phoneme_drills():
    """
    POST JSON: { patient_id, sessions (or weak_phonemes directly), difficulty, count, ab_group }
    A/B test: group "A" = personalized drills from weak phonemes, group "B" = random drills (control).
    Returns: { drills: [...], ab_group, personalized: bool }
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    patient_id = (data.get("patient_id") or data.get("patientId") or "").strip()
    difficulty = (data.get("difficulty") or "medium").strip().lower()
    if difficulty not in ("easy", "medium", "hard"):
        difficulty = "medium"
    count = int(data.get("count", 5))
    count = max(1, min(count, 10))
    ab_group = (data.get("ab_group") or DEFAULT_AB_GROUP).strip().upper()
    if ab_group not in ("A", "B"):
        ab_group = "A"

    # Group B = control (random drills, no personalization)
    if ab_group == "B":
        # Generate random drills from DrillWordProvider-style static lists
        all_words = []
        for words in PHONEME_WORD_MAP.values():
            all_words.extend(words)
        random.shuffle(all_words)
        drills = []
        for w in all_words[:count]:
            drills.append({
                "target_text": w,
                "target_phonemes": [],
                "difficulty": difficulty,
                "rationale": "Random drill (control group B)",
            })
        return jsonify({
            "patient_id": patient_id,
            "drills": drills,
            "ab_group": "B",
            "personalized": False,
        })

    # Group A = personalized drills based on weak phonemes
    weak_phonemes = data.get("weak_phonemes")
    if not weak_phonemes:
        sessions = data.get("sessions") or []
        if not sessions:
            return jsonify({"error": "Provide sessions or weak_phonemes for personalized drills"}), 400
        threshold = float(data.get("threshold", 60.0))
        weak_phonemes = identify_weak_phonemes(sessions, threshold)

    if not weak_phonemes:
        # No weak phonemes found — fall back to random but still mark as group A
        all_words = []
        for words in PHONEME_WORD_MAP.values():
            all_words.extend(words)
        random.shuffle(all_words)
        drills = [{"target_text": w, "target_phonemes": [], "difficulty": difficulty,
                   "rationale": "No weak phonemes detected — general practice"} for w in all_words[:count]]
        return jsonify({
            "patient_id": patient_id,
            "drills": drills,
            "ab_group": "A",
            "personalized": False,
            "note": "No weak phonemes found above threshold. Patient performing well.",
        })

    drills = generate_targeted_drills(weak_phonemes, difficulty, count)
    return jsonify({
        "patient_id": patient_id,
        "drills": drills,
        "ab_group": "A",
        "personalized": True,
        "weak_phonemes_targeted": [wp["phoneme"] for wp in weak_phonemes[:count]],
    })


# ---------- A/B Test Logging ----------
AB_LOG_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "transcriptions", "ab_test_log.jsonl"
)


@app.route("/ab/log", methods=["POST"])
def ab_log():
    """
    POST JSON: { patient_id, ab_group, event (drill_assigned|drill_completed|session_result),
                 accuracy, phonemes_targeted, timestamp }
    Logs A/B test events for later analysis.
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    record = {
        "timestamp_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "patient_id": data.get("patient_id") or data.get("patientId") or "",
        "ab_group": (data.get("ab_group") or "").upper(),
        "event": data.get("event") or "unknown",
        "accuracy": data.get("accuracy"),
        "phonemes_targeted": data.get("phonemes_targeted") or [],
        "difficulty": data.get("difficulty") or "",
        "extra": data.get("extra") or {},
    }

    os.makedirs(os.path.dirname(AB_LOG_PATH), exist_ok=True)
    try:
        with open(AB_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError as e:
        return jsonify({"error": f"Failed to write log: {e}"}), 500

    return jsonify({"status": "logged", "record": record})


@app.route("/ab/results", methods=["GET"])
def ab_results():
    """
    GET: Returns A/B test summary statistics.
    Compares average accuracy between group A (personalized) and group B (random).
    """
    if not os.path.isfile(AB_LOG_PATH):
        return jsonify({"error": "No A/B test data yet", "groups": {}})

    groups = defaultdict(lambda: {"sessions": 0, "total_accuracy": 0.0, "accuracies": []})
    try:
        with open(AB_LOG_PATH, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                grp = rec.get("ab_group", "")
                acc = rec.get("accuracy")
                event = rec.get("event", "")
                if grp in ("A", "B") and event in ("drill_completed", "session_result") and acc is not None:
                    groups[grp]["sessions"] += 1
                    groups[grp]["total_accuracy"] += float(acc)
                    groups[grp]["accuracies"].append(float(acc))
    except Exception as e:
        return jsonify({"error": str(e)}), 500

    summary = {}
    for grp, data in groups.items():
        n = data["sessions"]
        avg = (data["total_accuracy"] / n) if n > 0 else 0.0
        accs = sorted(data["accuracies"])
        median = accs[len(accs) // 2] if accs else 0.0
        summary[grp] = {
            "sessions": n,
            "avg_accuracy": round(avg, 2),
            "median_accuracy": round(median, 2),
            "min_accuracy": round(min(accs), 2) if accs else 0.0,
            "max_accuracy": round(max(accs), 2) if accs else 0.0,
        }

    # Simple significance indicator
    a_accs = groups.get("A", {}).get("accuracies", []) if "A" in groups else []
    b_accs = groups.get("B", {}).get("accuracies", []) if "B" in groups else []
    significance = None
    if len(a_accs) >= 5 and len(b_accs) >= 5:
        # Basic t-test approximation
        mean_a = sum(a_accs) / len(a_accs)
        mean_b = sum(b_accs) / len(b_accs)
        var_a = sum((x - mean_a) ** 2 for x in a_accs) / (len(a_accs) - 1) if len(a_accs) > 1 else 0
        var_b = sum((x - mean_b) ** 2 for x in b_accs) / (len(b_accs) - 1) if len(b_accs) > 1 else 0
        se = ((var_a / len(a_accs)) + (var_b / len(b_accs))) ** 0.5 if (var_a + var_b) > 0 else 0
        t_stat = (mean_a - mean_b) / se if se > 0 else 0
        significance = {
            "t_statistic": round(t_stat, 3),
            "mean_diff": round(mean_a - mean_b, 2),
            "likely_significant": abs(t_stat) > 1.96,
            "note": "Positive mean_diff = Group A (personalized) outperforms Group B (random)",
        }

    return jsonify({"groups": summary, "significance": significance})




if __name__ == "__main__":
    load_dotenv()  # ensure .env is loaded
    print("Environment setup complete. LLM model:", LLM_MODEL or "(none)")
    load_model()
    port = int(os.environ.get("VOCALAID_PORT", "5001"))  # 5001 avoids Windows port 5000 conflict
    print(f"VocalAid API running at http://0.0.0.0:{port} (emulator: http://10.0.2.2:{port})")
    app.run(host="0.0.0.0", port=port, debug=False)
