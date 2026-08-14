# Attimo — Progress & Backlog

**Last updated:** 2026-08-14
**Last session:** Continent-based region selection + spot capacity retry (issues #11, #15)

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

### Instance Sizing

| Task | Description | Commit |
|------|-------------|--------|
| — | InstanceSize enum + SpotAdvisor size-aware selection + RequestCommand --size option | pending |

### Multi-Cloud Architecture

| Task | Description | Commit |
|------|-------------|--------|
| — | Rearchitect CLI for per-cloud subcommands (ato aws ...), cloud-aware config/state/SSH paths, move commands to aws/command/ | `c2c2421` |

### Continent-Based Region Selection + Spot Capacity Retry

| Task | Description | Commit |
|------|-------------|--------|
| — | Continent enum (EMEA, Americas, Asia-Pacific) replacing RegionGroup | `5e9b3ae` |
| — | SpotAdvisor: continent-aware tiered scoring, ranked list return | `0ee62e2` |
| — | AttimoConfig: continent field with backward compat | `4d53c3e` |
| — | Commands: continent picker, retry loop on capacity failure, cleanup | `5bc2b08` |
| — | Remove old RegionGroup | `defcc3b` |

### Bug Fixes from Real AWS Testing

| Fix | Description | Commit |
|-----|-------------|--------|
| commons-logging ClassNotFoundException | Switched to UrlConnectionHttpClient (no external deps) | `4bdb61f` |
| `aws login` (login_session) not supported | Added `signin` module, upgraded SDK to 2.46.4 | `91b2953` |
| Fedora 44 AMI not found | Multi-pattern search + multiple owner IDs | `54d5615` |
| Incomplete cleanup (SG deletion fails, state cleared) | SG retry with backoff, state preserved on error, orphan scan | `a6d2850` |
| Opt-in regions (eu-south-1/2) return 401 | Gracefully skip disabled regions | `9f026e5` |
| Test SSH keys hardcoded in source | Generate ephemeral ed25519 keys programmatically | `8a7446e` |
| Fedora AMI not found in opt-in regions | Switched to Amazon Linux 2023 (SSM lookup, works in all regions) | `c26154a` |
| AWS eventual consistency in waitForRunning | Retry on InvalidInstanceID.NotFound after launch | `03c1737` |

## Current State

### What Works End-to-End (tested on real AWS)
- `ato aws init` — validates credentials (including `aws login` / SSO), sets region + SSH key
- `ato aws request --isa avx512` — finds best spot (default: medium size), launches, provisions jdk-dev packages, SSHs in
- `ato aws request --isa avx512 --size large` — uses larger instances for faster builds
- `ato aws status` — shows instance details, uptime, cost
- `ato aws connect` — reconnects to running instance
- `ato aws destroy` — cleans up all resources, scans for orphans if state is lost
- User successfully built OpenJDK and ran commands on a spot instance
- Works in all AWS regions including opt-in regions (eu-central-2, etc.)

### Base OS & Provisioning
- **Amazon Linux 2023** — base AMI, resolved via SSM Parameter Store (works in every region)
- **Amazon Corretto 25** — boot JDK, installed from `yum.corretto.aws` repo
- **capstone** — `capstone`, `capstone-devel` from AL2023 repos
- **SSH user** — `ec2-user`
- **Package manager** — `dnf`

### Test Counts
- **82 unit tests** — all pass, no AWS needed
- **10 integration tests** — all pass via LocalStack + Podman

### Key Technical Decisions Made During Implementation
- **UrlConnectionHttpClient** instead of Apache HTTP client (avoids commons-logging dependency with Quarkus)
- **AWS SDK 2.46.4** (upgraded from 2.31.63) — needed for `signin` module (login_session support)
- **Amazon Linux 2023 via SSM** — replaced Fedora AMI search with SSM Parameter Store lookup (works in all regions)
- **SG deletion retry** — up to 6 retries with 10s delay (AWS needs time to release ENI after termination)
- **Orphan resource scan** — tag-based search across all regions in group, gracefully skips opt-in regions
- **Test keys generated programmatically** — never stored in source, ephemeral ed25519 via Java KeyPairGenerator
- **Multi-cloud subcommand architecture** — all cloud commands under `ato <cloud> ...`, cloud-specific config at `~/.config/attimo/<cloud>/`, shared ISA/SSH/config infrastructure, no new Maven modules needed per cloud

## Next Tasks

### Phase 5: AMI Caching (from PLAN.md)
- [ ] **Task 15: AmiManager** — build, lookup, cleanup AMIs
- [ ] **Task 16: Integrate AMI caching** into AwsRequestCommand + AwsDestroyCommand
- [ ] **Task 17: BuildAmiCommand** — `ato aws build-ami --template <name>`

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
- [x] **SSH user detection** — resolved: using Amazon Linux 2023 (`ec2-user`) exclusively.
- [x] **Region fallback across continents** — resolved: EMEA/Americas/Asia-Pacific continent model with tiered scoring and automatic retry on capacity failure (issues #11, #15).
- [ ] **Region read from AWS config** — `ato init` should read the default region from `~/.aws/config` and offer it as the default instead of always suggesting `us-east-1`.

### Medium Priority
- [ ] **Suppress noisy test output** — unit tests print `Resolved Amazon Linux 2023...`, `Created security group...` etc. to stdout. Consider redirecting System.out in tests or using a logger.
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
├── aws/          # AWS cloud provider
│   ├── command/  # AWS CLI commands (ato aws init/request/status/connect/destroy)
│   └── ...       # SDK clients, spot logic, AMI, cleanup
├── command/      # Shared CLI base (BaseCommand)
├── config/       # Configuration (cloud-aware YAML, region groups)
├── isa/          # CPU ISA feature mapping (shared across clouds)
├── ssh/          # SSH key management, sessions, provisioning (shared)
├── cost/         # (planned) Cost tracking
├── tui/          # (planned) TUI components
└── tool/         # (planned) Tool/template system
```

### Multi-Cloud Architecture
- All cloud commands are under `ato <cloud> <command>` (e.g., `ato aws init`)
- Cloud-specific config stored at `~/.config/attimo/<cloud>/`
- Cloud-specific state stored at `~/.config/attimo/<cloud>/state.yaml`
- Cloud-specific SSH keys at `~/.config/attimo/<cloud>/ssh/`
- ISA mappings are shared at `~/.config/attimo/isa-mappings/`
- To add a new cloud: create a `@GroupCommandDefinition` + commands in `<cloud>/command/`
- No new Maven modules needed

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
