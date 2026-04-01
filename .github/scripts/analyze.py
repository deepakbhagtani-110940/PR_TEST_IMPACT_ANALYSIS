import os
import json
import requests
import re
import datetime
import sys

# 1. Configuration (Updated to 2.5 Flash Lite)
MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite").strip()
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
AUDIT_LOG = os.getenv("GEMINI_AUDIT_LOG", "gemini_audit_log.jsonl")

MARKDOWN_FALLBACK = "### 🔍 AI Impact Analysis\n\n_Analysis completed but no high-confidence impacts were identified._"

def sanitize_input(text: str, max_len: int) -> str:
    if not isinstance(text, str): return ""
    text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    text = re.sub(r"^(\+|\-|@@).*$", "", text, flags=re.MULTILINE)
    return text.strip()[:max_len]

def main():
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    pr_number = os.getenv("PR_NUMBER", "unknown")
    
    # Read metadata
    files_metadata = sanitize_input(open("files.txt").read(), 5000) if os.path.exists("files.txt") else "None"
    mapping_data = open("test_mapping.json").read() if os.path.exists("test_mapping.json") else "{}"
    pr_title = sanitize_input(os.getenv("PR_TITLE", ""), 200)
    pr_body = sanitize_input(os.getenv("PR_BODY", ""), 1000)

    # 2. Enhanced Prompt for Lite Models
    # We use explicit column headers and a "Hard Rule" to prevent empty columns.
    system_prompt = (
        "You are an expert SDET. Your task is to perform Impact Analysis on PR metadata.\n"
        "You MUST return a markdown table with exactly 4 columns:\n"
        "1. Changed file: Path of the file.\n"
        "2. Impacted area: A 1-sentence summary of what this change affects.\n"
        "3. Specs: Comma-separated keys from the provided QA context mapping.\n"
        "4. Confidence: A percentage (0-100%).\n\n"
        "CRITICAL: Do not leave 'Impacted area' or 'Confidence' blank. If unsure, provide your best estimate."
    )

    user_prompt = (
        f"QA Context Mapping: {mapping_data}\n\n"
        f"PR Title: {pr_title}\n"
        f"PR Description: {pr_body}\n"
        f"Changed Files Metadata:\n{files_metadata}\n\n"
        "Output the analysis table now:"
    )

    payload = {
        "system_instruction": {"parts": [{"text": system_prompt}]},
        "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
        "generationConfig": {
            "temperature": 0.1, 
            "maxOutputTokens": 1200,
            "topP": 0.95
        }
    }

    try:
        response = requests.post(ENDPOINT, params={"key": api_key}, json=payload, timeout=30)
        
        if response.status_code == 200:
            res_json = response.json()
            # Safety check for empty candidates
            if "candidates" in res_json and res_json["candidates"]:
                output_text = res_json['candidates'][0]['content']['parts'][0]['text']
            else:
                output_text = MARKDOWN_FALLBACK
        else:
            output_text = f"### 🔍 AI Impact Analysis\n\n⚠️ Error: API returned {response.status_code}"

    except Exception as e:
        output_text = f"### 🔍 AI Impact Analysis\n\n⚠️ System Exception: {type(e).__name__}"

    # 3. Final Sanitization & Save
    with open("output.txt", "w", encoding="utf-8") as f:
        # Ensure the header is always present
        if "### 🔍 AI Impact Analysis" not in output_text:
            f.write("### 🔍 AI Impact Analysis\n\n" + output_text)
        else:
            f.write(output_text)

if __name__ == "__main__":
    main()
