# Implementation Plan: Attimo v1 — Fastest Path to a Working Spot Instance

## Overview

Prioritise the shortest path to: `ato request --isa avx512` → SSH into a spot instance → build OpenJDK → run a jtreg test. Everything else (TUI, cost tracking, AMI caching, spot interruption recovery) comes after this core path works end-to-end.

## Dependency Graph

```
Maven project + Quarkus skeleton
    │
    ├── Config system (AttimoConfig, YAML)
    │       │
    │       ├── ISA mapping (static YAML)
    │       │       │
    │       │       └── SpotAdvisor (pricing + instance selection)
    │       │
    │       ├── AWS client factory + credential validation
    │       │       │
    │       │       ├── SpotAdvisor (needs EC2 client)
    │       │       │       │
    │       │       │       └── RequestCommand
    │       │       │
    │       │       ├── AmiManager (build, lookup, cleanup)
    │       │       │       │
    │       │       │       └── RequestCommand
    │       │       │
    │       │       └── ResourceCleaner
    │       │               │
    │       │               └── DestroyCommand
    │       │
    │       └── SSH key management
    │               │
    │               └── RequestCommand (inject key, connect)
    │
    └── CLI framework (Aesh BaseCommand)
            │
            ├── InitCommand
            ├── RequestCommand (the critical path)
            ├── StatusCommand
            ├── ConnectCommand
            ├── DestroyCommand
            └── TUI (ListCommand)
```

## Architecture Decisions

- **Vertical slicing**: build the `request` → `destroy` path first, end-to-end
- **Skip AMI caching for phase 1**: provision over SSH on every launch — slower but gets us to a working system fastest. AMI caching is phase 2
- **Skip TUI for phase 1**: CLI-only until the core lifecycle works
- **Skip spot interruption recovery for phase 1**: handle it in phase 2
- **Minimal init**: just validate credentials and set region + SSH key

## Task List

### Phase 1: Project Skeleton

#### Task 1: Maven project + Quarkus CLI skeleton

**Description:** Create the Maven project with Quarkus, Aesh, Jackson YAML, AWS SDK v2 dependencies. Set up the main entry point and BaseCommand. Verify it compiles and runs `ato --version`.

**Acceptance criteria:**
- [ ] `mvn package` succeeds
- [ ] `java -jar target/attimo-*.jar --version` prints version info
- [ ] Aesh CLI framework is wired up with a root command
- [ ] Java 25 compiler target configured
- [ ] Code follows Aeron + Elm comma-first style

**Verification:**
- [ ] `mvn package` exits 0
- [ ] Running the JAR with `--version` produces output

**Dependencies:** None

**Files likely touched:**
- `pom.xml`
- `src/main/java/org/mendrugo/attimo/Attimo.java`
- `src/main/java/org/mendrugo/attimo/BuildInfo.java`
- `src/main/java/org/mendrugo/attimo/command/BaseCommand.java`
- `src/main/resources/application.properties`

**Estimated scope:** Small

---

#### Task 2: Configuration system

**Description:** Implement `AttimoConfig` (load/save YAML from `~/.config/attimo/config.yaml`), `Environment` (XDG paths), and state file (`~/.config/attimo/state.yaml`). Follow incus-spawn's `SpawnConfig` pattern.

**Acceptance criteria:**
- [ ] `AttimoConfig` loads from YAML, returns defaults when file missing
- [ ] `AttimoConfig.save()` writes with owner-only permissions (chmod 600)
- [ ] `Environment` resolves `~/.config/attimo/` and `~/.cache/attimo/` paths
- [ ] State file model can serialise/deserialise active instance info

**Verification:**
- [ ] `AttimoConfigTest` — load, save, defaults, validation
- [ ] `mvn test` passes

