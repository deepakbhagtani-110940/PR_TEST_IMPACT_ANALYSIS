import os
import json
import requests

MODEL = "gemini-2.5-flash-lite"
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

MARKDOWN_FALLBACK = """### 🔍 AI Impact Analysis

_No impact detected (or analysis returned empty output)._"""

def read_file(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def extract_text(resp_json: dict) -> str:
    candidates = resp_json.get("candidates") or []
    if not candidates:
        return ""
    content = (candidates[0].get("content") or {})
    parts = content.get("parts") or []
    return "\n".join([p.get("text", "") for p in parts if p.get("text")]).strip()

def main() -> None:
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        with open("output.txt", "w", encoding="utf-8") as f:
            f.write("### 🔍 AI Impact Analysis\n\n⚠️ Missing required secret: `GEMINI_API_KEY`\n")
        raise SystemExit(1)

    diff = read_file("diff.txt")[:12000]
    files = read_file("files.txt")
    mapping_raw = read_file("test_mapping.json")

    try:
        mapping = json.loads(mapping_raw) if mapping_raw.strip() else {}
        mapping_for_prompt = json.dumps(mapping, indent=2)
    except Exception:
        mapping_for_prompt = mapping_raw

    prompt = f"""
You are a senior SDET doing PR impact analysis.

Changed files:
{files}

Code diff:
{diff}

Test mapping:
{mapping_for_prompt}

Instructions:
- Identify impacted test spec files (ONLY from the keys in Test mapping)
- Give clear reasoning
- Give confidence (0-100%)
- Do NOT hallucinate tests outside the mapping
- If unsure, keep confidence < 50%
- If nothing is impacted, say so clearly

Return strictly in this format:

### 🔍 AI Impact Analysis

- File: <test file from mapping>
  Reason: <why impacted>
  Confidence: <number>%
""".strip()

    payload = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.2, "maxOutputTokens": 800},
    }

    try:
        r = requests.post(
            ENDPOINT,
            params={"key": api_key},
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload),
            timeout=60,
        )

        if r.status_code >= 400:
            out = f"""### 🔍 AI Impact Analysis

⚠️ Analysis failed while calling Gemini.

**HTTP {r.status_code}**
```json
{r.text}
