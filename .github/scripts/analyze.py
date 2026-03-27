import os
import json
from openai import OpenAI

MARKDOWN_FALLBACK = """### 🔍 AI Impact Analysis

_No impact detected (or analysis returned empty output)._"""

def read_file(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def main() -> None:
    # Inputs produced by the workflow
    diff = read_file("diff.txt")
    files = read_file("files.txt")
    mapping_raw = read_file("test_mapping.json")

    # Parse mapping (keep raw text if parsing fails)
    try:
        mapping = json.loads(mapping_raw) if mapping_raw.strip() else {}
        mapping_for_prompt = json.dumps(mapping, indent=2)
    except Exception:
        mapping_for_prompt = mapping_raw

    # Limit diff to avoid token overflow (tweak as needed)
    diff = (diff or "")[:12000]

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
- Give clear reasoning for each impacted test
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

    # GitHub Models endpoint (as per your spec)
    # NOTE: This assumes the endpoint accepts GITHUB_TOKEN as api_key in your environment.
    client = OpenAI(
        api_key=os.getenv("GITHUB_TOKEN"),
        base_url="https://models.inference.ai.azure.com",
    )

    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "You are an expert SDET."},
                {"role": "user", "content": prompt},
            ],
        )

        output = (response.choices[0].message.content or "").strip()
    except Exception as e:
        output = f"""### 🔍 AI Impact Analysis

⚠️ Analysis failed while calling the model.

**Error:** `{type(e).__name__}: {e}`
"""

    if not output:
        output = MARKDOWN_FALLBACK

    with open("output.txt", "w", encoding="utf-8") as f:
        f.write(output)

if __name__ == "__main__":
    main()
