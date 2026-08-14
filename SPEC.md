# Spec: Attimo — Cloud Spot Instance Manager for OpenJDK Engineers

## Objective

Attimo (`ato`) is a CLI+TUI tool for OpenJDK software engineers who need to validate, verify, and test OpenJDK behaviour on platforms with specific CPU ISA characteristics that are not available locally. Cloud spot instances provide access to diverse architectures (x86_64 with AVX-512, AArch64 with SVE/SVE2, etc.) at minimal cost.

The tool manages the full lifecycle of cloud spot instances: finding the cheapest option across nearby regions, launching, provisioning, connecting via SSH, handling spot interruptions transparently, and tearing down all resources on completion — leaving zero cost footprint by default.

The architecture supports multiple cloud providers (AWS, GCP, Azure, etc.) through a per-cloud subcommand structure. Each cloud provider's commands are grouped under `ato <cloud> ...` (e.g., `ato aws init`, `ato aws request`). Cloud-specific configuration is stored separately under `~/.config/attimo/<cloud>/`.

**Primary user:** An OpenJDK engineer sitting at their workstation who needs a remote machine with specific CPU features for a few hours.

**Success criteria:**
- Engineer can go from `ato aws request --isa avx512` to a working SSH session in under 3 minutes (with cached AMI)
- Spot interruptions are handled transparently — new instance launched, user notified and reconnected
- `ato aws destroy` leaves zero cloud resources running or costing money
- All unit tests run without any cloud interaction (no cost to run tests)
- CI runs on every PR and push to main
- Adding a new cloud provider requires no new Maven modules

## Tech Stack

- **Java 25** — language version
- **Quarkus 3.36.x** — application framework (CLI mode, no HTTP server)
- **Maven** — build system
- **Aesh 3.11** — CLI command framework (same as incus-spawn)
- **Tamboui 0.3.0** — TUI framework (same as incus-spawn)
- **AWS SDK for Java v2** — EC2, STS, Pricing APIs
- **Jackson + YAML** — configuration and template parsing
- **JUnit 5 + Mockito + AssertJ** — testing
- **LocalStack (Testcontainers)** — integration testing without real AWS

## Commands

```bash
# Build
mvn package

# Run unit tests (no cloud interaction needed)
mvn test

# Run integration tests (LocalStack via Testcontainers)
mvn verify -DskipITs=false

# Run the TUI
java -jar target/attimo-*.jar
# or after install:
ato

# Global commands
ato --version                      # Show version info
ato --help                         # List available cloud subcommands

# AWS CLI commands (all under 'ato aws')
ato aws --help                     # Show AWS-specific commands
ato aws init                       # One-time setup: AWS auth, region, SSH key
ato aws request --isa avx512       # Find best spot (default: medium size), launch, provision, SSH in
ato aws request --isa avx512 --size large  # Use a larger instance (~2 min build)
ato aws request --isa sve --template jdk-dev  # With custom template
ato aws status                     # Show running instance (region, IP, uptime, cost)
ato aws connect                    # SSH into existing running instance
ato aws destroy                    # Tear down instance + all resources
ato aws destroy --keep-ami         # Tear down but keep the AMI for reuse
ato aws build-ami --template jdk-dev   # Pre-build an AMI without launching a spot instance
ato aws cost                       # Show current/total cost information
```

## Project Structure

