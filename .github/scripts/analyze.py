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
        "You are a senior SDET doing PR test impact analysis.\n\n"
        "You will be given:\n"
        "1) a list of changed files\n"
        "2) a code diff\n"
        "3) a test mapping JSON where keys are spec filenames and values are summaries of tests in that spec\n\n"
        "Changed files:\n"
        f"{files}\n\n"
        "Code diff:\n"
        f"{diff}\n\n"
        "Test mapping (ONLY these specs are allowed to be recommended):\n"
        f"{mapping_for_prompt}\n\n"
        "Task:\n"
        "For EACH changed file, produce one row in a markdown table with these columns:\n"
        "1) Changed file (exact filename/path)\n"
        "2) What changed / impacted area (1-2 lines, derived from diff)\n"
        "3) Specs to validate (comma-separated) — MUST be chosen ONLY from the keys of the test mapping. If none, write `None`.\n"
        "4) Confidence (0-100%)\n\n"
        "Hard rules:\n"
        "- DO NOT mention any spec that is not present as a key in the test mapping.\n"
        "- If you are unsure, keep confidence < 50%.\n"
        "- If the mapping doesn’t cover the changed area, write Specs to validate = `None` and explain why in column 2.\n"
        "- Keep output strictly markdown.\n\n"
        "Return ONLY the following format:\n\n"
        "### 🔍 AI Impact Analysis\n\n"
        "| Changed file | What changed / impacted area | Specs to validate (from mapping) | Confidence |\n"
        "|---|---|---|---|\n"
        "| <file> | <impact summary> | <spec1, spec2 OR None> | <NN>% |\n"
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
