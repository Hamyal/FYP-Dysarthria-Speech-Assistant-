# VocalAid Dysarthria API

Run the API server **before** using voice analysis in the app.

## Endpoints

- **POST /analyze** – Speech clarity/dysarthria (wav2vec2). Send multipart `audio` (WAV, 16 kHz).
- **POST /ai/summary** – Patient history summary and suggestions (Groq). Send JSON: `patientId`, `role` (patient|therapist), optional `question`, plus `patient`, `sessions`, `drills`, `reports` (the app sends these from Firebase).

## Quick start

```bash
cd vocalaid_api
pip install -r requirements.txt
python app.py
```

You should see: `VocalAid API running at http://0.0.0.0:5001`

## AI summary (Groq)

For **AI Summary** / **AI suggestions** in the app (patient history and suggestions), set your Groq API key:

- **Windows:** `set GROQ_API_KEY=your_groq_api_key_here` then `python app.py`
- **Linux/macOS:** `export GROQ_API_KEY=your_groq_api_key_here` then `python app.py`

Get a key at [console.groq.com](https://console.groq.com). Without `GROQ_API_KEY`, the app’s AI summary will return an error.

## Troubleshooting connection errors

If the app shows "Connection failed" or timeout:

1. **Start the API first** – The app connects to `10.0.2.2:5001` (emulator) or your PC IP (physical device).
2. **Port 5001** – The API uses port 5001 by default (avoids Windows port 5000 conflict). To use 5000: `set VOCALAID_PORT=5000` before `python app.py`, and update `VOCALAID_API_URL` in `app/build.gradle` to match.
3. **Firewall** – Allow Python through Windows Firewall when prompted.
4. **Physical device** – Use your PC's IP instead of 10.0.2.2. Edit `app/build.gradle`:
   ```gradle
   buildConfigField "String", "VOCALAID_API_URL", "\"http://192.168.1.X:5001\""
   ```
   Find your IP: `ipconfig` (look for IPv4 Address)
