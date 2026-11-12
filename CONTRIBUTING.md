# Contributing to Attimo

## Prerequisites

- **Java 25** — [Adoptium Temurin](https://adoptium.net/) or your preferred OpenJDK distribution
- **Maven 3.9+** — [https://maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)
- **Podman** (or Docker) — required only for integration tests

### Installing prerequisites

**Fedora:**

```bash
sudo dnf install java-25-openjdk-devel maven podman
```

**Ubuntu/Debian:**

```bash
# Java 25 via Adoptium (https://adoptium.net/installation/linux/)
# Maven
sudo apt install maven
# Podman
sudo apt install podman
```

**macOS:**

```bash
brew install openjdk@25 maven podman
```

**Nix:**

```bash
nix-env -iA nixpkgs.jdk25 nixpkgs.maven nixpkgs.podman
```

Verify your setup:

```bash
java -version    # should show 25.x
mvn -version     # should show 3.9+
podman --version # needed for integration tests only
```

## Building

```bash
mvn package
```

This compiles the source, runs unit tests, and produces the runnable JAR at `target/quarkus-app/quarkus-run.jar`.

To skip tests during the build:

```bash
mvn package -DskipTests
```

## Running locally

After building, run attimo with:

```bash
java -jar target/quarkus-app/quarkus-run.jar --help
```

For convenience, create a shell alias:

```bash
# Add to ~/.bashrc or ~/.zshrc
alias ato='java -jar /path/to/attimo/target/quarkus-app/quarkus-run.jar'
```

Or create a wrapper script:

```bash
#!/bin/bash
# Save as ~/bin/ato and chmod +x ~/bin/ato
exec java -jar /path/to/attimo/target/quarkus-app/quarkus-run.jar "$@"
```

Then use it normally:

```bash
ato --version
ato init
ato request --isa avx512
```

### Quarkus dev mode

For rapid development with hot reload:

```bash
mvn quarkus:dev -Dquarkus.args="--help"
mvn quarkus:dev -Dquarkus.args="status"
```

Note: interactive commands (`init`, `request`) that read from the console may not work well in dev mode. Build and run the JAR directly for those.

## Running tests

### Unit tests

```bash
mvn test
```

Unit tests do **not** require AWS credentials, a container runtime, or any external service. They use Mockito to mock AWS SDK clients. These should always pass on any machine with Java 25.

### Integration tests

```bash
mvn verify -DskipITs=false
```

Integration tests use [LocalStack](https://localstack.cloud/) via [Testcontainers](https://testcontainers.com/) to simulate AWS services locally. They require a container runtime (Podman or Docker).

**Podman setup for Testcontainers:**

```bash
# Start the Podman socket (needed by Testcontainers)
systemctl --user start podman.socket

# Set the Docker host for Testcontainers
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

# Disable Ryuk (cleanup container) — often not needed with Podman
export TESTCONTAINERS_RYUK_DISABLED=true
```

You can add these exports to your shell profile to avoid repeating them.

**Docker setup:**

If using Docker instead of Podman, no additional configuration is needed — Testcontainers auto-detects the Docker socket.

### Running a specific test

```bash
# Single test class
mvn test -Dtest=SpotAdvisorTest

# Single test method
mvn test -Dtest=SpotAdvisorTest#selectsCheapestCandidate

# Integration test class
mvn verify -DskipITs=false -Dit.test=LocalStackSmokeIT
```

### Test structure

```
src/test/java/org/mendrugo/attimo/
├── aws/
│   ├── AwsClientFactoryTest.java      # Unit: client creation, credential failure
│   ├── AwsClientFactoryIT.java        # Integration: STS + EC2 via LocalStack
│   ├── BaseAmiResolverTest.java       # Unit: AMI resolution with mocked EC2
│   ├── LocalStackSmokeIT.java         # Integration: basic EC2 operations
│   ├── ResourceCleanerTest.java       # Unit: cleanup flow, partial failure
│   ├── SpotAdvisorTest.java           # Unit: pricing, selection, proximity
│   └── SpotManagerTest.java           # Unit: launch, tagging, security groups
├── config/
│   ├── AttimoConfigTest.java          # Unit: config load/save, defaults
│   └── RegionGroupTest.java           # Unit: region lookup, grouping
├── isa/
│   └── IsaMappingTest.java            # Unit: ISA feature resolution
└── ssh/
    ├── SshKeyManagerTest.java         # Unit: key existence checks
    └── SshSessionTest.java            # Unit: SSH command construction
```

**Naming convention:**
- `*Test.java` — unit tests, run with `mvn test`
- `*IT.java` — integration tests, run with `mvn verify -DskipITs=false`

## Code style

Source is formatted by [jfmt](https://github.com/Netflix/jfmt), Netflix's Java source formatter.
A pre-commit hook enforces formatting — commits with unformatted Java are rejected.
The hook is installed automatically when you run `mvn compile` (or any Maven goal).

### Formatting

```bash
# Format specific files
./jfmtw src/main/java/org/mendrugo/attimo/Attimo.java

# Format all source
./jfmtw --source-path src/main/java $(find src/main/java -name '*.java')
./jfmtw --source-path src/test/java $(find src/test/java -name '*.java')

# Check without modifying
./jfmtw --check src/main/java/org/mendrugo/attimo/Attimo.java
```

The `jfmtw` wrapper downloads the jfmt JAR on first use (cached in `~/.cache/jfmt/`).

### Key rules

| Rule | Enforced by |
|------|-------------|
| **K&R braces** | jfmt |
| **4-space block indent, 8-space continuation** | jfmt |
| **Braces always required** (if/for/while) | jfmt |
| **Imports sorted, unused removed** | jfmt |
| **Explicit types (no `var`)** | `String name = ...` not `var name = ...` |
| **No wildcard imports** | jfmt expands wildcards |

### Example

```java
public record SpotRecommendation(
        String instanceType,
        String region,
        String availabilityZone,
        double pricePerHour,
        String rationale) {}

public SpotRecommendation recommend(IsaFeature feature, String preferredRegion) {
    var regionGroup = RegionGroup.forRegion(preferredRegion);

    if (regionGroup.regions().isEmpty()) {
        return null;
    }

    for (String region : regionGroup.regions()) {
        // ...
    }
}

@CommandDefinition(
        name = "request",
        description = "Request a spot instance",
        generateHelp = true)
public class RequestCommand extends BaseCommand {
    // ...
}
```

## Project layout

```
attimo/
├── pom.xml                              # Maven build config
├── jfmtw                                # jfmt wrapper (downloads + runs jfmt)
├── .jfmt/jfmt-wrapper.properties        # jfmt version metadata
├── hooks/pre-commit                     # Git pre-commit hook (format check)
├── SPEC.md                              # Full specification
├── PLAN.md                              # Implementation plan
├── README.md                            # User documentation
├── CONTRIBUTING.md                      # This file
├── .github/workflows/ci.yml            # GitHub Actions CI
├── src/
│   ├── main/
│   │   ├── java/org/mendrugo/attimo/   # Application source
│   │   ├── resources/
│   │   │   ├── application.properties  # Quarkus config
│   │   │   └── isa-mappings/           # Static ISA → instance family YAML
│   │   │       ├── x86_64.yaml
│   │   │       └── aarch64.yaml
│   │   └── resources-filtered/
│   │       └── build.properties        # Version info (Maven-filtered)
│   └── test/
│       └── java/org/mendrugo/attimo/   # Tests
```

## CI

GitHub Actions runs on every push to `main` and on pull requests:

1. **Unit tests** — `mvn test` with OpenJDK 25 (Temurin)
2. **Integration tests** — `mvn verify -DskipITs=false` with Podman + LocalStack

CI configuration: `.github/workflows/ci.yml`

## Making changes

1. Read `SPEC.md` for the full specification and design decisions
2. Read `PLAN.md` for the implementation plan and task breakdown
3. Write tests first (or alongside) — every AWS interaction must be testable without real AWS
4. Format with `./jfmtw` before committing (the pre-commit hook will verify)
5. Run `mvn test` before committing
6. Run `mvn verify -DskipITs=false` if you changed any AWS interaction code
7. Commit with a descriptive message referencing the task number if applicable

## Troubleshooting

### `mvn test` fails with "cannot find symbol"

Make sure you're using Java 25:

```bash
java -version
# Should show: openjdk version "25.x.x"
```

### Integration tests fail with "Could not find a valid Docker environment"

Testcontainers needs a container runtime. With Podman:

```bash
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
mvn verify -DskipITs=false
```

### Integration tests fail with "Permission denied" on Podman socket

```bash
# Check the socket exists
ls -la /run/user/$(id -u)/podman/podman.sock

# If missing, enable and start the socket
systemctl --user enable podman.socket
systemctl --user start podman.socket
```

### `ato request` fails with "AWS authentication failed"

Verify your credentials work:

```bash
aws sts get-caller-identity
```

If this fails, reconfigure with `aws configure` or check your environment variables.

### `ato request` fails with "No Fedora 44 Cloud AMI found"

The Fedora 44 Cloud AMI may not be available in all regions. Try a different region:

```bash
ato init  # re-run and choose a different region
```

Common regions with good AMI availability: `us-east-1`, `eu-west-1`, `ap-northeast-1`.
