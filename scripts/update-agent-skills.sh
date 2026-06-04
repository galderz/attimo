#!/usr/bin/env bash
set -euo pipefail

mkdir -p .pi/skills
tmpdir="$(mktemp -d)"
git clone --depth=1 https://github.com/addyosmani/agent-skills.git "$tmpdir/agent-skills"

rm -rf .pi/skills/agent-skills
mkdir -p .pi/skills/agent-skills
cp -R "$tmpdir/agent-skills/skills/"* .pi/skills/agent-skills/

git add .pi/skills/agent-skills
git commit -m "Update agent skills"
