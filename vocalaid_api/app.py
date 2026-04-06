"""
VocalAid Dysarthria Speech Analysis API
- POST /analyze: wav2vec dysarthria score + optional Whisper text + English ARPAbet-style phonemes (g2p-en from transcript).
  Form fields: audio (file), optional patient_id, optional target_text (if set, accuracy = how close transcription matches target, 0–100; speech_clarity_percent = wav2vec clarity).
  JSON: transcription, phonemes, transcription_error, saved paths.
- GET /dataset/torgo: list TORGO-style phrases (?difficulty=easy|medium|hard).
- GET /dataset/torgo/pick: random phrase with transcription for therapist drill assignment.
- POST /ai/summary: patient history summary and suggestions (Groq).
Run: python app.py
Set GROQ_API_KEY (and optional LLM_MODEL) in env or .env. Emulator: http://10.0.2.2:5001
Whisper: pip install openai-whisper (see requirements.txt).
  WHISPER_MODEL_SIZE=tiny|base|small|...  WHISPER_LANGUAGE=en (or empty/auto for detection).
  WHISPER_BEAM_SIZE=1 (fast, default) or 5 (slower, often better). ENABLE_TRANSCRIPTION=0 to disable.
  ENABLE_PHONEMES=0 to skip g2p-en (English phonemes derived from Whisper text, not direct acoustic alignment).
  Transcripts append to vocalaid_api/transcriptions/transcriptions.jsonl + per-request .json.
Replace data/torgo_phrases.json with your TORGO export (id, transcription, difficulty) if needed.
"""
import os
import io
import json
import random
import re
import difflib
import numpy as np
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
