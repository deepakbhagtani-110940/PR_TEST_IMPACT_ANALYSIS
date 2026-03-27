import os
import json
import requests

MODEL = "gemini-2.5-flash-lite"
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

MARKDOWN_FALLBACK = (
    "### 🔍 AI Impact Analysis\n\n"
    "_No impact detected (or analysis returned empty output)._"
)

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
    texts = []
    for p in parts:
        t = p.get("text")
        if t:
            texts.append(t)
    return "\n".join(texts).strip()

def main() -> None:
    api_key = (os.getenv("GEMINI_API_KEY") or "").strip()
    if not api_key:
        out = "### 🔍 AI Impact Analysis\n\n⚠️ Missing required secret: `GEMINI_API_KEY`\n"
        with open("output.txt", "w", encoding="utf-8") as f:
            f.write(out)
        raise SystemExit(1)

    diff = read_file("diff.txt")[:12000]
    files = read_file("files.txt")
    mapping_raw = read_file("test_mapping.json")

    try:
        mapping = json.loads(mapping_raw) if mapping_raw.strip() else {}
        mapping_for_prompt = json.dumps(mapping, indent=2)
    except Exception:
        mapping_for_prompt = mapping_raw

    prompt = (
        "You are a senior SDET doing PR impact analysis.\n\n"
        "Changed files:\n"
        f"{files}\n\n"
        "Code diff:\n"
        f"{diff}\n\n"
        "Test mapping:\n"
        f"{mapping_for_prompt}\n\n"
        "Instructions:\n"
        "- Identify impacted test spec files (ONLY from the keys in Test mapping)\n"
        "- Give clear reasoning\n"
        "- Give confidence (0-100%)\n"
        "- Do NOT hallucinate tests outside the mapping\n"
        "- If unsure, keep confidence < 50%\n"
        "- If nothing is impacted, say so clearly\n\n"
        "Return strictly in this format:\n\n"
        "### 🔍 AI Impact Analysis\n\n"
        "- File: <test file from mapping>\n"
        "  Reason: <why impacted>\n"
        "  Confidence: <number>%\n"
    )

    payload = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.2, "maxOutputTokens": 800},
    }

    out = ""
    try:
        r = requests.post(
            ENDPOINT,
            params={"key": api_key},
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload),
            timeout=60,
        )

        if r.status_code >= 400:
            out = (
                "### 🔍 AI Impact Analysis\n\n"
                "⚠️ Analysis failed while calling Gemini.\n\n"
                f"**HTTP {r.status_code}**\n"
                "```json\n"
                f"{r.text}\n"
                "```\n"
            )
        else:
            out = extract_text(r.json())

    except Exception as e:
        out = (
            "### 🔍 AI Impact Analysis\n\n"
            "⚠️ Analysis failed while calling Gemini.\n\n"
            f"**Error:** `{type(e).__name__}: {e}`\n"
        )

    if not out.strip():
        out = MARKDOWN_FALLBACK

    with open("output.txt", "w", encoding="utf-8") as f:
        f.write(out)

if __name__ == "__main__":
    main()
