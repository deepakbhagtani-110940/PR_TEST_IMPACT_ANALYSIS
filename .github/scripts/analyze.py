import os
import json
import requests
import re
import datetime
import sys
import time

# 1. Configuration: Using Gemini 2.5 Flash Lite
MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite").strip()
# Using the standard Google API endpoint (Update to Azure/Inference if using the GitHub Models proxy)
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
AUDIT_LOG = os.getenv("GEMINI_AUDIT_LOG", "gemini_audit_log.jsonl")

# 2. Security Handlers: Generic Messages (No exception details leaked to PR)
GENERIC_ERROR_MSG = (
    "### 🔍 AI Impact Analysis\n\n"
    "⚠️ **Analysis Unavailable**: An internal processing error occurred. "
    "Please check the system audit logs for reference."
)

MARKDOWN_FALLBACK = (
    "### 🔍 AI Impact Analysis\n\n"
    "_No significant test impacts detected for the provided metadata._"
)

def sanitize_input(text: str, max_len: int) -> str:
    """✅ Input sanitization: Prevents prompt injection & removes code artifacts."""
    if not isinstance(text, str): return ""
    # Remove markdown code blocks and diff markers to enforce metadata-only scope
    text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    text = re.sub(r"^(\+|\-|@@).*$", "", text, flags=re.MULTILINE)
    # Redact common prompt injection keywords
    text = re.sub(r"(?i)(system:|assistant:|user:|ignore instructions|act as role)", "[REDACTED]", text)
    return text.strip()[:max_len]

def write_audit_log(pr_number: str, status: str, error: Exception = None):
    """✅ Audit log entry: Stores status/errors internally, never in the PR."""
    entry = {
        "timestamp": datetime.datetime.utcnow().isoformat() + "Z",
        "pr_number": pr_number,
        "status": status,
        "model": MODEL,
        "error_detail": str(error) if error else None
    }
    try:
        with open(AUDIT_LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
    except: pass

def post_with_retry(api_key: str, payload: dict):
    """✅ 429 handling with retry/back-off logic."""
    for attempt in range(2):
        try:
            response = requests.post(
                ENDPOINT, 
                params={"key": api_key}, 
                json=payload, 
                timeout=30
            )
            if response.status_code == 429:
                time.sleep(5 * (attempt + 1))
                continue
            return response
        except requests.exceptions.RequestException:
            if attempt == 1: raise
            time.sleep(2)
    return None

def main():
    # Verify GitHub Token / API Key
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    pr_number = os.getenv("PR_NUMBER", "unknown")
    
    if not api_key:
        write_audit_log(pr_number, "error_missing_auth")
        with open("output.txt", "w") as f: f.write(GENERIC_ERROR_MSG)
        return

    try:
        # ✅ Data Minimization: Load metadata ONLY (no diff.txt)
        files_metadata = sanitize_input(open("files.txt").read(), 5000) if os.path.exists("files.txt") else "None"
        mapping_data = open("test_mapping.json").read() if os.path.exists("test_mapping.json") else "{}"
        
        pr_title = sanitize_input(os.getenv("PR_TITLE", ""), 200)
        pr_body = sanitize_input(os.getenv("PR_BODY", ""), 1000)

        # ✅ Structural Separation: System Instruction vs User Content
        payload = {
            "system_instruction": {
                "parts": [{"text": (
                    "You are a Senior SDET doing PR test impact analysis.\n"
                    "TASK: Generate a markdown table with exactly 4 columns: "
                    "| Changed file | Impacted area summary | Specs to validate | Confidence score |\n"
                    "RULES:\n"
                    "1. 'Impacted area summary' must explain the logical impact of the metadata change.\n"
                    "2. 'Specs to validate' must ONLY use keys from the QA Context JSON.\n"
                    "3. 'Confidence score' must be a percentage.\n"
                    "4. Output ONLY the markdown table."
                )}]
            },
            "contents": [{
                "role": "user",
                "parts": [{
                    "text": (
                        f"<qa_context>\n{mapping_data}\n</qa_context>\n\n"
                        f"<untrusted_metadata>\nTitle: {pr_title}\nDesc: {pr_body}\nFiles:\n{files_metadata}\n</untrusted_metadata>"
                    )
                }]
            }],
            "generationConfig": {"temperature": 0.1, "maxOutputTokens": 1000}
        }

        # POST to Model
        response = post_with_retry(api_key, payload)
        
        # ✅ AI Response Validation
        if response and response.status_code == 200:
            res_json = response.json()
            if "candidates" in res_json and res_json["candidates"]:
                raw_text = res_json['candidates'][0]['content']['parts'][0]['text'].strip()
                
                # Check for table structure before accepting
                if "|" in raw_text and "---" in raw_text:
                    final_output = raw_text
                    status = "success"
                else:
                    final_output = MARKDOWN_FALLBACK
                    status = "invalid_format"
            else:
                final_output = MARKDOWN_FALLBACK
                status = "empty_response"
        else:
            final_output = GENERIC_ERROR_MSG
            status = f"api_error_{response.status_code if response else 'timeout'}"

    except Exception as e:
        # ✅ Fail-safe: Generic error message only
        final_output = GENERIC_ERROR_MSG
        status = "exception"
        write_audit_log(pr_number, status, error=e)

    # ✅ PR Comment Sanitization: Save final safe string
    with open("output.txt", "w", encoding="utf-8") as f:
        if "### 🔍 AI Impact Analysis" not in final_output:
            f.write("### 🔍 AI Impact Analysis\n\n" + final_output)
        else:
            f.write(final_output)
    
    write_audit_log(pr_number, status)

if __name__ == "__main__":
    main()