```
src/
├── main/
│   ├── java/org/mendrugo/attimo/
│   │   ├── Attimo.java                    # @QuarkusMain entry point
│   │   ├── BuildInfo.java                 # Version/git SHA info
│   │   ├── Environment.java               # XDG paths, cloud-aware (~/.config/attimo/{cloud}/)
│   │   ├── command/                        # Shared CLI base
│   │   │   └── BaseCommand.java           # Command base class
│   │   ├── config/                         # Configuration (cloud-aware)
│   │   │   ├── AttimoConfig.java          # Per-cloud config (~/.config/attimo/{cloud}/config.yaml)
│   │   │   ├── InstanceState.java         # Per-cloud state (~/.config/attimo/{cloud}/state.yaml)
│   │   │   ├── ImageDef.java              # Template image definitions
│   │   │   └── Continent.java             # EMEA/Americas/Asia-Pacific continent groupings
│   │   ├── aws/                            # AWS cloud provider
│   │   │   ├── AwsClientFactory.java      # SDK client creation + credential validation
│   │   │   ├── SpotAdvisor.java           # Spot pricing analysis + instance selection
│   │   │   ├── SpotManager.java           # Instance lifecycle (launch, monitor, terminate)
│   │   │   ├── AmiManager.java            # AMI build, cache, cleanup
│   │   │   ├── ResourceCleaner.java       # Teardown of all AWS resources
│   │   │   ├── AwsException.java          # Typed AWS error wrapper
│   │   │   ├── InstanceSize.java          # AWS instance size tiers
│   │   │   ├── BaseAmiResolver.java       # Amazon Linux 2023 AMI via SSM
│   │   │   ├── SpotRecommendation.java    # Spot selection result
│   │   │   └── command/                   # AWS CLI commands (under 'ato aws')
│   │   │       ├── AwsGroupCommand.java   # 'aws' subcommand group
│   │   │       ├── AwsInitCommand.java    # ato aws init
│   │   │       ├── AwsRequestCommand.java # ato aws request
│   │   │       ├── AwsStatusCommand.java  # ato aws status
│   │   │       ├── AwsConnectCommand.java # ato aws connect
│   │   │       └── AwsDestroyCommand.java # ato aws destroy
│   │   ├── isa/                            # CPU ISA feature mapping (shared across clouds)
│   │   │   ├── IsaMapping.java            # Static YAML + dynamic hybrid lookup
│   │   │   └── IsaFeature.java            # ISA feature model
│   │   ├── ssh/                            # SSH management (shared across clouds)
│   │   │   ├── SshKeyManager.java         # Key pair management (cloud-aware paths)
│   │   │   ├── SshSession.java            # SSH connection + reconnection
│   │   │   ├── SshProvisioner.java        # Run provisioning commands over SSH
│   │   │   └── OsPackages.java            # Package lists for provisioning
│   │   ├── tool/                           # Tool/template system (planned)
│   │   │   ├── ToolDef.java               # Tool definition model
│   │   │   ├── ToolDefLoader.java         # YAML tool loader with resolution order
│   │   │   └── ToolSetup.java             # Tool installation interface
│   │   ├── cost/                           # Cost tracking (planned)
│   │   │   ├── CostTracker.java           # Ongoing cost calculation
│   │   │   └── SpotPriceHistory.java      # Historical spot price analysis
│   │   └── tui/                            # TUI components (planned)
│   │       ├── BackgroundTask.java         # Async task model
│   │       └── BackgroundTaskManager.java  # Task lifecycle
│   └── resources/
│       ├── application.properties          # Quarkus config
│       ├── isa-mappings/                   # Static ISA → instance family YAML
│       │   ├── x86_64.yaml
│       │   └── aarch64.yaml
│       ├── images/                         # Built-in image templates
│       │   └── jdk-dev.yaml
│       └── tools/                          # Built-in tool definitions
│           └── jtreg.yaml
├── test/
│   ├── java/org/mendrugo/attimo/
│   │   ├── aws/
│   │   │   ├── SpotAdvisorTest.java       # Pricing logic with mocked AWS responses
│   │   │   ├── SpotManagerTest.java        # Lifecycle with mocked EC2 client
│   │   │   ├── ResourceCleanerTest.java    # Teardown completeness verification
│   │   │   ├── InstanceSizeTest.java       # Instance size tier tests
│   │   │   ├── BaseAmiResolverTest.java    # AMI resolution via SSM
│   │   │   └── AwsClientFactoryTest.java   # Credential validation
│   │   ├── isa/
│   │   │   └── IsaMappingTest.java         # Static + dynamic ISA resolution
│   │   ├── config/
│   │   │   ├── AttimoConfigTest.java       # Config load/save/validation
│   │   │   └── ContinentTest.java          # Continent grouping + fallback logic
│   │   ├── ssh/
│   │   │   ├── SshKeyManagerTest.java      # Key generation, config management
│   │   │   ├── SshSessionTest.java         # SSH command construction
│   │   │   └── OsPackagesTest.java         # Package list tests
│   │   └── TestKeys.java                   # Ephemeral test key generation
│   └── resources/
│       └── isa-mappings/                   # Test ISA mapping fixtures
docs/
└── architecture.md                         # Design decisions and tradeoffs
```

## Code Style

Aeron-inspired style (Allman braces, `final` parameters/locals, 4-space indent) combined with
Elm-style comma-first formatting so that adding or removing an argument changes exactly one line
in the diff.

