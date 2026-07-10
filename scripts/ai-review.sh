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

# ── Build prompts ────────────────────────────────────────────────────
# Two-pass approach: Pass 1 gets a high-quality natural review, Pass 2
# converts it to structured JSON with accurate per-line comments.

EXTRA_BLOCK=""
if [[ -n "$EXTRA_INSTRUCTIONS" ]]; then
    EXTRA_BLOCK="
Additional instructions: ${EXTRA_INSTRUCTIONS}
"
fi

PR_CONTEXT="## PR #${PR_NUMBER}: ${PR_TITLE}

### Description
${PR_BODY}

### Files in this PR
${PR_FILES}

### Diff${TRUNCATION_NOTE}
\`\`\`diff
${PR_DIFF}
\`\`\`"

PASS1_PROMPT="Review the following pull request.

${PR_CONTEXT}

---

You are an expert code reviewer. Evaluate this PR across five axes:

1. **Correctness** — bugs, edge cases, error paths, off-by-one, race conditions
2. **Readability** — naming, control flow, simplicity, dead code
3. **Architecture** — patterns, module boundaries, duplication, abstraction level
4. **Security** — input validation, secrets in code, injection, untrusted data
5. **Performance** — N+1 queries, unbounded loops, hot path allocations

Rules:
- ONLY report issues you can VERIFY from the diff. Do NOT hallucinate or assume problems.
- Re-read the exact diff line before reporting whitespace/formatting/syntax issues.
- Do NOT claim files, tests, or CI are missing — the diff shows only changed files; the file list above shows ALL files in the PR.
- Fewer high-confidence comments are better than many speculative ones.
- If the PR looks good, say so. Do not invent problems.
- Approve when the change improves code health, even if imperfect.
- Do NOT use any tools.
${EXTRA_BLOCK}
Prefix every inline comment with a severity: **Critical:**, **Nit:**, **Optional:**, **FYI**, or no prefix for required changes.

Start your review with a five-axis verdict line using this format:
Correctness: <verdict> | Readability: <verdict> | Architecture: <verdict> | Security: <verdict> | Performance: <verdict>
where <verdict> is one of: ✅ ⚠️ ❌

Then give your assessment. When you reference specific lines, always state the exact file path (as shown after +++ b/ in the diff) and the NEW file line number (the number after + in the @@ hunk header). Format line references as: \`path/to/file.ext:42\`."

# ── Call AI ──────────────────────────────────────────────────────────
call_bob() {
    local prompt="$1"
    local bob_args=(--chat-mode ask --approval-mode yolo --hide-intermediary-output --auth-method api-key)
    [[ -n "${AI_MODEL:-}" ]] && bob_args+=(-m "$AI_MODEL")

    bob "${bob_args[@]}" "$prompt" 2>/dev/null
}

call_vertex() {
    local prompt="$1"
    local token
    token=$(gcloud auth print-access-token)

    local endpoint="https://${GCLOUD_REGION}-aiplatform.googleapis.com/v1/projects/${GCLOUD_PROJECT}/locations/${GCLOUD_REGION}/publishers/anthropic/models/${AI_MODEL}:rawPredict"

    local payload
    payload=$(jq -n \
        --arg user "$prompt" \
        '{"anthropic_version": "vertex-2023-10-16",
          "max_tokens": 4096,
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
    local prompt="$1"
    local payload
    payload=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg user "$prompt" \
        '{model: $model, max_tokens: 4096,
          messages: [{role: "user", content: $user}]}')

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

# ── Pass 1: Review ──────────────────────────────────────────────────
echo "🧠 Pass 1/2: Generating review..."

PASS1_REVIEW=$(call_${AI_PROVIDER} "$PASS1_PROMPT")

[[ -z "$PASS1_REVIEW" || "$PASS1_REVIEW" == "null" ]] && { echo "Error: empty AI response from pass 1" >&2; exit 1; }

echo "✅ Pass 1 complete"

# ── Pass 2: Format as JSON ──────────────────────────────────────────
PASS2_PROMPT="Convert the code review below into a JSON object. This is a formatting task — do not add, remove, or modify any review findings.

### Code Review
${PASS1_REVIEW}

### Diff (for line number verification)${TRUNCATION_NOTE}
\`\`\`diff
${PR_DIFF}
\`\`\`

Output ONLY a valid JSON object with this exact structure:
{\"summary\": \"<the five-axis verdict line and overall assessment>\", \"comments\": [{\"path\": \"<file path>\", \"line\": <NEW file line number>, \"body\": \"<comment text>\"}]}

Rules:
- \"summary\": required. Include the five-axis verdict line and the overall assessment from the review.
- \"comments\": array of inline comments from the review. Use an empty array [] if the review has no line-specific comments.
- \"path\": must exactly match a path from the diff headers (the text after +++ b/).
- \"line\": must be the NEW file line number — the line's position in the new version of the file. Verify each line number against the diff's @@ hunk headers. Only reference added or modified lines (lines starting with + in the diff).
- \"body\": include the severity prefix from the review (**Critical:**, **Nit:**, etc.).
- Output ONLY the JSON object. No markdown fences. No explanation. No text before or after."

echo "🧠 Pass 2/2: Formatting as JSON..."

PASS2_RESPONSE=$(call_${AI_PROVIDER} "$PASS2_PROMPT")

[[ -z "$PASS2_RESPONSE" || "$PASS2_RESPONSE" == "null" ]] && {
    echo "⚠️  Empty response from pass 2, falling back to plain comment" >&2
    PASS2_RESPONSE=""
}

# ── Parse pass 2 response ──────────────────────────────────────────
LOG_DIR=$(mktemp -d /tmp/ai-review-XXXXXX)
chmod 700 "$LOG_DIR"
echo "📁 Debug logs: ${LOG_DIR}"

echo "$PASS1_REVIEW" > "${LOG_DIR}/01-pass1-response.txt"
echo "$PASS2_RESPONSE" > "${LOG_DIR}/02-pass2-raw.txt"

# Strip ANSI escape codes
AI_CLEAN=$(sed 's/\x1b\[[0-9;]*m//g' <<< "$PASS2_RESPONSE")
echo "$AI_CLEAN" > "${LOG_DIR}/03-pass2-stripped.txt"

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
echo "$AI_JSON" > "${LOG_DIR}/04-extracted-json.txt"

if ! jq empty <<< "$AI_JSON" 2>/dev/null; then
    jq empty <<< "$AI_JSON" 2> "${LOG_DIR}/05-jq-error.txt" || true
    echo "⚠️  Pass 2 response is not valid JSON, posting pass 1 review as plain comment" >&2
    echo "   See ${LOG_DIR} for debug files" >&2
    # Fallback: post the pass 1 markdown review (high quality, just not structured)
    REVIEW_BODY="${PASS1_REVIEW}"

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
