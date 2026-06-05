# Attimo — Progress & Backlog

**Last updated:** 2026-06-05
**Last session:** Tasks 1–14 implemented + bug fixes from real AWS testing

## How to Resume

If you're a new agent session picking up this project:

1. Read `SPEC.md` for the full specification and design decisions
2. Read `PLAN.md` for the implementation plan and task breakdown
3. Read this file for current status, completed work, and next steps
4. Read `AGENTS.md` for skill invocation rules
5. Run `mvn test` and `mvn verify -DskipITs=false` to verify everything passes
6. Pick up from the **Next Tasks** section below

## Completed Tasks

### Phase 1: Project Skeleton + CI ✅

| Task | Description | Commit |
|------|-------------|--------|
| 1 | Maven project + Quarkus CLI skeleton | `8933f2c` |
| 2 | CI + integration test infrastructure (GitHub Actions, Podman, LocalStack) | `946d823` |
| 3 | Configuration system (AttimoConfig, Environment, InstanceState) | `5efdea1` |
| 4 | AWS client factory + credential validation | `0979efc` |

### Phase 2: Init + ISA Resolution ✅

| Task | Description | Commit |
|------|-------------|--------|
| 5 | ISA mapping (static YAML + dynamic fallback placeholder) | `9b5755c` |
| 6 | Region groups | `02bb3c4` |
| 7 | InitCommand + SshKeyManager | `59acda5` |

### Phase 3: Spot Instance Launch ✅

| Task | Description | Commit |
|------|-------------|--------|
| 8 | SpotAdvisor — pricing + instance selection | `0566b05` |
| 9 | Base AMI auto-resolution | `ddff5fe` |
| 10 | SpotManager — launch + monitor | `f9f66aa` |
| 11 | SSH session management | `7cac155` |
| 12 | RequestCommand — wire it all together | `fca503e` |

### Phase 4: Cleanup + Reconnect ✅

| Task | Description | Commit |
|------|-------------|--------|
| 13 | ResourceCleaner + DestroyCommand | `71790dc` |
| 14 | StatusCommand + ConnectCommand | `a2dbe76` |

### Bug Fixes from Real AWS Testing

| Fix | Description | Commit |
|-----|-------------|--------|
| commons-logging ClassNotFoundException | Switched to UrlConnectionHttpClient (no external deps) | `4bdb61f` |
| `aws login` (login_session) not supported | Added `signin` module, upgraded SDK to 2.46.4 | `91b2953` |
| Fedora 44 AMI not found | Multi-pattern search + multiple owner IDs | `54d5615` |
| Incomplete cleanup (SG deletion fails, state cleared) | SG retry with backoff, state preserved on error, orphan scan | `a6d2850` |
| Opt-in regions (eu-south-1/2) return 401 | Gracefully skip disabled regions | `9f026e5` |
| Test SSH keys hardcoded in source | Generate ephemeral ed25519 keys programmatically | `8a7446e` |

## Current State

### What Works End-to-End (tested on real AWS)
- `ato init` — validates credentials (including `aws login` / SSO), sets region + SSH key
- `ato request --isa avx512` — finds cheapest spot, launches, provisions jdk-dev packages, SSHs in
- `ato status` — shows instance details, uptime, cost
- `ato connect` — reconnects to running instance
- `ato destroy` — cleans up all resources, scans for orphans if state is lost
- User successfully built OpenJDK and ran commands on a spot instance

### Test Counts
- **57 unit tests** — all pass, no AWS needed
- **11 integration tests** — all pass via LocalStack + Podman

### Key Technical Decisions Made During Implementation
- **UrlConnectionHttpClient** instead of Apache HTTP client (avoids commons-logging dependency with Quarkus)
- **AWS SDK 2.46.4** (upgraded from 2.31.63) — needed for `signin` module (login_session support)
- **Fedora AMI multi-pattern search** — naming convention changed; try 3 patterns in order
- **SG deletion retry** — up to 6 retries with 10s delay (AWS needs time to release ENI after termination)
- **Orphan resource scan** — tag-based search across all regions in group, gracefully skips opt-in regions
- **Test keys generated programmatically** — never stored in source, ephemeral ed25519 via Java KeyPairGenerator