| Rule | Source |
|------|--------|
| Allman braces (opening `{` on new line) | Aeron |
| `final` on all parameters and locals | Aeron |
| 4-space indent, 4-space continuation | Aeron |
| Braces always required (if/for/while) | Aeron |
| No wildcard imports | Aeron |
| No space after type cast | Aeron |
| **Comma-first** on multi-line args/params/fields | Elm |
| **One change = one line diff** for additions/removals | Elm |

```java
// Records — comma-first for multi-field records
public record IsaFeature(
    final String name
    , final String architecture
    , final List<String> instanceFamilies
)
{}

public record SpotRecommendation(
    final String instanceType
    , final String region
    , final String availabilityZone
    , final double pricePerHour
    , final double interruptionRate
    , final String rationale
)
{}

// Commands extend BaseCommand (Aesh pattern)
// Cloud-specific commands live under aws/command/
@CommandDefinition(
    name = "request"
    , description = "Request a spot instance with specific CPU ISA features"
    , generateHelp = true
)
public class AwsRequestCommand extends BaseCommand
{
    @Option(
        name = "isa"
        , description = "CPU ISA feature (e.g. avx512, sve, aarch64)"
        , required = true
    )
    String isaFeature;

    @Option(
        name = "size"
        , description = "Instance size: micro, small, medium (default), large"
        , defaultValue = "medium"
    )
    String size;

    @Option(
        name = "template"
        , description = "Image template to use"
        , defaultValue = "jdk-dev"
    )
    String template;

    @Override
    protected CommandResult doExecute() throws Exception
    {
        // 1. Resolve ISA → candidate instance types
        // 2. Query spot pricing across region group
        // 3. Select best option (cost vs longevity)
        // 4. Build AMI if needed
        // 5. Launch spot instance
        // 6. Wait for ready, provision if needed
        // 7. SSH in (with reconnection on spot termination)
    }
}

// Config — Jackson YAML, Allman braces
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttimoConfig
{
    @JsonProperty("preferred-region")
    private String preferredRegion = "";

    @JsonProperty("ssh-public-key")
    private String sshPublicKey = "";

    public static AttimoConfig load()
    {
        /* ... */
    }

    public void save()
    {
        /* ... */
    }
}

// AWS client factory — Allman braces, final params
public class AwsClientFactory
{
    public Ec2Client ec2(final String region)
    {
        return Ec2Client.builder()
            .region(Region.of(region))
            .build();
    }

    public String validateCredentials()
    {
        try (final StsClient sts = StsClient.create())
        {
            final GetCallerIdentityResponse identity = sts.getCallerIdentity();
            System.out.println("  Authenticated as: " + identity.arn());
            return null;
        }
        catch (final SdkException e)
        {
            return "AWS authentication failed: " + e.getMessage();
        }
    }
}

// Enum — comma-first between enum constants
public enum Continent
{
    EMEA(
        List.of("eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1")
        , List.of("eu-west-1", "eu-central-1", "me-south-1")
        , "EMEA (Europe, Middle East & Africa)"
    )
    , AMERICAS(
        List.of("us-east-1", "us-east-2", "us-west-1", "us-west-2")
        , List.of("us-east-1", "us-west-2", "ca-central-1")
        , "Americas"
    )
    , ASIA_PACIFIC(
        List.of("ap-northeast-1", "ap-southeast-1", "ap-south-1")
        , List.of("ap-northeast-1", "ap-southeast-1", "ap-south-1")
        , "Asia-Pacific"
    );

    private final List<String> regions;
    private final List<String> representatives;
    private final String displayName;

    Continent(
        final List<String> regions
        , final List<String> representatives
        , final String displayName
    )
    {
        this.regions = regions;
        this.representatives = representatives;
        this.displayName = displayName;
    }
}

// Multi-argument methods — comma-first
public void launchSpotInstance(
    final String instanceType
    , final String region
    , final String amiId
    , final String securityGroupId
    , final String keyPairName
)
{
    final RunInstancesRequest request = RunInstancesRequest.builder()
        .imageId(amiId)
        .instanceType(InstanceType.fromValue(instanceType))
        .keyName(keyPairName)
        .securityGroupIds(securityGroupId)
        .instanceMarketOptions(
            InstanceMarketOptionsRequest.builder()
                .marketType(MarketType.SPOT)
                .spotOptions(
                    SpotMarketOptions.builder()
                        .spotInstanceType(SpotInstanceType.ONE_TIME)
                        .build()
                )
                .build()
        )
        .minCount(1)
        .maxCount(1)
        .tagSpecifications(
            TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                    Tag.builder().key("attimo:managed").value("true").build()
                    , Tag.builder().key("attimo:session-id").value(sessionId).build()
                    , Tag.builder().key("attimo:template").value(template).build()
                )
                .build()
        )
        .build();

    final RunInstancesResponse response = ec2.runInstances(request);
    final String instanceId = response.instances().getFirst().instanceId();
    System.out.println("  Launched " + instanceType + " in " + region + ": " + instanceId);
}

// Tests — Allman braces, Mockito
@ExtendWith(MockitoExtension.class)
class SpotAdvisorTest
{
    @Mock
    Ec2Client ec2;

    @Test
    void selectsCheapestInstanceAcrossRegions()
    {
        // ...
    }
}
```

