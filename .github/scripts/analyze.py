import os
import json
import requests
import re
import datetime
import sys
import time

# ✅ Model & Endpoint Configuration
MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite").strip()
# Switch to GitHub Models endpoint if using the Azure/GitHub Models proxy
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
AUDIT_LOG = os.getenv("GEMINI_AUDIT_LOG", "gemini_audit_log.jsonl")

MARKDOWN_FALLBACK = (
    "### 🔍 AI Impact Analysis\n\n"
    "_No impact detected (or analysis returned empty output)._"
)

# ✅ Security: Generic message for public PRs to prevent exception leakage
GENERIC_ERROR_MSG = (
    "### 🔍 AI Impact Analysis\n\n"
    "⚠️ **Analysis Unavailable**: An internal error occurred while processing. "
    "Details have been logged for the internal team."
)

def read_file(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def sanitize_text(text: str, max_len: int = 2000) -> str:
    """✅ Security: Input sanitization for prompt injection resistance."""
    if not isinstance(text, str):
        return ""
    text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    text = re.sub(r"^(\+|\-|@@).*$", "", text, flags=re.MULTILINE)
    text = re.sub(
        r"(?i)(ignore previous instructions|system:|assistant:|user:|act as|role:)",
        "[REDACTED]",
        text,
    )
    return text.strip()[:max_len]

def validate_response_markdown_table(md: str) -> bool:
    """✅ Security: Output validation against expected structure."""
    if not isinstance(md, str) or "### 🔍 AI Impact Analysis" not in md:
        return False
    # Check for basic table markers
    return "|" in md and "---" in md

def write_audit_log(pr_number, status, error=None, extra=None):
    """✅ Security: Internal Audit Log (No prompts stored)."""
    entry = {
        "timestamp": datetime.datetime.utcnow().isoformat() + "Z",
        "pr_number": pr_number,
        "model": MODEL,
        "status": status,
        "error": str(error) if error else None,
    }
    if extra: entry.update(extra)
    try:
        with open(AUDIT_LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
    except:
        pass

def post_with_retry(api_key: str, payload: dict, timeout: int = 60, max_attempts: int = 2):
    """✅ Security: 429 Handling with Back-off."""
    last_r = None
    for attempt in range(1, max_attempts + 1):
        try:
            r = requests.post(
                ENDPOINT,
                params={"key": api_key},
                headers={"Content-Type": "application/json"},
                json=payload,
                timeout=timeout,
            )
            last_r = r
            if r.status_code != 429:
                return r
            if attempt < max_attempts:
                time.sleep(5 * attempt)
        except Exception as e:
            if attempt == max_attempts: raise e
    return last_r

def main() -> None:
    api_key = (os.getenv("GEMINI_API_KEY") or "").strip()
    pr_number = os.getenv("PR_NUMBER", "unknown")

    if not api_key:
        write_audit_log(pr_number, "error", "missing_api_key")
        with open("output.txt", "w") as f: f.write(GENERIC_ERROR_MSG)
        sys.exit(1)

    # ✅ Security: Data Minimization (Metadata only, no diff.txt)
    files_raw = read_file("files.txt")
    mapping_raw = read_file("test_mapping.json")
    
    # ✅ Security: Input Sanitization
    pr_title = sanitize_text(os.getenv("PR_TITLE", ""), 200)
    pr_body = sanitize_text(os.getenv("PR_BODY", ""), 2000)
    files_sanitized = sanitize_text(files_raw, 6000)

    # ✅ Security: Structural Prompt Separation
    prompt = (
        "SYSTEM INSTRUCTIONS:\n"
        "You are a senior SDET doing PR test impact analysis. Use ONLY the mapping keys provided.\n"
        "Return ONLY the markdown format requested.\n\n"
        "<pr_content>\n"
        f"Title: {pr_title}\n"
        f"Description: {pr_body}\n"
        f"Files: {files_sanitized}\n"
        "</pr_content>\n\n"
        "<qa_context>\n"
        f"Mapping: {mapping_raw}\n"
        "</qa_context>\n\n"
        "Task: Generate a markdown table with columns: | Changed file | Impacted area | Specs | Confidence |"
    )

    payload = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.1, "maxOutputTokens": 900},
    }

    status = "success"
    try:
        r = post_with_retry(api_key, payload)
        
        if r.status_code == 200:
            resp_json = r.json()
            candidates = resp_json.get("candidates") or []
            out = candidates[0].get("content", {}).get("parts", [{}])[0].get("text", "").strip() if candidates else ""
            
            if not out:
                out = MARKDOWN_FALLBACK
            elif not validate_response_markdown_table(out):
                # ✅ Security: Fail-safe check
                status = "invalid_format"
                out = MARKDOWN_FALLBACK + "\n\n> ⚠️ Note: AI output format was non-standard."
        else:
            status = f"http_{r.status_code}"
            out = GENERIC_ERROR_MSG

    except Exception as e:
        status = "exception"
        # ✅ Security: Log exception detail internally, not in PR
        write_audit_log(pr_number, status, error=e)
        out = GENERIC_ERROR_MSG

    # ✅ Security: Save validated/sanitized content
    with open("output.txt", "w", encoding="utf-8") as f:
        f.write(out)

    write_audit_log(pr_number, status)

if __name__ == "__main__":
    main()