## Next Tasks

### Phase 5: AMI Caching (from PLAN.md)
- [ ] **Task 15: AmiManager** — build, lookup, cleanup AMIs
- [ ] **Task 16: Integrate AMI caching** into RequestCommand + DestroyCommand
- [ ] **Task 17: BuildAmiCommand** — `ato build-ami --template <name>`

### Phase 6: Spot Interruption Recovery
- [ ] **Task 18:** Background thread monitoring + automatic replacement

### Phase 7: Cost Tracking + TUI
- [ ] **Task 19:** CostTracker + `ato cost` command
- [ ] **Task 20:** TUI main view (Tamboui)

### Phase 8: Template System
- [ ] **Task 21:** Image template + tool YAML system (incus-spawn style)

## Backlog — New Ideas & Improvements

Items discovered during implementation that aren't in the original plan:

### High Priority
- [ ] **jtreg tool provisioning** — currently jtreg is NOT pre-installed (only dnf packages). Need to add jtreg download + install as part of provisioning. The spec has the jtreg.yaml tool definition but the template system (Task 21) isn't implemented yet. For now, users must install jtreg manually.
- [ ] **SSH user detection** — currently hardcoded to `fedora` user. Should detect from AMI metadata or make configurable (Amazon Linux uses `ec2-user`, Ubuntu uses `ubuntu`).
- [ ] **Region read from AWS config** — `ato init` should read the default region from `~/.aws/config` and offer it as the default instead of always suggesting `us-east-1`.

### Medium Priority
- [ ] **Suppress noisy test output** — unit tests print `Resolved fedora-44...`, `Created security group...` etc. to stdout. Consider redirecting System.out in tests or using a logger.
- [ ] **VPC handling** — current SG creation uses the default VPC. Some accounts may not have a default VPC, which would cause failures.
- [ ] **Instance type validation** — verify the selected instance type is actually available in the target AZ before launching.
- [ ] **Spot price in `ato request` output** — show estimated hourly cost before launching so user can confirm.
- [ ] **Shell completion** — `ato completion bash/zsh/fish` for tab completion of commands and ISA features.

### Low Priority
- [ ] **`ato list-isa`** — command to list all available ISA features with descriptions.
- [ ] **Config validation** — validate region exists, SSH key path exists, warn if stale.
- [ ] **Uber-jar profile** — `mvn package -Prelease` for single-JAR distribution.
- [ ] **Native image** — GraalVM native compilation for instant startup.

## Architecture Notes for Future Sessions

### Package Structure
```
org.mendrugo.attimo/
├── aws/          # AWS interaction (SDK clients, spot logic, AMI, cleanup)
├── command/      # CLI commands (Aesh framework)
├── config/       # Configuration (YAML files, region groups)
├── isa/          # CPU ISA feature mapping
├── ssh/          # SSH key management, sessions, provisioning
├── cost/         # (planned) Cost tracking
├── tui/          # (planned) TUI components
└── tool/         # (planned) Tool/template system
```

### Code Style
- **Allman braces** — `{` on its own line
- **`final` on all parameters and locals**
- **Comma-first** — `, secondArg` on new line
- **No wildcard imports**
- See `SPEC.md` Code Style section for full examples

### Testing Rules
- Every code change must consider both unit tests AND integration tests
- Never store keys or secrets in source — generate programmatically (see `TestKeys.java`)
- Unit tests: `*Test.java`, run with `mvn test`
- Integration tests: `*IT.java`, run with `mvn verify -DskipITs=false`, require Podman
- All AWS interactions must be testable without real AWS