## Configuration

### Per-Cloud Config (`~/.config/attimo/{cloud}/config.yaml`)

Each cloud provider stores its configuration in a separate subdirectory.
For AWS: `~/.config/attimo/aws/config.yaml`.

```yaml
# Set during 'ato aws init'
continent: EMEA
preferred-region: eu-west-1
ssh-public-key: ~/.ssh/id_ed25519.pub
```

AWS credentials are NOT stored by attimo — they are managed entirely by the AWS SDK default credential chain (`~/.aws/credentials`, env vars, SSO).

### SSH Key (`~/.config/attimo/{cloud}/ssh/`)

Each cloud provider has its own managed SSH key pair.
For AWS: `~/.config/attimo/aws/ssh/`.

```
~/.config/attimo/aws/ssh/
    id_ed25519          # Managed private key (mode 600, no passphrase)
    id_ed25519.pub      # Managed public key
```

Borrowed from incus-spawn's `SshKeyManager`. The managed key is injected into spot instances for guaranteed passwordless access. The user's personal SSH public key (from config) is also injected.

### ISA Mappings (`src/main/resources/isa-mappings/x86_64.yaml`)

```yaml
# Static mapping of CPU ISA features to AWS instance families
features:
  avx2:
    description: "Advanced Vector Extensions 2 (256-bit)"
    families: [c5, m5, r5, c6i, m6i, r6i, c7i, m7i, c7a, m7a]

  avx512:
    description: "AVX-512 Foundation + common subsets"
    families: [c5, m5, r5, c6i, m6i, r6i, c7i, m7i, c7a, m7a]

  avx512_vnni:
    description: "AVX-512 Vector Neural Network Instructions"
    families: [c6i, m6i, r6i, c7i, m7i]

  amx:
    description: "Advanced Matrix Extensions (Intel)"
    families: [c7i, m7i, r7i]
```

```yaml
# src/main/resources/isa-mappings/aarch64.yaml
features:
  neon:
    description: "ARM NEON/ASIMD"
    families: [c6g, m6g, r6g, c7g, m7g, r7g, c8g, m8g]

  lse:
    description: "Large System Extensions (atomics)"
    families: [c6g, m6g, r6g, c7g, m7g, r7g, c8g, m8g]

  sve:
    description: "Scalable Vector Extension (256-bit on Graviton3)"
    families: [c7g, m7g, r7g]

  sve2:
    description: "Scalable Vector Extension v2 (Graviton4)"
    families: [c8g, m8g, r8g]

  bf16:
    description: "BFloat16 instructions"
    families: [c7g, m7g, r7g, c8g, m8g]
```

User-overridable at `~/.config/attimo/isa-mappings/`. Resolution order: built-in → user overrides.

### Image Templates (`src/main/resources/images/jdk-dev.yaml`)

```yaml
name: jdk-dev
description: OpenJDK build + test environment
base-ami: al2023  # Amazon Linux 2023 (available in all AWS regions via SSM)
packages:
  - gcc
  - gcc-c++
  - make
  - autoconf
  - cups-devel
  - libX11-devel
  - libXt-devel
  - libXrender-devel
  - libXrandr-devel
  - libXi-devel
  - libXtst-devel
  - alsa-lib-devel
  - fontconfig-devel
  - freetype-devel
  - capstone
  - capstone-devel
boot-jdk:
  # Amazon Corretto 25 from yum.corretto.aws (not in default AL2023 repos)
  - rpm --import https://yum.corretto.aws/corretto.key
  - curl -Lo /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
  - dnf install -y java-25-amazon-corretto-devel
tools:
  - jtreg
```

User-defined templates at `~/.config/attimo/images/` and project-local at `.attimo/images/`.

### Tool Definitions (`src/main/resources/tools/jtreg.yaml`)

