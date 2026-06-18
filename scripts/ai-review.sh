#!/usr/bin/env bash
# ai-review.sh — AI-driven PR review
#
# Usage:
#   ./scripts/ai-review.sh <PR_NUMBER>
#
# Required env vars (never commit these):
#   GITHUB_TOKEN   — GitHub PAT with repo + pull_request write scope
#   AI_API_KEY     — API key for the AI provider
#
# Optional env vars:
#   AI_PROVIDER    — "anthropic" (default) or "openai"
#   AI_MODEL       — Model override (defaults: claude-sonnet-4-20250514 / gpt-4o)
#   REVIEW_EVENT   — "COMMENT" (default), "APPROVE", or "REQUEST_CHANGES"
#
set -euo pipefail

# ── Validate ─────────────────────────────────────────────────────────
PR_NUMBER="${1:-}"
[[ -z "$PR_NUMBER" ]]    && { echo "Usage: $0 <PR_NUMBER>" >&2; exit 1; }
[[ -z "${GITHUB_TOKEN:-}" ]] && { echo "Error: GITHUB_TOKEN not set" >&2; exit 1; }
[[ -z "${AI_API_KEY:-}" ]]   && { echo "Error: AI_API_KEY not set" >&2; exit 1; }

REPO=$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##; s#/$##')
AI_PROVIDER="${AI_PROVIDER:-anthropic}"
REVIEW_EVENT="${REVIEW_EVENT:-COMMENT}"
MAX_DIFF_CHARS=80000

case "$AI_PROVIDER" in
    anthropic) AI_MODEL="${AI_MODEL:-claude-sonnet-4-20250514}" ;;
    openai)    AI_MODEL="${AI_MODEL:-gpt-4o}" ;;
    *)         echo "Error: AI_PROVIDER must be 'anthropic' or 'openai'" >&2; exit 1 ;;
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

# ── Build AI request ─────────────────────────────────────────────────
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

if [[ "$AI_PROVIDER" == "anthropic" ]]; then
    AI_PAYLOAD=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$SYSTEM" \
        --arg user "$USER_MSG" \
        '{model: $model, max_tokens: 4096, system: $system,
          messages: [{role: "user", content: $user}]}')

    AI_RESPONSE=$(curl -sf \
        -H "x-api-key: ${AI_API_KEY}" \
        -H "anthropic-version: 2023-06-01" \
        -H "content-type: application/json" \
        -d "$AI_PAYLOAD" \
        "https://api.anthropic.com/v1/messages" \
        | jq -r '.content[0].text')
else
    AI_PAYLOAD=$(jq -n \
        --arg model "$AI_MODEL" \
        --arg system "$SYSTEM" \
        --arg user "$USER_MSG" \
        '{model: $model, max_tokens: 4096,
          messages: [{role: "system", content: $system},
                     {role: "user", content: $user}]}')

    AI_RESPONSE=$(curl -sf \
        -H "Authorization: Bearer ${AI_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$AI_PAYLOAD" \
        "https://api.openai.com/v1/chat/completions" \
        | jq -r '.choices[0].message.content')
fi

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
