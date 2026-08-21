# Attimo

Cloud instance manager for OpenJDK engineers. Request cloud instances for building and testing OpenJDK on architectures not available locally.

Built with [Quarkus](https://quarkus.io/), Java 25.

## Why

You're an OpenJDK engineer and you need to:

- Verify JIT compiler output on AVX-512 or AMX hardware
- Test AArch64 SVE/SVE2 codegen on Graviton3/4 instances
- Run jtreg tests on a platform you don't have on your desk
- Disassemble generated code with Capstone on a specific architecture

Cloud instances give access to these platforms at minimal cost. Attimo handles the lifecycle — launching, provisioning build tools, connecting via SSH, and tearing everything down when you're done.

## Supported Clouds

| Cloud | Command Prefix | Status |
|-------|---------------|--------|
| **AWS** | `ato aws ...` | ✅ Fully supported |
| **Blue Hat** | `ato bh ...` | ✅ Fully supported |

Each cloud gets its own subcommand group and configuration directory. Additional providers can be added alongside existing code.

## Requirements

- **Java 25** — to build and run attimo
- **Maven 3.9+** — to build
- **Cloud account** — with credentials configured (see setup sections below)
- **ssh** — the system SSH client (always available on Linux and macOS)

## Building

```bash
mvn package
```

The runnable JAR is at `target/quarkus-app/quarkus-run.jar`. You can create an alias for convenience:

```bash
alias ato='java -jar /path/to/attimo/target/quarkus-app/quarkus-run.jar'
```

---

## AWS

AWS spot instances provide access to diverse CPU architectures (x86_64 with AVX-512, AArch64 with SVE/SVE2, etc.) at minimal cost. Attimo finds the cheapest spot instance across nearby regions, launches it, provisions build tools, connects via SSH, and tears everything down when you're done.

### Setup

#### 1. Configure AWS credentials

Attimo uses the standard AWS SDK credential chain. It **never stores your AWS credentials** — authentication is handled entirely by the AWS SDK.

Choose one of these methods:

**Option A: AWS CLI** (recommended)

```bash
# Install the AWS CLI
sudo dnf install awscli2        # Fedora
sudo apt install awscli         # Ubuntu/Debian
brew install awscli             # macOS
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

#### 2. Run `ato aws init`

```bash
ato aws init
```

This will:

1. **Validate your AWS credentials** — calls STS GetCallerIdentity to verify access
   - If credentials are missing, offers platform-specific install instructions and the option to enter an access key directly
2. **Set your preferred region** — the region closest to you (e.g., `eu-west-1`). Attimo also checks nearby regions for better spot prices
3. **Configure your SSH key** — generates a managed ed25519 key pair at `~/.config/attimo/aws/ssh/` and asks for the path to your personal SSH public key

Configuration is saved to `~/.config/attimo/aws/config.yaml` with owner-only permissions.

### Request a spot instance

```bash
ato aws request --isa avx512 --size micro
```

This will:

1. Resolve `avx512` to candidate instance families (c5, c6i, c7i, m5, m6i, m7i, etc.)
2. Query spot pricing across your region group (e.g., all European regions)
3. Select the best option balancing cost, instance size, and proximity
4. Resolve the Amazon Linux 2023 AMI for the target architecture
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

### Instance sizes

Control the instance size with `--size <value>` (default: `medium`):

| Size | vCPUs | OpenJDK build time | AWS suffixes | Best for |
|------|-------|--------------------|--------------|----------|
| `micro` | 2–4 | ~30 min | `large`, `xlarge` | Smoke tests — cheapest way to verify the ISA works |
| `small` | 8–16 | ~10 min | `2xlarge`, `4xlarge` | Light development, quick iteration |
| `medium` | 16–32 | ~5 min | `4xlarge`, `8xlarge` | Day-to-day OpenJDK hacking (default) |
| `large` | 32–64 | ~2 min | `8xlarge`, `12xlarge`, `16xlarge` | Full build + jtreg runs, CI-like throughput |

Tip: start with `--size micro` to confirm the instance launches and the ISA feature is present before spending more on a larger machine.

### Check instance status

```bash
ato aws status
```

Shows: instance type, region, IP address, uptime, running cost, and whether the instance is still alive.

### Reconnect to a running instance

```bash
ato aws connect
```

If your laptop restarts or your terminal closes, use this to SSH back into the running instance.

### Destroy the instance

```bash
ato aws destroy
```

Tears down everything:

1. Terminates the EC2 instance
2. Deletes the security group
3. Deletes the imported key pair
4. Clears the local state file

**No resources are left running.** Each step is logged, and cleanup continues even if individual steps fail.

### Spot instance selection

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

### Region groups

Your preferred region (set during `ato aws init`) determines which regions are searched for spot pricing:

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

### Pre-installed software

Instances are provisioned with an Amazon Linux 2023 base image and the following packages:

**Build tools:** gcc, gcc-c++, make, autoconf

**Java:** Amazon Corretto 25

**OpenJDK build dependencies:** cups-devel, libX11-devel, libXt-devel, libXrender-devel, libXrandr-devel, libXi-devel, libXtst-devel, alsa-lib-devel, fontconfig-devel, freetype-devel

**Disassembly:** capstone, capstone-devel

### Configuration

| Path | Purpose |
|------|---------|
| `~/.config/attimo/aws/config.yaml` | Preferred region, SSH key path |
| `~/.config/attimo/aws/state.yaml` | Active instance tracking (auto-managed) |
| `~/.config/attimo/aws/ssh/id_ed25519` | Managed SSH private key |
| `~/.config/attimo/aws/ssh/id_ed25519.pub` | Managed SSH public key |
| `~/.config/attimo/isa-mappings/*.yaml` | User ISA mapping overrides |

```yaml
# ~/.config/attimo/aws/config.yaml
preferred-region: eu-west-1
ssh-public-key: ~/.ssh/id_ed25519.pub
```

### Security

- **AWS credentials are never stored by attimo.** Authentication is delegated entirely to the AWS SDK default credential chain.
- **SSH keys:** attimo generates a dedicated passphraseless ed25519 key pair for instance access. Your personal SSH key (if configured) is also available.
- **Security groups** allow SSH (port 22) from any IP (`0.0.0.0/0`). For production use, consider restricting this to your IP.
- **All AWS resources** are tagged with `attimo:managed=true` and a session ID for identification and cleanup.
- **`ato aws destroy`** verifies no resources remain after cleanup.

### Cloud costs

Attimo uses spot instances which are significantly cheaper than on-demand:

- **Spot pricing** varies by instance type and region. Typical x86_64 instances (c7i.xlarge) cost $0.05-$0.10/hr as spot
- **AArch64 Graviton instances** are often even cheaper
- **`ato aws destroy`** cleans up everything — no ongoing cost after destruction
- **`ato aws status`** shows your current running cost

Spot instances can be interrupted by AWS with 2 minutes notice. If this happens, you'll need to run `ato aws request` again. Automatic spot interruption recovery is planned for a future version.

### CLI reference

| Command | Description |
|---------|-------------|
| `ato aws init` | One-time setup: AWS credentials, region, SSH key |
| `ato aws request --isa <feature> [--size <size>]` | Request a spot instance with specific CPU ISA (size: micro/small/medium/large) |
| `ato aws status` | Show active instance status, uptime, cost |
| `ato aws connect` | SSH into the active instance |
| `ato aws destroy` | Tear down instance and all AWS resources |
| `ato aws --help` | Show AWS-specific commands |

### Example: Build OpenJDK and run jtreg

```bash
# 1. Request a spot instance with AVX-512 support
ato aws request --isa avx512

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

---

## Blue Hat

Blue Hat cloud provides VMs. Attimo communicates with the Blue Hat to request VMs, provision OpenJDK build tools, connect via SSH, and tear down VMs when done.

### Setup

Run `ato bh init`:

```bash
ato bh init
```

This will:

1. **Ask for the Blue Hat host name or IP address** — the address of your Blue Hat cloud
2. **Generate an SSH key pair** — creates a managed ed25519 key pair at `~/.config/attimo/bh/ssh/`

Configuration is saved to `~/.config/attimo/bh/config.yaml` with owner-only permissions.

### Request a VM

```bash
ato bh request --size medium
```

This will:

1. Send an HTTP POST to the Blue Hat cloud with CPU, memory, and OS requirements
2. Wait for the VM to be provisioned (returns an FQDN)
3. Wait for SSH to be reachable
4. Install OpenJDK build dependencies (gcc, make, autoconf, JDK 25, capstone, etc.)
5. Open an interactive SSH session as `root`

When you exit the SSH session, attimo asks whether to keep the VM running or destroy it.

The default OS is RedHat 10.2. The description field is auto-generated with the creation timestamp.

### Instance sizes

Control the VM size with `--size <value>` (default: `medium`):

| Size | CPUs | Memory (GB) | Best for |
|------|------|-------------|----------|
| `micro` | 1 | 2 | Smoke tests and verification |
| `small` | 8 | 16 | Full builds (~10 min) |
| `medium` | 16 | 32 | Iterative development (default) |
| `large` | 32 | 64 | Fast builds and jtreg runs (~2 min) |

Memory-to-CPU ratio is 2:1 across all sizes, providing enough RAM for OpenJDK builds and jtreg test runs.

### Check VM status

```bash
ato bh status
```

Queries the Blue Hat API and shows: FQDN, VM ID, state (running/stopped/deleted), uptime, and description.

### Reconnect to a running VM

```bash
ato bh connect
```

Verifies the VM is still running via the Blue Hat API, then opens an SSH session. If the VM is no longer running, you'll be told to destroy and re-request.

### Destroy the VM

```bash
ato bh destroy
```

Sends an HTTP DELETE to the Blue Hat cloud and clears the local state file.

### Pre-installed software

VMs are provisioned with the same OpenJDK development packages as AWS:

**Build tools:** gcc, gcc-c++, make, autoconf

**Java:** Amazon Corretto 25

**OpenJDK build dependencies:** cups-devel, libX11-devel, libXt-devel, libXrender-devel, libXrandr-devel, libXi-devel, libXtst-devel, alsa-lib-devel, fontconfig-devel, freetype-devel

**Disassembly:** capstone, capstone-devel

### Configuration

| Path | Purpose |
|------|---------|
| `~/.config/attimo/bh/config.yaml` | Blue Hat host name |
| `~/.config/attimo/bh/state.yaml` | Active VM tracking (auto-managed) |
| `~/.config/attimo/bh/ssh/id_ed25519` | Managed SSH private key |
| `~/.config/attimo/bh/ssh/id_ed25519.pub` | Managed SSH public key |

```yaml
# ~/.config/attimo/bh/config.yaml
host-name: bluehat-cloud.acme.com
```

### CLI reference

| Command | Description |
|---------|-------------|
| `ato bh init` | One-time setup: Blue Hat host, SSH key |
| `ato bh request [--size <size>]` | Request a VM (size: micro/small/medium/large) |
| `ato bh status` | Show active VM status: FQDN, state, uptime |
| `ato bh connect` | SSH into the active Blue Hat VM |
| `ato bh destroy` | Destroy the active Blue Hat VM |
| `ato bh --help` | Show Blue Hat-specific commands |

---

## Global commands

| Command | Description |
|---------|-------------|
| `ato --version` | Show version info |
| `ato --help` | Show help (lists available cloud subcommands) |

## Development

### Building from source

```bash
mvn package
```

### Running tests

```bash
# Unit tests (no cloud or container runtime needed)
mvn test

# Integration tests (requires Podman or Docker for LocalStack)
mvn verify -DskipITs=false
```

AWS integration tests use [LocalStack](https://localstack.cloud/) via [Testcontainers](https://testcontainers.com/) to test AWS interactions without touching real AWS. Blue Hat integration tests use a dummy in-process API server (no container runtime needed). Install Podman to run the full suite locally:

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
├── Environment.java               # XDG path resolution (cloud-aware)
├── aws/                           # AWS interaction
│   ├── AwsClientFactory.java      # SDK client creation
│   ├── BaseAmiResolver.java       # Amazon Linux 2023 AMI lookup
│   ├── InstanceSize.java          # Instance size tiers
│   ├── ResourceCleaner.java       # Teardown all resources
│   ├── SpotAdvisor.java           # Pricing + selection
│   ├── SpotManager.java           # Instance lifecycle
│   └── command/                   # AWS CLI commands
│       ├── AwsGroupCommand.java   # 'aws' subcommand group
│       ├── AwsInitCommand.java
│       ├── AwsRequestCommand.java
│       ├── AwsStatusCommand.java
│       ├── AwsConnectCommand.java
│       └── AwsDestroyCommand.java
├── bluehat/                       # Blue Hat interaction
│   ├── BlueHat.java               # Cloud constants
│   ├── BlueHatClient.java         # HTTP client for Blue Hat API
│   ├── BlueHatException.java      # Typed error wrapper
│   ├── BlueHatInstanceSize.java   # Size → CPU/memory mapping
│   └── command/                   # Blue Hat CLI commands
│       ├── BlueHatGroupCommand.java   # 'bh' subcommand group
│       ├── BlueHatInitCommand.java
│       ├── BlueHatRequestCommand.java
│       ├── BlueHatStatusCommand.java
│       ├── BlueHatConnectCommand.java
│       └── BlueHatDestroyCommand.java
├── command/                       # Shared CLI base
│   └── BaseCommand.java
├── config/                        # Configuration
│   ├── AttimoConfig.java
│   ├── InstanceState.java
│   └── RegionGroup.java
├── isa/                           # CPU ISA feature mapping (shared)
│   ├── IsaFeature.java
│   └── IsaMapping.java
└── ssh/                           # SSH management (shared)
    ├── OsPackages.java
    ├── SshKeyManager.java
    ├── SshProvisioner.java
    └── SshSession.java
```

### Adding a new cloud provider

The Blue Hat integration serves as a reference implementation for adding a new cloud provider. To add another (e.g., GCP):

1. Create a package `org.mendrugo.attimo.gcp` with cloud constants and client code
2. Create a `@GroupCommandDefinition` class at `gcp/command/GcpGroupCommand.java`
3. Implement cloud-specific commands (init, request, status, connect, destroy)
4. Register the group command in `Attimo.AttimoCommand.groupCommands`
5. Use `Environment.configDir("gcp")` for cloud-specific configuration paths
6. Reuse shared code: `BaseCommand`, `SshSession`, `SshKeyManager`, `SshProvisioner`, `OsPackages`, `AttimoConfig`, `InstanceState`

No new Maven modules are needed — cloud provider code lives alongside existing code.

## Roadmap

- [ ] **AMI caching** — build a custom AMI on first request, reuse for instant subsequent launches
- [ ] **Spot interruption recovery** — automatically request a replacement instance when AWS reclaims the spot
- [ ] **Cost tracking** — `ato aws cost` command, running cost display in status and on destroy
- [ ] **TUI** — interactive terminal UI (like incus-spawn) for managing instances
- [ ] **Template system** — YAML-defined image templates with custom packages and tools
- [ ] **`ato aws build-ami`** — pre-build AMIs without launching a spot instance
- [x] **Blue Hat cloud provider** — `ato bh init/request/status/connect/destroy`
- [ ] **Additional cloud providers** — GCP, Azure, etc.

## License

Apache-2.0