```yaml
name: jtreg
description: OpenJDK regression test harness
# Primary source: Adoptium CI
# Alternative source: https://builds.shipilev.net/jtreg
#   (useful if Adoptium builds are unavailable or a different version is needed)
run:
  - |
    JTREG_VERSION=7.5.1
    curl -fsSL "https://ci.adoptium.net/view/Dependencies/job/dependency_pipeline/lastSuccessfulBuild/artifact/jtreg/jtreg-${JTREG_VERSION}.tar.gz" \
      -o /tmp/jtreg.tar.gz
    mkdir -p /opt/jtreg
    tar -xzf /tmp/jtreg.tar.gz -C /opt/jtreg --strip-components=1
    rm /tmp/jtreg.tar.gz
    ln -sf /opt/jtreg/bin/jtreg /usr/local/bin/jtreg
verify: jtreg -version
```

Same YAML schema and resolution order as incus-spawn.

## Architecture

### AWS Interaction Layer

**AWS SDK for Java v2** is used exclusively (no CLI shelling out). Rationale:
- Typed responses with proper error handling
- Built-in retry logic with exponential backoff
- Testable with mocked clients (no process spawning)
- Credential chain handled transparently by the SDK

```java
// AwsClientFactory creates regional clients
public class AwsClientFactory {
    public Ec2Client ec2(String region) {
        return Ec2Client.builder()
                .region(Region.of(region))
                .build();
    }

    public String validateCredentials() {
        // STS GetCallerIdentity — returns null on success, error message on failure
    }
}
```

### Spot Instance Selection Strategy

`SpotAdvisor` implements the cost-optimization logic:

1. **Resolve ISA → instance families** via hybrid mapping (static YAML + `DescribeInstanceTypes` fallback)
2. **Expand families → specific instance types** (e.g., `c7g` → `c7g.medium`, `c7g.large`, `c7g.xlarge`, ...)
3. **Query spot pricing** across preferred region + adjacent regions in the same geographic group
4. **Score candidates** by:
   - Spot price per hour (lower is better)
   - Historical interruption frequency (lower is better)
   - Instance size bias: slightly prefer larger instances (they tend to have lower interruption rates)
   - Region proximity: prefer closer regions when prices are within ~15% of each other
5. **Select the best candidate** and explain the choice to the user

### Continent Groups

Three continent-level groupings cover all AWS regions. The user selects a continent
during `ato aws init`, then picks a specific region within it. SpotAdvisor queries
all regions in the home continent plus 3 representative regions per foreign continent,
with graduated pricing penalties:

| Tier | Regions | Penalty |
|------|---------|---------|
| 0 | User's exact preferred region | 0% |
| 1 | Other regions in user's continent | +10% |
| 2 | Cheaper foreign continent (3 reps) | +25% |
| 3 | More expensive foreign continent (3 reps) | +40% |

```java
public enum Continent {
    EMEA("eu-west-1", "eu-west-2", ..., "me-south-1", "me-central-1", "af-south-1"),
    AMERICAS("us-east-1", "us-east-2", ..., "ca-central-1", "ca-west-1", "sa-east-1"),
    ASIA_PACIFIC("ap-northeast-1", ..., "ap-south-1", "ap-south-2");

    // Each continent defines 3 high-volume representative regions
    // used when querying foreign continents as fallback
    public List<String> representatives() { /* ... */ }
    public static Continent forRegion(String region) { /* ... */ }
}
```

Foreign continent priority is determined dynamically by median spot price
(cheapest continent gets tier-2 penalty, more expensive gets tier-3).

