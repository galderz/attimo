#!/usr/bin/env bash
# ai-review.sh — AI-driven PR review
#
# Usage:
#   ./scripts/ai-review.sh <PR_NUMBER>
#
# Required env vars (never commit these):
#   GITHUB_TOKEN        — GitHub PAT with repo + pull_request write scope
#
# Provider-specific env vars:
#
#   bob (default):
#     BOBSHELL_API_KEY  — Bob API key
#     BOB_API_BASE_URL  — Base URL (default: https://api.bobshell.ai)
#     AI_MODEL          — Model override (default: bob-default)
#
#   vertex:
#     GCLOUD_PROJECT    — Google Cloud project ID
#     GCLOUD_REGION     — Region (default: us-east5)
#     AI_MODEL          — Model override (default: claude-sonnet-4-20250514)
#     (auth via: gcloud auth print-access-token)
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
[[ -z "$PR_NUMBER" ]]        && { echo "Usage: $0 <PR_NUMBER>" >&2; exit 1; }
[[ -z "${GITHUB_TOKEN:-}" ]] && { echo "Error: GITHUB_TOKEN not set" >&2; exit 1; }

REPO=$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##; s#/$##')
AI_PROVIDER="${AI_PROVIDER:-bob}"
REVIEW_EVENT="${REVIEW_EVENT:-COMMENT}"
MAX_DIFF_CHARS=80000

case "$AI_PROVIDER" in
    bob)
        [[ -z "${BOBSHELL_API_KEY:-}" ]] && { echo "Error: BOBSHELL_API_KEY not set" >&2; exit 1; }
        AI_MODEL="${AI_MODEL:-bob-default}"
        BOB_API_BASE_URL="${BOB_API_BASE_URL:-https://api.bobshell.ai}"
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

echo "📋 PR #${PR_NUMBER} in ${REPO} — ${AI_PROVIDER}/${AI_MODEL}"

# ── Fetch PR ─────────────────────────────────────────────────────────
PR_JSON=$(curl -sf "${GH_HEADERS[@]}" "${GH_API}/pulls/${PR_NUMBER}")
PR_DIFF=$(curl -sf -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github.v3.diff" \
    "${GH_API}/pulls/${PR_NUMBER}")

PR_TITLE=$(jq -r '.title' <<< "$PR_JSON")
PR_BODY=$(jq -r '.body // ""' <<< "$PR_JSON")

echo "   ${PR_TITLE}"

TRUNCATION_NOTE=""
if [[ ${#PR_DIFF} -gt $MAX_DIFF_CHARS ]]; then
    PR_DIFF="${PR_DIFF:0:$MAX_DIFF_CHARS}"
    TRUNCATION_NOTE=" (truncated to ${MAX_DIFF_CHARS} chars)"
    echo "⚠️  Diff truncated"
fi

# ── Build prompt ─────────────────────────────────────────────────────
SYSTEM="You are an expert code reviewer. Review the pull request diff.

Focus on: correctness (bugs, logic errors, edge cases), security (vulnerabilities, leaks),
performance (unnecessary allocations, algorithmic complexity), maintainability (naming,
complexity, missing tests), style (consistency).

Be concise and actionable. Use markdown. Quote relevant code for each issue.
If the PR looks good, say so briefly — don't invent problems.
Do NOT repeat the full diff back."

USER_MSG="## PR #${PR_NUMBER}: ${PR_TITLE}

### Description
${PR_BODY}

### Diff${TRUNCATION_NOTE}
\`\`\`diff
${PR_DIFF}
\`\`\`

Review this PR."

echo "🧠 Requesting review..."

# ── Call AI ──────────────────────────────────────────────────────────
call_bob() {
    local payload
    payload=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$SYSTEM" \
        --arg user "$USER_MSG" \
        '{model: $model, max_tokens: 4096,
          messages: [{role: "system", content: $system},
                     {role: "user", content: $user}]}')

    curl -sf \
        -H "Authorization: Bearer ${BOBSHELL_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "${BOB_API_BASE_URL}/v1/chat/completions" \
        | jq -r '.choices[0].message.content'
}

call_vertex() {
    local token
    token=$(gcloud auth print-access-token)

    local endpoint="https://${GCLOUD_REGION}-aiplatform.googleapis.com/v1/projects/${GCLOUD_PROJECT}/locations/${GCLOUD_REGION}/publishers/anthropic/models/${AI_MODEL}:rawPredict"

    local payload
    payload=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$SYSTEM" \
        --arg user "$USER_MSG" \
        '{"anthropic_version": "vertex-2023-10-16",
          "max_tokens": 4096,
          "system": $system,
          "messages": [{role: "user", content: $user}]}')

    curl -sf \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$endpoint" \
        | jq -r '.content[0].text'
}

call_openai() {
    local payload
    payload=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$SYSTEM" \
        --arg user "$USER_MSG" \
        '{model: $model, max_tokens: 4096,
          messages: [{role: "system", content: $system},
                     {role: "user", content: $user}]}')

    curl -sf \
        -H "Authorization: Bearer ${AI_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "https://api.openai.com/v1/chat/completions" \
        | jq -r '.choices[0].message.content'
}

AI_RESPONSE=$(call_${AI_PROVIDER})

[[ -z "$AI_RESPONSE" || "$AI_RESPONSE" == "null" ]] && { echo "Error: empty AI response" >&2; exit 1; }

echo "✅ Review generated (${#AI_RESPONSE} chars)"

# ── Post review ──────────────────────────────────────────────────────
REVIEW_BODY="## 🤖 AI Review (${AI_MODEL})

${AI_RESPONSE}

---
*Automated review by \`ai-review.sh\` — not a substitute for human review.*"

echo "📤 Posting review..."

REVIEW_URL=$(jq -n \
    --arg body "$REVIEW_BODY" \
    --arg event "$REVIEW_EVENT" \
    '{body: $body, event: $event}' \
    | curl -sf "${GH_HEADERS[@]}" \
        -H "Content-Type: application/json" \
        -d @- \
        "${GH_API}/pulls/${PR_NUMBER}/reviews" \
    | jq -r '.html_url')

echo "🎉 Done → ${REVIEW_URL}"
