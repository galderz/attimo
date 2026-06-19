#!/usr/bin/env bash
# ai-review.sh — AI-driven PR review with inline comments
#
# Usage:
#   ./scripts/ai-review.sh <PR_NUMBER> [EXTRA_INSTRUCTIONS...]
#
# Required env vars (never commit these):
#   GITHUB_TOKEN        — GitHub PAT with repo + pull_request write scope
#
# Provider-specific requirements:
#
#   bob (default):
#     bob CLI installed
#     BOBSHELL_API_KEY  — Bob API key
#     AI_MODEL          — Model override via bob's -m flag (optional)
#
#   vertex:
#     GCLOUD_PROJECT    — Google Cloud project ID
#     GCLOUD_REGION     — Region (default: us-east5)
#     AI_MODEL          — Model override (default: claude-sonnet-4-20250514)
#     gcloud CLI installed + authenticated
#
#   openai:
#     AI_API_KEY        — OpenAI API key
#     AI_MODEL          — Model override (default: gpt-4o)
#
# Optional env vars:
#   AI_PROVIDER         — "bob" (default), "vertex", or "openai"
#   REVIEW_EVENT        — "COMMENT" (default), "APPROVE", or "REQUEST_CHANGES"
#
set -euo pipefail

# ── Validate ─────────────────────────────────────────────────────────
PR_NUMBER="${1:-}"
[[ -z "$PR_NUMBER" ]]        && { echo "Usage: $0 <PR_NUMBER> [extra instructions...]" >&2; exit 1; }
shift
EXTRA_INSTRUCTIONS="${*:-}"
[[ -z "${GITHUB_TOKEN:-}" ]] && { echo "Error: GITHUB_TOKEN not set" >&2; exit 1; }

REPO=$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##; s#/$##')
AI_PROVIDER="${AI_PROVIDER:-bob}"
REVIEW_EVENT="${REVIEW_EVENT:-COMMENT}"
MAX_DIFF_CHARS=80000

case "$AI_PROVIDER" in
    bob)
        command -v bob >/dev/null 2>&1 || { echo "Error: bob CLI not found. Install: curl -fsSL https://bob.ibm.com/download/bobshell.sh | bash" >&2; exit 1; }
        [[ -z "${BOBSHELL_API_KEY:-}" ]] && { echo "Error: BOBSHELL_API_KEY not set" >&2; exit 1; }
        ;;
    vertex)
        [[ -z "${GCLOUD_PROJECT:-}" ]] && { echo "Error: GCLOUD_PROJECT not set" >&2; exit 1; }
        command -v gcloud >/dev/null 2>&1 || { echo "Error: gcloud CLI not found" >&2; exit 1; }
        GCLOUD_REGION="${GCLOUD_REGION:-us-east5}"
        AI_MODEL="${AI_MODEL:-claude-sonnet-4-20250514}"
        ;;
    openai)
        [[ -z "${AI_API_KEY:-}" ]] && { echo "Error: AI_API_KEY not set" >&2; exit 1; }
        AI_MODEL="${AI_MODEL:-gpt-4o}"
        ;;
    *)
        echo "Error: AI_PROVIDER must be 'bob', 'vertex', or 'openai'" >&2
        exit 1
        ;;
esac

GH_API="https://api.github.com/repos/${REPO}"
GH_HEADERS=(-H "Authorization: token ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json")

echo "📋 PR #${PR_NUMBER} in ${REPO} — ${AI_PROVIDER}"

# ── Fetch PR ─────────────────────────────────────────────────────────
PR_JSON=$(curl -s --fail-with-body "${GH_HEADERS[@]}" "${GH_API}/pulls/${PR_NUMBER}") || {
    echo "Error: failed to fetch PR metadata:" >&2
    echo "$PR_JSON" >&2
    exit 1
}
PR_DIFF=$(curl -s --fail-with-body -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github.v3.diff" \
    "${GH_API}/pulls/${PR_NUMBER}") || {
    echo "Error: failed to fetch PR diff:" >&2
    echo "$PR_DIFF" >&2
    exit 1
}