**Dependencies:** Task 1

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/config/AttimoConfig.java`
- `src/main/java/org/mendrugo/attimo/Environment.java`
- `src/main/java/org/mendrugo/attimo/config/InstanceState.java`
- `src/test/java/org/mendrugo/attimo/config/AttimoConfigTest.java`

**Estimated scope:** Small

---

#### Task 3: AWS client factory + credential validation

**Description:** Implement `AwsClientFactory` that creates regional EC2/STS clients using the default credential chain. Include a `validateCredentials()` method that calls STS `GetCallerIdentity`.

**Acceptance criteria:**
- [ ] `AwsClientFactory.ec2(region)` returns a configured `Ec2Client`
- [ ] `AwsClientFactory.validateCredentials()` returns null on success, error message on failure
- [ ] Clients use the default credential chain (no credential storage)

**Verification:**
- [ ] `AwsClientFactoryTest` — mock STS client, verify success/failure paths
- [ ] `mvn test` passes

**Dependencies:** Task 1

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/AwsClientFactory.java`
- `src/main/java/org/mendrugo/attimo/aws/AwsException.java`
- `src/test/java/org/mendrugo/attimo/aws/AwsClientFactoryTest.java`

**Estimated scope:** Small

---

### Checkpoint: Project Skeleton
- [ ] `mvn test` passes
- [ ] `mvn package` produces runnable JAR
- [ ] Config and AWS client foundation in place

---

### Phase 2: Init + ISA Resolution

#### Task 4: ISA mapping (static YAML + dynamic fallback)

**Description:** Implement `IsaMapping` that loads static YAML mappings from classpath, resolves ISA feature names to AWS instance families, and falls back to `DescribeInstanceTypes` for unknown features. User overrides from `~/.config/attimo/isa-mappings/`.

**Acceptance criteria:**
- [ ] Static mappings loaded from `src/main/resources/isa-mappings/x86_64.yaml` and `aarch64.yaml`
- [ ] `resolve("avx512")` returns list of instance families (`c5`, `c6i`, `c7i`, etc.)
- [ ] `resolve("sve")` returns AArch64 Graviton3 families
- [ ] Unknown features trigger dynamic AWS API fallback (mocked in tests)
- [ ] User overrides at `~/.config/attimo/isa-mappings/` take precedence

**Verification:**
- [ ] `IsaMappingTest` — static resolution, user overrides, unknown feature fallback
- [ ] `mvn test` passes

**Dependencies:** Task 1

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/isa/IsaMapping.java`
- `src/main/java/org/mendrugo/attimo/isa/IsaFeature.java`
- `src/main/resources/isa-mappings/x86_64.yaml`
- `src/main/resources/isa-mappings/aarch64.yaml`
- `src/test/java/org/mendrugo/attimo/isa/IsaMappingTest.java`

**Estimated scope:** Medium

---

#### Task 5: Region groups

**Description:** Implement `RegionGroup` enum with geographic groupings of AWS regions. Given a preferred region, return all regions in the same group for spot pricing comparison.

**Acceptance criteria:**
- [ ] `RegionGroup.forRegion("eu-west-1")` returns the EUROPE group
- [ ] Group contains all European regions
- [ ] Unknown region throws clear error

**Verification:**
- [ ] `RegionGroupTest` — all groups, lookup, unknown region
- [ ] `mvn test` passes

**Dependencies:** Task 1

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/config/RegionGroup.java`
- `src/test/java/org/mendrugo/attimo/config/RegionGroupTest.java`

**Estimated scope:** XS

---

#### Task 6: InitCommand

**Description:** Implement `ato init` — validates AWS credentials, prompts for preferred region, configures SSH public key path. Provides install instructions for `aws` CLI when credentials are missing.

**Acceptance criteria:**
- [ ] Validates credentials via `AwsClientFactory.validateCredentials()`
- [ ] On failure: prints platform-specific install instructions (Fedora, Ubuntu, macOS, NixOS)
- [ ] On failure: offers to enter access key directly (writes `~/.aws/credentials`)
- [ ] Prompts for preferred region (with sensible default based on latency or user input)
- [ ] Prompts for SSH public key path (defaults to `~/.ssh/id_ed25519.pub`)
- [ ] Saves config to `~/.config/attimo/config.yaml`
- [ ] Generates managed SSH key pair at `~/.config/attimo/ssh/`