On launch failure due to no spot capacity, the request command automatically
retries with the next-best candidate from the ranked list (up to 3 attempts),
cleaning up SG/key pair from failed attempts before moving on.
```

### Instance Sizing

The `--size` option controls the instance size tier, balancing cost vs. build speed:

| Size | AWS Sizes | vCPUs | Build Time Target | Use Case |
|------|-----------|-------|-------------------|----------|
| `micro` | large, xlarge | 2–4 | N/A | Cheapest; smoke tests, verification |
| `small` | 2xlarge, 4xlarge | 8–16 | ~10 min | Development, full builds |
| `medium` | 4xlarge, 8xlarge | 16–32 | ~5 min | Iterative development (default) |
| `large` | 8xlarge, 12xlarge, 16xlarge | 32–64 | ~2 min | Fast tier testing, jtreg runs |

The default size is `medium`. Within each tier, the SpotAdvisor searches for the cheapest spot price across the allowed AWS sizes and region group.

### Instance Lifecycle

```
ato aws request --isa avx512 --template jdk-dev --size medium
│
├─ [SpotAdvisor] Resolve ISA → instance types
├─ [SpotAdvisor] Query pricing across region group
├─ [SpotAdvisor] Select best: c7i.xlarge in eu-west-2 ($0.067/hr)
│
├─ [AmiManager] AMI for 'jdk-dev' on x86_64 in eu-west-2?
│  ├─ No → Build AMI:
│  │  ├─ Launch t3.medium on-demand from Amazon Linux 2023 base AMI
│  │  ├─ SSH in, install packages + tools
│  │  ├─ CreateImage → ami-abc123
│  │  ├─ Terminate build instance
│  │  └─ (AMI is region-specific; copy cross-region if needed)
│  └─ Yes → Use existing ami-abc123
│
├─ [SpotManager] Launch spot instance:
│  ├─ Create security group (SSH only, port 22)
│  ├─ Import SSH key pair
│  ├─ RequestSpotInstances with ami-abc123
│  ├─ Wait for running + status checks passed
│  └─ Record instance ID, region, public IP
│
├─ [SshSession] Connect:
│  ├─ SSH into instance
│  ├─ Monitor for spot termination notice (2-min warning)
│  └─ If terminated:
│     ├─ Notify user: "Spot instance reclaimed. Requesting replacement..."
│     ├─ [SpotManager] Request new instance (same AMI, same or fallback region)
│     ├─ Wait for ready
│     └─ Reconnect SSH
│
└─ User exits SSH (types 'exit'):
   └─ "Keep instance running? (y/N)"
      ├─ N → ato aws destroy (automatic)
      └─ Y → Instance stays up, reconnect with `ato aws connect`
```

### Resource Cleanup (`ato aws destroy`)

`ResourceCleaner` ensures zero cost residue:

1. **Terminate EC2 instance** (if running)
2. **Delete security group** (created for this instance)
3. **Delete key pair** (imported for this instance)
4. **Deregister AMI + delete EBS snapshot** (default, unless `--keep-ami`)
5. **Verify** no resources remain via `DescribeInstances`, `DescribeSecurityGroups`, `DescribeKeyPairs`
6. **Report total cost** for the session

Each step is logged clearly. If any step fails, the error is reported and remaining steps still execute (best-effort cleanup).

### Resource Tagging

All AWS resources created by attimo are tagged for identification and cleanup:

```
attimo:managed = true
attimo:session-id = <uuid>
attimo:template = jdk-dev
attimo:isa = avx512
attimo:created-by = ato
```

This enables `ResourceCleaner` to find orphaned resources (e.g., after a crash) and clean them up.

### AMI Management

- AMIs are named `attimo-<template>-<arch>-<timestamp>` (e.g., `attimo-jdk-dev-x86_64-20260605`)
- AMIs are region-specific; if the best spot price is in a different region from the AMI, the AMI is copied cross-region
- On `ato aws destroy`, user is prompted: "AMI 'attimo-jdk-dev-x86_64' exists. Keep for future use? (y/N)"
  - Default **No**: deregister AMI + delete snapshot (zero ongoing cost)
  - **Yes**: keep for reuse in future sessions
- `ato aws build-ami` pre-builds an AMI without launching a spot instance

### SSH Provisioning (AMI Build Time)

Provisioning runs over SSH during AMI build. The flow:

1. Wait for instance to be reachable via SSH
2. Run `dnf install -y <packages>` for template-declared packages
3. Execute tool definitions in order: packages → run → run_as_user → files → env → verify
4. Clean caches (`dnf clean all`, clear `/tmp`)
5. Snapshot → AMI

### Cost Tracking

`CostTracker` calculates ongoing and total costs:

- **Ongoing**: `(current_time - launch_time) × spot_price_per_hour`
- **Total session**: sum of all instance run times × their spot prices, plus any AMI build instance time
- Displayed in `ato aws status` and on `ato aws destroy`
- AMI storage cost noted if AMI is kept (~$0.05/GB/month)

### Spot Interruption Handling

The tool monitors for spot termination in two ways:

1. **Instance metadata endpoint**: Poll `http://169.254.169.254/latest/meta-data/spot/termination-time` from inside the instance (2-minute warning)
2. **EC2 API polling**: Periodic `DescribeInstances` to detect state changes (fallback)

On interruption:
1. User is notified: "⚠ Spot instance terminated by AWS. Requesting replacement..."
2. New spot request is made (same AMI, same or fallback region)
3. User is notified: "✓ New instance ready in eu-west-2 (c7i.xlarge). Reconnecting..."
4. SSH session is re-established

