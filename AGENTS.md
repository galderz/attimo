# Agent instructions

Use project-local skills from `.pi/skills/agent-skills` when relevant.

### Core Rules

- If a task matches a skill, you MUST invoke it
- Skills are located in `skills/<skill-name>/SKILL.md`
- Never implement directly if a skill applies
- Always follow the skill instructions exactly (do not partially apply them)

### Intent → Skill Mapping

Prefer these workflows:
- `spec-driven-development`, then `incremental-implementation`, `test-driven-development` for feature work
- `planning-and-task-breakdown` for planning or breakdown
- `debugging-and-error-recovery` for fixing bugs, handling failures or dealing with unexpected behavior
- `code-review-and-quality` before final answers or PR-ready changes or code reviews
- `code-simplification` for code refactoring and simplification
- `api-and-interface-design` for API and interface design
- `frontend-ui-engineering` for UI work