**Verification:**
- [ ] Manual test: `ato init` runs interactively
- [ ] `mvn test` passes (no new unit tests needed — interactive command)

**Dependencies:** Tasks 2, 3

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/InitCommand.java`
- `src/main/java/org/mendrugo/attimo/ssh/SshKeyManager.java`
- `src/test/java/org/mendrugo/attimo/ssh/SshKeyManagerTest.java`

**Estimated scope:** Medium

---

### Checkpoint: Init + ISA
- [ ] `mvn test` passes
- [ ] `ato init` works end-to-end interactively
- [ ] ISA features resolve to instance families
- [ ] Region groups work

---

### Phase 3: Spot Instance Launch (The Critical Path)

#### Task 7: SpotAdvisor — pricing + instance selection

**Description:** Implement spot pricing analysis. Given ISA-resolved instance families and a region group, query `DescribeSpotPriceHistory` across regions, score candidates by price + interruption rate + size bias + proximity, and return the best recommendation.

**Acceptance criteria:**
- [ ] Expands families to specific instance types (e.g., `c7i` → `c7i.large`, `c7i.xlarge`, etc.)
- [ ] Queries spot pricing across all regions in the group
- [ ] Scores: price (primary), instance size bias (prefer larger), region proximity (prefer closer when within 15%)
- [ ] Returns `SpotRecommendation` with instance type, region, AZ, price, rationale
- [ ] Falls back to adjacent regions when preferred region has no capacity

**Verification:**
- [ ] `SpotAdvisorTest` — mocked pricing responses, cheapest selection, proximity preference, no-capacity fallback
- [ ] `mvn test` passes

**Dependencies:** Tasks 3, 4, 5

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/SpotAdvisor.java`
- `src/main/java/org/mendrugo/attimo/aws/SpotRecommendation.java`
- `src/test/java/org/mendrugo/attimo/aws/SpotAdvisorTest.java`

**Estimated scope:** Medium

---

#### Task 8: Base AMI auto-resolution

**Description:** Implement Fedora AMI lookup via `DescribeImages` filtered by owner and name pattern (e.g., `Fedora-Cloud-Base-44-*`). Resolve to the correct AMI ID for a given region and architecture (x86_64 or arm64).

**Acceptance criteria:**
- [ ] `resolveBaseAmi("fedora-44", region, arch)` returns an AMI ID
- [ ] Handles x86_64 and arm64 architectures
- [ ] Clear error when no matching AMI found
- [ ] Caches AMI IDs per region for the session (avoid repeated API calls)

**Verification:**
- [ ] `BaseAmiResolverTest` — mocked `DescribeImages`, both architectures, no-match error
- [ ] `mvn test` passes

**Dependencies:** Task 3

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/BaseAmiResolver.java`
- `src/test/java/org/mendrugo/attimo/aws/BaseAmiResolverTest.java`

**Estimated scope:** Small

---

#### Task 9: SpotManager — launch + monitor

**Description:** Implement spot instance launch using `RunInstances` with `InstanceMarketOptions`. Creates security group (SSH-only), imports SSH key pair, launches instance, waits for running + status checks, returns connection info. Tags all resources.

**Acceptance criteria:**
- [ ] Creates security group allowing inbound SSH (port 22) from 0.0.0.0/0
- [ ] Imports SSH public key as EC2 key pair
- [ ] Launches spot instance with correct AMI, instance type, SG, key pair
- [ ] All resources tagged with `attimo:managed`, `attimo:session-id`, etc.
- [ ] Waits for instance to reach `running` state and pass status checks
- [ ] Returns `InstanceInfo` (ID, IP, region, AZ, instance type)
- [ ] Updates state file with active instance info

**Verification:**
- [ ] `SpotManagerTest` — mocked EC2 client: launch flow, resource tagging, wait loop
- [ ] `mvn test` passes

**Dependencies:** Tasks 2, 3, 8

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/SpotManager.java`
- `src/test/java/org/mendrugo/attimo/aws/SpotManagerTest.java`

