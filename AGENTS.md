# Agent instructions

Use project-local skills from `.pi/skills/agent-skills` when relevant.

### Getting Started (New Session)

1. **Read `PROGRESS.md` first** — current status, completed work, next tasks, backlog
2. Read `SPEC.md` for full specification and design decisions
3. Read `PLAN.md` for the implementation plan and task breakdown
4. Run `mvn test` and `mvn verify -DskipITs=false` to verify the codebase is healthy
5. Pick up from the **Next Tasks** section in `PROGRESS.md`

### Core Rules

- If a task matches a skill, you MUST invoke it
- Skills are located in `skills/<skill-name>/SKILL.md`
- Never implement directly if a skill applies
- Always follow the skill instructions exactly (do not partially apply them)
- **Every code change** must consider both unit tests (`*Test.java`) and integration tests (`*IT.java`)
- **Never store keys or secrets** in source — generate programmatically (see `TestKeys.java`)
- **Git commit** each logical unit of work with a descriptive message
- **Update `PROGRESS.md`** at the end of each session with: completed tasks, new backlog items, decisions made
- Follow the code style: Allman braces, `final` on params/locals, comma-first (see `SPEC.md`)

### Intent → Skill Mapping

Prefer these workflows:
- `spec-driven-development`, then `incremental-implementation`, `test-driven-development` for feature work
- `planning-and-task-breakdown` for planning or breakdown
- `debugging-and-error-recovery` for fixing bugs, handling failures or dealing with unexpected behavior
- `code-review-and-quality` before final answers or PR-ready changes or code reviews
- `code-simplification` for code refactoring and simplification
- `api-and-interface-design` for API and interface design
- `frontend-ui-engineering` for UI work
