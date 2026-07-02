# Attimo

AWS spot instance manager for OpenJDK engineers. Request spot instances with specific CPU ISA features (AVX-512, SVE, AMX, etc.) for building and testing OpenJDK on architectures not available locally.

Built with [Quarkus](https://quarkus.io/), Java 25.

## Why

You're an OpenJDK engineer and you need to:

- Verify JIT compiler output on AVX-512 or AMX hardware
- Test AArch64 SVE/SVE2 codegen on Graviton3/4 instances
- Run jtreg tests on a platform you don't have on your desk
- Disassemble generated code with Capstone on a specific architecture

AWS spot instances give access to these platforms at minimal cost. Attimo handles the lifecycle — finding the cheapest spot instance across nearby regions, launching it, provisioning build tools, connecting via SSH, and tearing everything down when you're done.

## Requirements

- **Java 25** — to build and run attimo
- **Maven 3.9+** — to build
- **AWS account** — with credentials configured (see [Setup](#setup))
- **ssh** — the system SSH client (always available on Linux and macOS)

## Building

```bash
mvn package
```

The runnable JAR is at `target/quarkus-app/quarkus-run.jar`. You can create an alias for convenience:

```bash
alias ato='java -jar /path/to/attimo/target/quarkus-app/quarkus-run.jar'
```

## Setup

### 1. Configure AWS credentials

Attimo uses the standard AWS SDK credential chain. It **never stores your AWS credentials** — authentication is handled entirely by the AWS SDK.

Choose one of these methods:

**Option A: AWS CLI** (recommended)

```bash
# Install the AWS CLI
sudo dnf install awscli2        # Fedora
sudo apt install awscli          # Ubuntu/Debian
brew install awscli              # macOS
nix-env -iA nixpkgs.awscli2     # Nix

# Login to AWS
aws login
```

**Option B: Environment variables**

```bash
# Configure credentials
aws configure

# Enter: Access Key ID, Secret Access Key, default region, output format
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_DEFAULT_REGION=eu-west-1
```

**Option C: SSO** (for organizations)

```bash
aws sso login
```

If you don't have an access key, create one at https://console.aws.amazon.com/iam → Security credentials → Access keys.

### 2. Run `ato init`

```bash
ato init
```

This will:

1. **Validate your AWS credentials** — calls STS GetCallerIdentity to verify access
   - If credentials are missing, offers platform-specific install instructions and the option to enter an access key directly
2. **Set your preferred region** — the region closest to you (e.g., `eu-west-1`). Attimo also checks nearby regions for better spot prices
3. **Configure your SSH key** — generates a managed ed25519 key pair at `~/.config/attimo/ssh/` and asks for the path to your personal SSH public key

Configuration is saved to `~/.config/attimo/config.yaml` with owner-only permissions.

## Usage

### Request a spot instance

```bash
ato request --isa avx512
```

This will:

1. Resolve `avx512` to candidate instance families (c5, c6i, c7i, m5, m6i, m7i, etc.)
2. Query spot pricing across your region group (e.g., all European regions)
3. Select the best option balancing cost, instance size, and proximity
4. Resolve the Fedora 44 Cloud AMI for the target architecture
5. Create a security group (SSH-only) and import your SSH key
6. Launch a spot instance
7. Wait for the instance to be ready
8. Install OpenJDK build dependencies (gcc, make, autoconf, JDK 25, capstone, etc.)
9. Open an interactive SSH session

When you exit the SSH session, attimo asks whether to keep the instance running or destroy it.

### Available ISA features

| Feature | Architecture | Description | Instance families |
|---------|-------------|-------------|-------------------|
| `sse4_2` | x86_64 | SSE 4.2 | c5, m5, r5, c6i, m6i, r6i, c7i, m7i, c7a, m7a |
| `avx2` | x86_64 | Advanced Vector Extensions 2 (256-bit) | c5, m5, r5, c6i, m6i, r6i, c7i, m7i, c7a, m7a |
| `avx512` | x86_64 | AVX-512 Foundation | c5, m5, r5, c6i, m6i, r6i, c7i, m7i, c7a, m7a |
| `avx512_vnni` | x86_64 | AVX-512 VNNI (Intel Ice Lake+) | c6i, m6i, r6i, c7i, m7i |
| `amx` | x86_64 | Advanced Matrix Extensions (Sapphire Rapids) | c7i, m7i, r7i |
| `neon` | aarch64 | ARM NEON/ASIMD | c6g, m6g, r6g, c7g, m7g, r7g, c8g, m8g |
| `lse` | aarch64 | Large System Extensions (Graviton2+) | c6g, m6g, r6g, c7g, m7g, r7g, c8g, m8g |
| `sve` | aarch64 | Scalable Vector Extension (Graviton3) | c7g, m7g, r7g |
| `sve2` | aarch64 | Scalable Vector Extension v2 (Graviton4) | c8g, m8g, r8g |
| `bf16` | aarch64 | BFloat16 (Graviton3+) | c7g, m7g, r7g, c8g, m8g |
| `rng` | aarch64 | Hardware RNG (Graviton3+) | c7g, m7g, r7g, c8g, m8g |

ISA mappings can be overridden by placing YAML files in `~/.config/attimo/isa-mappings/`.

### Check instance status

```bash
ato status
```

Shows: instance type, region, IP address, uptime, running cost, and whether the instance is still alive.

### Reconnect to a running instance

```bash
ato connect
```

If your laptop restarts or your terminal closes, use this to SSH back into the running instance.

### Destroy the instance

```bash
ato destroy
```

Tears down everything:

1. Terminates the EC2 instance
2. Deletes the security group
3. Deletes the imported key pair
4. Clears the local state file

**No resources are left running.** Each step is logged, and cleanup continues even if individual steps fail.

## Example: Build OpenJDK and run jtreg

```bash
# 1. Request a spot instance with AVX-512 support
ato request --isa avx512

# 2. On the instance, clone and build OpenJDK
git clone https://github.com/openjdk/jdk.git
cd jdk
bash configure
make images

# 3. Run a basic jtreg test
export JT_HOME=/opt/jtreg
jtreg -jdk:build/linux-x86_64-server-release/images/jdk \
  test/hotspot/jtreg/compiler/c2/

# 4. Exit when done
exit

# 5. Destroy the instance (or keep it running for more testing)
# attimo will prompt you after exit
```

Note: jtreg is pre-installed at `/opt/jtreg` on provisioned instances. You can also install it manually:

```bash
# Primary source (Adoptium)
curl -fsSL "https://ci.adoptium.net/view/Dependencies/job/dependency_pipeline/lastSuccessfulBuild/artifact/jtreg/jtreg-7.5.1.tar.gz" -o /tmp/jtreg.tar.gz

# Alternative source (Shipilev) — useful if Adoptium builds are unavailable
# https://builds.shipilev.net/jtreg

sudo mkdir -p /opt/jtreg
sudo tar -xzf /tmp/jtreg.tar.gz -C /opt/jtreg --strip-components=1
```

## Spot Instance Selection

Attimo optimizes for cost while balancing reliability:

1. **Region group search** — queries spot pricing across all regions in your geographic group (e.g., all European regions), not just your preferred one
2. **Size bias** — slightly prefers larger instances because they tend to have lower interruption rates
3. **Proximity preference** — if a closer region is within ~15% of the cheapest price, it prefers the closer one (lower latency)
4. **Best option displayed** — always tells you what it picked and why

Example output:

```
[2/5] Querying spot prices across region group...
  Best option: c7i.xlarge in eu-west-2a @ $0.0670/hr
```

## Region Groups

Your preferred region (set during `ato init`) determines which regions are searched for spot pricing:

| Group | Regions |
|-------|---------|
| Europe | eu-west-1, eu-west-2, eu-west-3, eu-central-1, eu-central-2, eu-north-1, eu-south-1, eu-south-2 |
| US East | us-east-1, us-east-2 |
| US West | us-west-1, us-west-2 |
| Asia Pacific Southeast | ap-southeast-1, ap-southeast-2, ap-southeast-3, ap-southeast-4, ap-southeast-5 |
| Asia Pacific Northeast | ap-northeast-1, ap-northeast-2, ap-northeast-3 |
| Asia Pacific South | ap-south-1, ap-south-2 |
| South America | sa-east-1 |
| Middle East | me-south-1, me-central-1 |
| Africa | af-south-1 |
| Canada | ca-central-1, ca-west-1 |

## Pre-installed Software

Instances are provisioned with a Fedora 44 base image and the following packages:

**Build tools:** gcc, gcc-c++, make, autoconf

**Java:** java-25-openjdk-devel, java-25-openjdk-javadoc, java-25-openjdk-src

**OpenJDK build dependencies:** libcups-devel, libX11-devel, libXt-devel, libXrender-devel, libXrandr-devel, libXi-devel, libXtst-devel, alsa-lib-devel, fontconfig-devel, freetype-devel

**Disassembly:** capstone, capstone-devel, capstone-tool

## Configuration

### Files

| Path | Purpose |
|------|---------|
| `~/.config/attimo/config.yaml` | Preferred region, SSH key path |
| `~/.config/attimo/state.yaml` | Active instance tracking (auto-managed) |
| `~/.config/attimo/ssh/id_ed25519` | Managed SSH private key |
| `~/.config/attimo/ssh/id_ed25519.pub` | Managed SSH public key |
| `~/.config/attimo/isa-mappings/*.yaml` | User ISA mapping overrides |

### config.yaml

```yaml
preferred-region: eu-west-1
ssh-public-key: ~/.ssh/id_ed25519.pub
```

## CLI Reference

| Command | Description |
|---------|-------------|
| `ato init` | One-time setup: AWS credentials, region, SSH key |
| `ato request --isa <feature>` | Request a spot instance with specific CPU ISA |
| `ato status` | Show active instance status, uptime, cost |
| `ato connect` | SSH into the active instance |
| `ato destroy` | Tear down instance and all AWS resources |
| `ato --version` | Show version info |
| `ato --help` | Show help |

## Security

- **AWS credentials are never stored by attimo.** Authentication is delegated entirely to the AWS SDK default credential chain.
- **SSH keys:** attimo generates a dedicated passphraseless ed25519 key pair for instance access. Your personal SSH key (if configured) is also available.
- **Security groups** allow SSH (port 22) from any IP (`0.0.0.0/0`). For production use, consider restricting this to your IP.
- **All AWS resources** are tagged with `attimo:managed=true` and a session ID for identification and cleanup.
- **`ato destroy`** verifies no resources remain after cleanup.

## AWS Costs

Attimo uses spot instances which are significantly cheaper than on-demand:

- **Spot pricing** varies by instance type and region. Typical x86_64 instances (c7i.xlarge) cost $0.05-$0.10/hr as spot
- **AArch64 Graviton instances** are often even cheaper
- **`ato destroy`** cleans up everything — no ongoing cost after destruction
- **`ato status`** shows your current running cost

Spot instances can be interrupted by AWS with 2 minutes notice. If this happens, you'll need to run `ato request` again. Automatic spot interruption recovery is planned for a future version.

## Development

### Building from source

```bash
mvn package
```

### Running tests

```bash
# Unit tests (no AWS or container runtime needed)
mvn test

# Integration tests (requires Podman or Docker for LocalStack)
mvn verify -DskipITs=false
```

Integration tests use [LocalStack](https://localstack.cloud/) via [Testcontainers](https://testcontainers.com/) to test AWS interactions without touching real AWS. Install Podman to run them locally:

```bash
sudo dnf install podman          # Fedora
sudo apt install podman           # Ubuntu
brew install podman               # macOS
```

### Project structure

```
src/main/java/org/mendrugo/attimo/
├── Attimo.java                    # Entry point
├── BuildInfo.java                 # Version info
├── Environment.java               # XDG path resolution
├── aws/                           # AWS interaction
│   ├── AwsClientFactory.java      # SDK client creation
│   ├── BaseAmiResolver.java       # Fedora AMI lookup
│   ├── ResourceCleaner.java       # Teardown all resources
│   ├── SpotAdvisor.java           # Pricing + selection
│   └── SpotManager.java           # Instance lifecycle
├── command/                       # CLI commands (Aesh)
│   ├── BaseCommand.java
│   ├── ConnectCommand.java
│   ├── DestroyCommand.java
│   ├── InitCommand.java
│   ├── RequestCommand.java
│   └── StatusCommand.java
├── config/                        # Configuration
│   ├── AttimoConfig.java
│   ├── InstanceState.java
│   └── RegionGroup.java
├── isa/                           # CPU ISA feature mapping
│   ├── IsaFeature.java
│   └── IsaMapping.java
└── ssh/                           # SSH management
    ├── SshKeyManager.java
    ├── SshProvisioner.java
    └── SshSession.java
```

## Roadmap

- [ ] **AMI caching** — build a custom AMI on first request, reuse for instant subsequent launches
- [ ] **Spot interruption recovery** — automatically request a replacement instance when AWS reclaims the spot
- [ ] **Cost tracking** — `ato cost` command, running cost display in status and on destroy
- [ ] **TUI** — interactive terminal UI (like incus-spawn) for managing instances
- [ ] **Template system** — YAML-defined image templates with custom packages and tools
- [ ] **`ato build-ami`** — pre-build AMIs without launching a spot instance

## License

Apache-2.0