### State File (`~/.config/attimo/{cloud}/state.yaml`)

Each cloud provider tracks its active instance separately.
For AWS: `~/.config/attimo/aws/state.yaml`.

```yaml
active-instance:
  instance-id: i-0abc123def456
  region: eu-west-2
  availability-zone: eu-west-2a
  instance-type: c7i.xlarge
  public-ip: 3.10.45.67
  launched-at: 2026-06-05T14:30:00Z
  spot-price: 0.067
  template: jdk-dev
  isa-feature: avx512
  ami-id: ami-abc123
  security-group-id: sg-xyz789
  key-pair-name: attimo-20260605-143000
  session-id: 550e8400-e29b-41d4-a716-446655440000
```

This file is updated on launch, cleared on destroy. `ato aws status` and `ato aws connect` read it to find the active instance.

## TUI Design

The TUI (launched with bare `ato` command) provides an interactive view similar to incus-spawn, using Tamboui:

### Main View

```
┌─ Attimo ───────────────────────────────────────────────────────┐
│ Status: No active instance                                     │
│                                                                │
│ Quick Actions:                                                 │
│   [R] Request spot instance                                    │
│   [S] Show spot pricing for ISA                                │
│   [I] Init / reconfigure                                       │
│                                                                │
│ Templates:                                                     │
│   Name          Description                    AMI             │
│ > jdk-dev       OpenJDK build + test env       ami-abc123      │
│   minimal       Base AL2023, no tools          not built       │
│                                                                │
│ ISA Mappings:                                                  │
│   avx512, avx2, amx, sve, sve2, neon, lse, bf16               │
├────────────────────────────────────────────────────────────────┤
│ F1 Help  F5 Request  F8 Destroy  F10 Quit                     │
└────────────────────────────────────────────────────────────────┘
```

### Active Instance View

```
┌─ Attimo ───────────────────────────────────────────────────────┐
│ Instance: c7i.xlarge in eu-west-2a                             │
│ Status:   ● Running    IP: 3.10.45.67                          │
│ Uptime:   1h 23m       Cost: $0.093 (@ $0.067/hr)             │
│ ISA:      avx512        Template: jdk-dev                      │
│ AMI:      ami-abc123                                           │
│                                                                │
│ Quick Actions:                                                 │
│   [C] Connect (SSH)                                            │
│   [D] Destroy instance                                         │
│   [S] Spot pricing refresh                                     │
│                                                                │
│ Cost History:                                                  │
│   14:30 - 15:53   c7i.xlarge  eu-west-2a  $0.093              │
│                                                                │
├────────────────────────────────────────────────────────────────┤
│ F1 Help  F2 Connect  F8 Destroy  F10 Quit                     │
└────────────────────────────────────────────────────────────────┘
```

## Testing Strategy

### Principle: Zero AWS Cost in Tests

All tests must run without touching real AWS. This is non-negotiable because AWS API calls cost money and introduce flakiness.

### Unit Tests (`mvn test` — no AWS, no containers)

Use **Mockito** to mock AWS SDK v2 clients. The SDK v2 is designed for testability — all clients are interfaces.

```java
@ExtendWith(MockitoExtension.class)
class SpotAdvisorTest {
    @Mock Ec2Client ec2;

    @Test
    void selectsCheapestInstanceAcrossRegions() {
        // Mock DescribeSpotPriceHistory responses for multiple regions
        // Verify the advisor picks the cheapest valid option
    }

    @Test
    void prefersCloserRegionWhenPricesAreSimilar() {
        // Mock two regions with <15% price difference
        // Verify closer region is preferred
    }

    @Test
    void fallsBackToAdjacentRegionWhenPreferredHasNoCapacity() {
        // Mock preferred region returning no spot capacity
        // Verify fallback to adjacent region
    }
}

class ResourceCleanerTest {
    @Test
    void cleansAllResources() {
        // Mock all AWS clients
        // Run cleanup
        // Verify terminate, delete SG, delete key pair, deregister AMI, delete snapshot
    }

    @Test
    void continuesCleanupOnPartialFailure() {
        // Mock one step failing
        // Verify remaining steps still execute
    }
}
```