**Estimated scope:** Medium

---

#### Task 10: SSH session management

**Description:** Implement `SshSession` — waits for SSH to be reachable (retry loop), then launches `ssh` via `ProcessBuilder` with the managed key. Monitor subprocess exit and spot termination (EC2 API polling in background thread).

**Acceptance criteria:**
- [ ] Waits for SSH port 22 to be reachable (retry with backoff, timeout after 5 minutes)
- [ ] Launches `ssh -i <key> -o StrictHostKeyChecking=no ec2-user@<ip>` (or `fedora@` depending on AMI)
- [ ] SSH subprocess inherits stdin/stdout/stderr (interactive terminal)
- [ ] Returns exit code from SSH process
- [ ] On normal exit (user types `exit`): prompts "Keep instance running? (y/N)"

**Verification:**
- [ ] `SshSessionTest` — verify command construction, key path, options
- [ ] `mvn test` passes

**Dependencies:** Task 6 (SSH key manager)

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/ssh/SshSession.java`
- `src/test/java/org/mendrugo/attimo/ssh/SshSessionTest.java`

**Estimated scope:** Small

---

#### Task 11: RequestCommand — wire it all together

**Description:** Implement `ato request --isa <feature> [--template <name>]`. This is the main command that ties SpotAdvisor, SpotManager, and SshSession together. For phase 1, skip AMI caching — provision over SSH after launch using the base Fedora AMI directly.

**Acceptance criteria:**
- [ ] Validates init has been run (credentials + config exist)
- [ ] Resolves ISA → instance types via IsaMapping
- [ ] Finds best spot option via SpotAdvisor
- [ ] Prints recommendation: "Launching c7i.xlarge in eu-west-2 ($0.067/hr)..."
- [ ] Launches spot instance via SpotManager
- [ ] Provisions packages over SSH (dnf install from template)
- [ ] SSHs into instance
- [ ] On SSH exit: prompts to keep or destroy

**Verification:**
- [ ] Manual end-to-end test: `ato request --isa avx512` → SSH session on real AWS
- [ ] `mvn test` passes (unit tests with mocks)

**Dependencies:** Tasks 4, 7, 8, 9, 10

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/RequestCommand.java`
- `src/main/java/org/mendrugo/attimo/ssh/SshProvisioner.java`

**Estimated scope:** Medium

---

### Checkpoint: End-to-End Launch
- [ ] `mvn test` passes
- [ ] `ato request --isa avx512` launches a spot instance and drops into SSH
- [ ] Can build OpenJDK on the instance
- [ ] Can run a basic jtreg test on the instance
- [ ] **Review with human before proceeding**

---

### Phase 4: Cleanup + Reconnect

#### Task 12: ResourceCleaner + DestroyCommand

**Description:** Implement `ato destroy` — tears down all resources created for the active instance. Terminate instance, delete security group, delete key pair. Report what was cleaned up.