PR_TITLE=$(jq -r '.title' <<< "$PR_JSON")
PR_BODY=$(jq -r '.body // ""' <<< "$PR_JSON")
COMMIT_SHA=$(jq -r '.head.sha' <<< "$PR_JSON")

# Fetch full file list for the PR (diff may be truncated)
PR_FILES=$(curl -s --fail-with-body "${GH_HEADERS[@]}" \
    "${GH_API}/pulls/${PR_NUMBER}/files?per_page=100" \
    | jq -r '.[].filename') || {
    echo "Warning: could not fetch file list" >&2
    PR_FILES=""
}

echo "   ${PR_TITLE}"
echo "   HEAD: ${COMMIT_SHA:0:8}"

TRUNCATION_NOTE=""
if [[ ${#PR_DIFF} -gt $MAX_DIFF_CHARS ]]; then
    PR_DIFF="${PR_DIFF:0:$MAX_DIFF_CHARS}"
    TRUNCATION_NOTE=" (truncated to ${MAX_DIFF_CHARS} chars)"
    echo "⚠️  Diff truncated"
fi

# ── Build prompt ─────────────────────────────────────────────────────
REVIEW_INSTRUCTIONS='You are an expert code reviewer performing a structured five-axis review.

## Review Axes

Evaluate the PR across these five dimensions:

### 1. Correctness
- Does the code do what it claims? Does it match the PR description?
- Are edge cases handled (null, empty, boundary values)?
- Are error paths handled (not just the happy path)?
- Are there off-by-one errors, race conditions, or state inconsistencies?

### 2. Readability & Simplicity
- Are names descriptive and consistent with project conventions?
- Is the control flow straightforward?
- Could this be done more simply?
- Are there dead code artifacts?

### 3. Architecture
- Does it follow existing patterns or introduce new ones? If new, is it justified?
- Does it maintain clean module boundaries?
- Is there code duplication that should be shared?
- Is the abstraction level appropriate?

### 4. Security
- Is user input validated and sanitized?
- Are secrets kept out of code, logs, and version control?
- Are SQL queries parameterized?
- Is data from external sources treated as untrusted?

### 5. Performance
- Any N+1 query patterns or unbounded loops?
- Any unnecessary allocations in hot paths?
- Any synchronous operations that should be async?

## Severity Labels

Prefix every inline comment with one of these:
- **Critical:** — Blocks merge. Security vulnerability, data loss, broken functionality.
- (no prefix) — Required change. Must address before merge.
- **Nit:** — Minor, optional. Author may ignore (formatting, style preferences).
- **Optional:** — Worth considering but not required.
- **FYI** — Informational only. No action needed.

## Approval Standard

Approve when the change definitely improves overall code health, even if it is not perfect.
Perfect code does not exist — the goal is continuous improvement.
Do not block a change because it is not exactly how you would have written it.

## Critical Rules

- ONLY report issues you can VERIFY from the diff. Do NOT hallucinate or assume problems.
- Before reporting a whitespace, formatting, or syntax issue, re-read the exact line from the diff.
  If the diff does not clearly show the problem, do NOT report it.
- Fewer high-confidence comments are far better than many speculative ones.
- If the PR looks good, say so — do not invent problems to justify your existence.
- Do NOT claim files, tests, or CI are missing just because they are not in the diff.
  The diff only shows changed files. The file list below shows ALL files in the PR.
- Do NOT use any tools — just analyze the diff and respond.

## Output Format

You MUST respond with ONLY a JSON object (no markdown fences, no extra text):

{
  "summary": "Overall assessment in markdown. Include a brief verdict: Approve, Request Changes, or Comment. Mention which of the five axes have issues, if any.",
  "comments": [
    {
      "path": "relative/path/to/file.java",
      "line": 42,
      "body": "**Critical:** Your inline comment in markdown."
    }
  ]
}

Rules for the JSON:
- "summary" is required. Include a five-axis verdict (even if brief, e.g. "Correctness: ✅ | Readability: ✅ | Architecture: ✅ | Security: ⚠️ | Performance: ✅").
- "comments" array can be empty if there are no line-level issues.
- "path" must match exactly as shown in the diff (e.g. "src/main/java/Foo.java").
- "line" must be the NEW file line number (the number after + in @@ -a,b +c,d @@). Only comment on added/modified lines (lines starting with + in the diff).
- "body" must start with a severity label (Critical:, Nit:, Optional:, FYI, or no prefix for required).
- Output raw JSON only. No ```json fences. No text before or after.'

if [[ -n "$EXTRA_INSTRUCTIONS" ]]; then
    REVIEW_INSTRUCTIONS+="

Additional instructions:
${EXTRA_INSTRUCTIONS}"
fi

REVIEW_PROMPT="## PR #${PR_NUMBER}: ${PR_TITLE}

### Description
${PR_BODY}

### Files in this PR
${PR_FILES}

### Diff${TRUNCATION_NOTE}
\`\`\`diff
${PR_DIFF}
\`\`\`"

echo "🧠 Requesting review..."

# ── Call AI ──────────────────────────────────────────────────────────
call_bob() {
    local bob_args=(--chat-mode ask --approval-mode yolo --hide-intermediary-output --auth-method api-key)
    [[ -n "${AI_MODEL:-}" ]] && bob_args+=(-m "$AI_MODEL")

    local full_prompt="${REVIEW_INSTRUCTIONS}

${REVIEW_PROMPT}"

    bob "${bob_args[@]}" "$full_prompt" 2>/dev/null
}

call_vertex() {
    local token
    token=$(gcloud auth print-access-token)

    local endpoint="https://${GCLOUD_REGION}-aiplatform.googleapis.com/v1/projects/${GCLOUD_PROJECT}/locations/${GCLOUD_REGION}/publishers/anthropic/models/${AI_MODEL}:rawPredict"

    local payload
    payload=$(jq -n \
        --arg system "$REVIEW_INSTRUCTIONS" \
        --arg user "$REVIEW_PROMPT" \
        '{"anthropic_version": "vertex-2023-10-16",
          "max_tokens": 4096,
          "system": $system,
          "messages": [{role: "user", content: $user}]}')

    local raw
    raw=$(curl -s --fail-with-body \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$endpoint") || {
        echo "Error: Vertex API call failed:" >&2
        echo "$raw" >&2
        return 1
    }
    jq -r '.content[0].text' <<< "$raw"
}

call_openai() {
    local payload
    payload=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$REVIEW_INSTRUCTIONS" \
        --arg user "$REVIEW_PROMPT" \
        '{model: $model, max_tokens: 4096,
          messages: [{role: "system", content: $system},
                     {role: "user", content: $user}]}')

    local raw
    raw=$(curl -s --fail-with-body \
        -H "Authorization: Bearer ${AI_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "https://api.openai.com/v1/chat/completions") || {
        echo "Error: OpenAI API call failed:" >&2
        echo "$raw" >&2
        return 1
    }
    jq -r '.choices[0].message.content' <<< "$raw"
}

AI_RESPONSE=$(call_${AI_PROVIDER})

[[ -z "$AI_RESPONSE" || "$AI_RESPONSE" == "null" ]] && { echo "Error: empty AI response" >&2; exit 1; }

# ── Parse AI response ───────────────────────────────────────────────
# Log directory for debugging (restricted permissions, AI response only — no secrets/PII)
LOG_DIR=$(mktemp -d /tmp/ai-review-XXXXXX)
chmod 700 "$LOG_DIR"
echo "📁 Debug logs: ${LOG_DIR}"

# Save raw AI response
echo "$AI_RESPONSE" > "${LOG_DIR}/01-raw-response.txt"

# Strip ANSI escape codes
AI_CLEAN=$(sed 's/\x1b\[[0-9;]*m//g' <<< "$AI_RESPONSE")
echo "$AI_CLEAN" > "${LOG_DIR}/02-ansi-stripped.txt"

# Extract JSON from response (handles raw JSON, markdown fences, preamble text)
AI_JSON=$(echo "$AI_CLEAN" | python3 -c '
import sys, json
text = sys.stdin.read().strip()
# Try raw text as JSON first
try:
    json.loads(text)
    print(text)
    sys.exit(0)
except ValueError:
    pass
# Extract outermost { ... } from preamble/fences
first = text.find("{")
last = text.rfind("}")
if first != -1 and last > first:
    candidate = text[first:last+1]
    try:
        json.loads(candidate)
        print(candidate)
        sys.exit(0)
    except ValueError:
        pass
# Give up - return raw text for fallback handling
print(text)
')
echo "$AI_JSON" > "${LOG_DIR}/03-extracted-json.txt"

if ! jq empty <<< "$AI_JSON" 2>/dev/null; then
    # Log the parse error
    jq empty <<< "$AI_JSON" 2> "${LOG_DIR}/04-jq-error.txt" || true
    echo "⚠️  AI response is not valid JSON, posting as plain comment" >&2
    echo "   See ${LOG_DIR} for debug files" >&2
    # Fallback: post the raw response as a single review comment
    REVIEW_BODY="${AI_RESPONSE}"

    REVIEW_RESULT=$(jq -n \
        --arg body "$REVIEW_BODY" \
        --arg event "$REVIEW_EVENT" \
        '{body: $body, event: $event}' \
        | curl -s --fail-with-body "${GH_HEADERS[@]}" \
            -H "Content-Type: application/json" \
            -d @- \
            "${GH_API}/pulls/${PR_NUMBER}/reviews") || {
        echo "Error: failed to post review:" >&2
        echo "$REVIEW_RESULT" >&2
        exit 1
    }
    REVIEW_URL=$(jq -r '.html_url' <<< "$REVIEW_RESULT")
    echo "🎉 Done (fallback) → ${REVIEW_URL}"
    exit 0
fi

SUMMARY=$(jq -r '.summary' <<< "$AI_JSON")
COMMENT_COUNT=$(jq '.comments | length' <<< "$AI_JSON")

echo "✅ Review: ${COMMENT_COUNT} inline comment(s)"

# ── Build review payload ─────────────────────────────────────────────
REVIEW_SUMMARY="${SUMMARY}"

# Build the GitHub review API payload with inline comments
REVIEW_PAYLOAD=$(jq -n \
    --arg body "$REVIEW_SUMMARY" \
    --arg event "$REVIEW_EVENT" \
    --arg commit_id "$COMMIT_SHA" \
    --argjson comments "$(jq '[.comments[] | {path, line: (.line // empty), body}]' <<< "$AI_JSON")" \
    '{body: $body, event: $event, commit_id: $commit_id, comments: $comments}')

# ── Post review ──────────────────────────────────────────────────────
echo "📤 Posting review with inline comments..."

REVIEW_RESULT=$(curl -s --fail-with-body "${GH_HEADERS[@]}" \
    -H "Content-Type: application/json" \
    -d "$REVIEW_PAYLOAD" \
    "${GH_API}/pulls/${PR_NUMBER}/reviews") || {
    echo "Error: failed to post review:" >&2
    echo "$REVIEW_RESULT" >&2

    # If inline comments fail (e.g. line number mismatch), retry without them
    echo "⚠️  Retrying without inline comments..." >&2
    REVIEW_PAYLOAD=$(jq -n \
        --arg body "$REVIEW_SUMMARY" \
        --arg event "$REVIEW_EVENT" \
        '{body: $body, event: $event}')

    REVIEW_RESULT=$(curl -s --fail-with-body "${GH_HEADERS[@]}" \
        -H "Content-Type: application/json" \
        -d "$REVIEW_PAYLOAD" \
        "${GH_API}/pulls/${PR_NUMBER}/reviews") || {
        echo "Error: retry also failed:" >&2
        echo "$REVIEW_RESULT" >&2
        exit 1
    }
}

REVIEW_URL=$(jq -r '.html_url' <<< "$REVIEW_RESULT")
echo "🎉 Done → ${REVIEW_URL}"