Key unit test areas:
- `SpotAdvisorTest` — pricing logic, region fallback, instance size bias, ISA resolution
- `SpotManagerTest` — launch flow, termination detection, replacement logic
- `AmiManagerTest` — build, cache lookup, cross-region copy, cleanup
- `ResourceCleanerTest` — complete teardown, partial failure resilience, orphan detection
- `IsaMappingTest` — static YAML parsing, dynamic fallback, user overrides
- `CostTrackerTest` — cost calculation, multi-instance session totals
- `ContinentTest` — continent grouping, representatives, region lookup, fallback logic
- `AttimoConfigTest` — load, save, validation, defaults
- `ImageDefTest` — template parsing, tool reference resolution
- `ToolDefTest` — tool YAML parsing, execution order
- `SshKeyManagerTest` — key generation, config file management (borrowed from incus-spawn)

### Integration Tests (`mvn verify -DskipITs=false` — LocalStack via Testcontainers)

Use **LocalStack** (via Testcontainers) to run a local AWS-compatible API:

```java
@Testcontainers
class SpotManagerIT {
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.EC2);

    @Test
    void fullLifecycle() {
        // Create EC2 client pointing at LocalStack
        // Launch instance, verify running, terminate, verify cleaned up
    }
}
```

LocalStack supports: EC2 (instances, security groups, key pairs, AMIs), STS (identity), and Pricing.

### GitHub CI

```yaml
# .github/workflows/ci.yml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - run: mvn test

  integration-tests:
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - run: mvn verify -DskipITs=false
```

## Boundaries

### Always do:
- Run `mvn test` before commits
- Tag all AWS resources with `attimo:managed=true` and session ID
- Clean up all resources on destroy (terminate, delete SG, delete key pair, deregister AMI)
- Log every AWS API call at DEBUG level for troubleshooting
- Show clear error messages with actionable instructions
- Validate AWS credentials before attempting any operation
- Follow incus-spawn code conventions (Aesh commands, Jackson YAML config, record types)

### Ask first:
- Adding new dependencies beyond AWS SDK v2
- Changing the ISA mapping schema
- Adding new AWS services beyond EC2/STS/Pricing
- Changing the config file format

### Never do:
- Store AWS credentials in attimo's config files
- Leave AWS resources running after `ato aws destroy`
- Run tests that make real AWS API calls
- Commit AWS account IDs, keys, or secrets
- Remove failing tests without understanding why they fail

## Success Criteria

1. **`ato aws init`** validates AWS credentials and guides setup for all platforms (Fedora, Ubuntu, macOS, NixOS)
2. **`ato aws request --isa avx512`** launches a spot instance and drops the user into an SSH session within 3 minutes (with cached AMI)
3. **`ato aws request --isa sve`** finds AArch64 Graviton3 instances and launches correctly
4. **Spot interruption**: tool detects termination, launches replacement, reconnects — all automatic
5. **`ato aws destroy`** removes all AWS resources; `ato destroy --keep-ami` preserves only the AMI
6. **`ato aws status`** shows instance details, uptime, and cost
7. **`ato aws connect`** reconnects to a running instance after local restart
8. **Region fallback**: when preferred region has no capacity, adjacent regions are checked
9. **Cost display**: ongoing cost shown in TUI and on destroy, total session cost on final teardown
10. **`mvn test`** passes with zero AWS interaction — all AWS calls are mocked
11. **`mvn verify -DskipITs=false`** passes using LocalStack
12. **GitHub CI** runs on every PR and push to main
13. **ISA mappings** are overridable by users at `~/.config/attimo/isa-mappings/`
14. **Templates** support incus-spawn-style YAML with packages + tools

## Open Questions

1. ~~**Base AMI resolution**~~: **Resolved** — Uses Amazon Linux 2023 via SSM Parameter Store lookup (`/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-<arch>`). Works in every AWS region including opt-in regions. SSH user is `ec2-user`. Boot JDK is Amazon Corretto 25 (from `yum.corretto.aws` repo). Capstone is available in AL2023 repos.
2. ~~**SSH client**~~: **Resolved** — Use the system `ssh` command for interactive sessions (launched via `ProcessBuilder`). This gives users their familiar terminal, respects their `.ssh/config`, and avoids bundling an SSH library. A background thread polls the EC2 API to detect spot termination; on interruption the tool kills the `ssh` subprocess, launches a replacement instance, and starts a new `ssh` process. **Future alternative**: a Java SSH library (Apache MINA SSHD) could replace the system `ssh` for tighter lifecycle control, multiplexed monitoring, and environments where `ssh` is not installed.
3. ~~**Spot request method**~~: **Resolved** — Use the modern `RunInstances` with `InstanceMarketOptions` (single API call). The legacy `RequestSpotInstances` API is not needed.