**Acceptance criteria:**
- [ ] Terminates EC2 instance
- [ ] Deletes security group (waits for instance to terminate first — SG can't be deleted while in use)
- [ ] Deletes imported key pair
- [ ] Each step logged clearly
- [ ] Continues cleanup on partial failure (best-effort)
- [ ] Clears state file
- [ ] Verifies no resources remain via describe calls
- [ ] Clear error if no active instance exists

**Verification:**
- [ ] `ResourceCleanerTest` — mocked EC2: full cleanup, partial failure, orphan detection
- [ ] `mvn test` passes

**Dependencies:** Tasks 2, 3

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/ResourceCleaner.java`
- `src/main/java/org/mendrugo/attimo/command/DestroyCommand.java`
- `src/test/java/org/mendrugo/attimo/aws/ResourceCleanerTest.java`

**Estimated scope:** Medium

---

#### Task 13: StatusCommand + ConnectCommand

**Description:** Implement `ato status` (show running instance info) and `ato connect` (SSH into existing instance). Both read from the state file.

**Acceptance criteria:**
- [ ] `ato status` shows: instance type, region, IP, uptime, ISA, template
- [ ] `ato status` verifies instance is actually running via EC2 API (handles stale state)
- [ ] `ato connect` SSHs into the instance using SshSession
- [ ] Clear error when no active instance exists

**Verification:**
- [ ] Manual test: `ato status` and `ato connect` after a `ato request`
- [ ] `mvn test` passes

**Dependencies:** Tasks 2, 10

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/StatusCommand.java`
- `src/main/java/org/mendrugo/attimo/command/ConnectCommand.java`

**Estimated scope:** Small

---

### Checkpoint: Full CLI Lifecycle
- [ ] `mvn test` passes
- [ ] `ato request` → `ato status` → `ato connect` → `ato destroy` all work
- [ ] `ato destroy` leaves zero AWS resources
- [ ] **Review with human before proceeding**

---

### Phase 5: AMI Caching

#### Task 14: AmiManager — build, lookup, cleanup

**Description:** Implement AMI build flow: launch cheap on-demand instance, provision over SSH, snapshot to AMI, terminate build instance. Lookup existing AMIs by attimo tags. Cleanup (deregister + delete snapshot).

**Acceptance criteria:**
- [ ] `buildAmi(template, region, arch)` provisions and creates AMI
- [ ] AMI named `attimo-<template>-<arch>-<timestamp>`
- [ ] AMI tagged with `attimo:managed`, `attimo:template`, `attimo:arch`
- [ ] `findAmi(template, region, arch)` looks up existing AMI by tags
- [ ] `deleteAmi(amiId)` deregisters AMI + deletes associated snapshot
- [ ] Cross-region AMI copy when best spot price is in a different region

**Verification:**
- [ ] `AmiManagerTest` — mocked EC2: build flow, lookup, cleanup, cross-region copy
- [ ] `mvn test` passes

**Dependencies:** Tasks 3, 8, SSH provisioner from Task 11

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/AmiManager.java`
- `src/test/java/org/mendrugo/attimo/aws/AmiManagerTest.java`

**Estimated scope:** Medium

---

#### Task 15: Integrate AMI caching into RequestCommand + DestroyCommand

**Description:** Update `ato request` to check for cached AMI before provisioning. Update `ato destroy` to prompt for AMI cleanup.

**Acceptance criteria:**
- [ ] Request checks for existing AMI first; skips provisioning if found
- [ ] If no AMI: provisions, then builds AMI for next time
- [ ] Destroy prompts: "AMI exists. Keep for future use? (y/N)"
- [ ] Default No: deletes AMI + snapshot
- [ ] Yes: keeps AMI, notes ongoing storage cost

**Verification:**
- [ ] Manual test: first request provisions + builds AMI; second request uses cached AMI
- [ ] `mvn test` passes

**Dependencies:** Tasks 11, 12, 14

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/RequestCommand.java`
- `src/main/java/org/mendrugo/attimo/command/DestroyCommand.java`

**Estimated scope:** Small

---

#### Task 16: BuildAmiCommand

**Description:** Implement `ato build-ami --template <name>` to pre-build an AMI without launching a spot instance.

**Acceptance criteria:**
- [ ] Builds AMI using AmiManager
- [ ] Reports AMI ID, region, size on completion
- [ ] Works independently of `ato request`

**Verification:**
- [ ] Manual test: `ato build-ami --template jdk-dev`
- [ ] `mvn test` passes

**Dependencies:** Task 14

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/BuildAmiCommand.java`

**Estimated scope:** XS

---

### Checkpoint: AMI Caching
- [ ] `mvn test` passes
- [ ] Second `ato request` launches in ~60 seconds (no provisioning)
- [ ] `ato destroy` offers AMI cleanup
- [ ] `ato build-ami` works standalone
- [ ] **Review with human before proceeding**

---

### Phase 6: Spot Interruption Recovery

#### Task 17: Spot termination detection + automatic replacement

**Description:** Add background thread to RequestCommand that polls EC2 API for instance state changes. On spot termination: notify user, request replacement instance (same AMI, same or fallback region), reconnect SSH.

**Acceptance criteria:**
- [ ] Background thread polls `DescribeInstances` every 30 seconds
- [ ] Detects `terminated` or `shutting-down` state
- [ ] Prints: "⚠ Spot instance reclaimed. Requesting replacement..."
- [ ] Launches new instance via SpotManager (same AMI)
- [ ] Falls back to adjacent region if original region has no capacity
- [ ] Reconnects SSH automatically
- [ ] Prints: "✓ New instance ready in <region>. Reconnected."
- [ ] Updates state file with new instance info

**Verification:**
- [ ] `SpotManagerTest` — mock termination detection, replacement flow
- [ ] `mvn test` passes

**Dependencies:** Tasks 9, 10, 14

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/aws/SpotManager.java`
- `src/main/java/org/mendrugo/attimo/ssh/SshSession.java`
- `src/main/java/org/mendrugo/attimo/command/RequestCommand.java`

**Estimated scope:** Medium

---

### Checkpoint: Resilient Lifecycle
- [ ] `mvn test` passes
- [ ] Spot interruption handled gracefully (manual test or simulated)
- [ ] **Review with human before proceeding**

---

### Phase 7: Cost Tracking + TUI

#### Task 18: Cost tracking

**Description:** Implement `CostTracker` — calculates ongoing and total session cost. Add `ato cost` command. Display cost in `ato status` and on `ato destroy`.

**Acceptance criteria:**
- [ ] Ongoing cost: `(now - launch_time) × spot_price_per_hour`
- [ ] `ato cost` shows current cost
- [ ] `ato destroy` shows total session cost
- [ ] `ato status` includes cost info
- [ ] Notes AMI storage cost if AMI is kept

**Verification:**
- [ ] `CostTrackerTest` — cost calculation, multi-instance sessions
- [ ] `mvn test` passes

**Dependencies:** Tasks 2, 13

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/cost/CostTracker.java`
- `src/main/java/org/mendrugo/attimo/command/CostCommand.java`
- `src/test/java/org/mendrugo/attimo/cost/CostTrackerTest.java`

**Estimated scope:** Small

---

#### Task 19: TUI main view

**Description:** Implement the TUI using Tamboui (launched with bare `ato`). Shows instance status, templates, quick actions. Follows incus-spawn's ListCommand pattern.

**Acceptance criteria:**
- [ ] `ato` (no args) launches TUI
- [ ] Shows active instance info (or "No active instance")
- [ ] Shows available templates with AMI status
- [ ] Keyboard shortcuts: R=request, C=connect, D=destroy, Q=quit
- [ ] F1=help, F5=request, F8=destroy, F10=quit
- [ ] Refreshes instance status periodically

**Verification:**
- [ ] Manual test: TUI renders correctly, actions work
- [ ] `mvn test` passes

**Dependencies:** Tasks 11, 12, 13, 18

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/command/ListCommand.java`
- `src/main/java/org/mendrugo/attimo/tui/BackgroundTask.java`
- `src/main/java/org/mendrugo/attimo/tui/BackgroundTaskManager.java`
- `src/main/java/org/mendrugo/attimo/Attimo.java` (wire TUI as default)

**Estimated scope:** Large (but self-contained — TUI only)

---

### Checkpoint: Feature Complete v1
- [ ] `mvn test` passes
- [ ] Full CLI lifecycle works
- [ ] TUI works
- [ ] Cost tracking works
- [ ] Spot interruption recovery works
- [ ] **Review with human before proceeding**

---

### Phase 8: CI + Template System

#### Task 20: GitHub Actions CI

**Description:** Set up GitHub Actions workflow for unit tests and integration tests (LocalStack).

**Acceptance criteria:**
- [ ] CI runs on push to main and PRs
- [ ] Unit tests job: `mvn test` with OpenJDK 25
- [ ] Integration tests job: `mvn verify -DskipITs=false` with LocalStack
- [ ] Both jobs pass

**Verification:**
- [ ] Push to repo, CI runs green

**Dependencies:** All previous tasks

**Files likely touched:**
- `.github/workflows/ci.yml`

**Estimated scope:** XS

---

#### Task 21: Image template + tool system

**Description:** Implement the incus-spawn-style YAML template and tool system. `ImageDef` loads templates, `ToolDef` loads tools. `SshProvisioner` executes them over SSH during AMI build.

**Acceptance criteria:**
- [ ] `ImageDef` loads from classpath, user dir, project-local (resolution order)
- [ ] `ToolDef` loads YAML tools with packages, run, run_as_user, files, env, verify
- [ ] Built-in `jdk-dev` template and `jtreg` tool work
- [ ] SshProvisioner executes template packages + tools in correct order

**Verification:**
- [ ] `ImageDefTest` — loading, resolution order
- [ ] `ToolDefTest` — parsing, execution order
- [ ] `mvn test` passes

**Dependencies:** Task 11 (SshProvisioner exists)

**Files likely touched:**
- `src/main/java/org/mendrugo/attimo/config/ImageDef.java`
- `src/main/java/org/mendrugo/attimo/tool/ToolDef.java`
- `src/main/java/org/mendrugo/attimo/tool/ToolDefLoader.java`
- `src/main/java/org/mendrugo/attimo/tool/ToolSetup.java`
- `src/test/java/org/mendrugo/attimo/config/ImageDefTest.java`
- `src/test/java/org/mendrugo/attimo/tool/ToolDefTest.java`

**Estimated scope:** Medium

---

### Checkpoint: Complete
- [ ] All acceptance criteria met
- [ ] CI passes
- [ ] Template system works
- [ ] Ready for real-world use

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| AWS SDK v2 for Java 25 compatibility | High | Verify SDK works with Java 25 in Task 1. If issues, pin to latest compatible SDK version |
| Fedora AMI auto-resolution fails (owner IDs change, naming conventions differ) | Medium | Hard-code known Fedora owner ID. Fall back to manual AMI ID entry if auto-resolution fails |
| Spot capacity unavailable in any region group | Medium | SpotAdvisor already searches all regions in group. Could expand to cross-group search with `--any-region` flag |
| SSH provisioning too slow for first-time use | Low | Acceptable for v1. AMI caching (Phase 5) eliminates this for subsequent uses |
| LocalStack EC2 support incomplete (spot-specific APIs) | Medium | Use Mockito for unit tests (primary). LocalStack for integration tests where it supports the APIs. Skip unsupported LocalStack tests gracefully |
| Tamboui/Aesh compatibility with Java 25 | Medium | Verify in Task 1. These are used by incus-spawn with Java 25 already |

## Phase Priority Summary

| Phase | What it delivers | Needed for goal? |
|-------|-----------------|------------------|
| 1. Project Skeleton | Compiling project with CLI framework | Yes — foundation |
| 2. Init + ISA | `ato init`, ISA→instance type resolution | Yes — prerequisite |
| 3. Spot Launch | **`ato request` → SSH into spot instance** | **Yes — the goal** |
| 4. Cleanup + Reconnect | `ato destroy`, `ato status`, `ato connect` | Yes — must clean up |
| 5. AMI Caching | Fast subsequent launches | Nice to have for v1 |
| 6. Spot Interruption | Auto-recovery from spot termination | Nice to have for v1 |
| 7. Cost + TUI | Cost tracking, interactive TUI | Nice to have for v1 |
| 8. CI + Templates | GitHub Actions, full template system | Nice to have for v1 |

**The goal (build JDK + run jtreg) is achievable after Phase 4.** Phases 5-8 improve the experience but are not blocking.
