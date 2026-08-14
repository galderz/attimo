package org.mendrugo.attimo.aws.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.BaseAmiResolver;
import org.mendrugo.attimo.aws.InstanceSize;
import org.mendrugo.attimo.aws.ResourceCleaner;
import org.mendrugo.attimo.aws.SpotAdvisor;
import org.mendrugo.attimo.aws.SpotManager;
import org.mendrugo.attimo.aws.SpotRecommendation;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.isa.IsaMapping;
import org.mendrugo.attimo.ssh.OsPackages;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.mendrugo.attimo.ssh.SshProvisioner;
import org.mendrugo.attimo.ssh.SshSession;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.time.Instant;
import java.util.List;

@CommandDefinition(
    name = "request"
    , description = "Request a spot instance with specific CPU ISA features"
    , generateHelp = true
)
public class AwsRequestCommand extends BaseCommand
{
    /** Maximum number of candidates to try before giving up. */
    private static final int MAX_LAUNCH_ATTEMPTS = 3;

    @Option(
        name = "isa"
        , description = "CPU ISA feature (e.g. avx512, sve, aarch64)"
        , required = true
    )
    String isaFeature;

    @Option(
        name = "size"
        , description = "Instance size: micro (2-4 vCPUs), small (~10 min build)"
            + ", medium (~5 min build, default), large (~2 min build)"
        , defaultValue = "medium"
    )
    String size;

    @Override
    protected CommandResult doExecute() throws Exception
    {
        // Validate init
        if (!AwsInitCommand.hasBeenInitialized())
        {
            System.err.println("Error: AWS has not been initialized. Run 'ato aws init' first.");
            return CommandResult.valueOf(1);
        }

        // Check for existing active instance
        final var existingState = InstanceState.load(Aws.CLOUD);
        if (existingState.hasActiveInstance())
        {
            System.err.println("Error: an instance is already active (" + existingState.getInstanceId() + ").");
            System.err.println("Use 'ato aws connect' to reconnect or 'ato aws destroy' to tear it down first.");
            return CommandResult.valueOf(1);
        }

        final var config = AttimoConfig.load(Aws.CLOUD);
        final var preferredRegion = config.getPreferredRegion();
        if (preferredRegion.isBlank())
        {
            System.err.println("Error: no preferred region configured. Run 'ato aws init' first.");
            return CommandResult.valueOf(1);
        }

        // 1. Resolve ISA → instance types
        System.out.println("=== Requesting spot instance ===\n");
        System.out.println("[1/5] Resolving ISA feature: " + isaFeature);

        final var isaMapping = new IsaMapping();
        final var feature = isaMapping.resolve(isaFeature);
        if (feature == null)
        {
            System.err.println("Error: unknown ISA feature '" + isaFeature + "'.");
            System.err.println("Available features: " + String.join(", ", isaMapping.allFeatureNames()));
            return CommandResult.valueOf(1);
        }

        System.out.println("  " + feature.description() + " (" + feature.architecture() + ")");
        System.out.println("  Candidate families: " + String.join(", ", feature.families()));

        // Parse instance size
        final InstanceSize instanceSize;
        try
        {
            instanceSize = InstanceSize.fromLabel(size);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.valueOf(1);
        }

        // 2. Find spot options across continents
        System.out.println("\n[2/5] Querying spot prices across continents"
            + " (size: " + instanceSize.label() + ")...");

        final var factory = new AwsClientFactory();
        final var advisor = new SpotAdvisor(region -> factory.ec2(region));
        final var recommendations = advisor.recommend(
            feature
            , preferredRegion
            , instanceSize
        );

        if (recommendations.isEmpty())
        {
            System.err.println("Error: no spot instances available for " + isaFeature
                + " across all continents.");
            return CommandResult.valueOf(1);
        }

        System.out.println("  Best option: " + recommendations.getFirst().rationale());
        if (recommendations.size() > 1)
        {
            System.out.println("  (" + (recommendations.size() - 1) + " fallback options available)");
        }

        // 3. Resolve base AMI + launch with retry on capacity failure
        final var arch = "aarch64".equals(feature.architecture()) ? "arm64" : "x86_64";
        final var amiResolver = new BaseAmiResolver();

        final var launchResult = launchWithRetry(
            recommendations
            , factory
            , amiResolver
            , arch
        );

        if (launchResult == null)
        {
            System.err.println("Error: failed to launch spot instance after "
                + Math.min(MAX_LAUNCH_ATTEMPTS, recommendations.size()) + " attempts.");
            System.err.println("All candidate regions had no spot capacity. Try again later"
                + " or use a different --size.");
            return CommandResult.valueOf(1);
        }

        try (launchResult)
        {
            return provisionAndConnect(launchResult);
        }
    }

    private CommandResult provisionAndConnect(final LaunchResult launchResult)
    {
        // Save state for reconnection
        final var state = new InstanceState();
        state.setInstanceId(launchResult.instanceId);
        state.setRegion(launchResult.recommendation.region());
        state.setAvailabilityZone(launchResult.recommendation.availabilityZone());
        state.setInstanceType(launchResult.recommendation.instanceType());
        state.setPublicIp(launchResult.publicIp);
        state.setLaunchedAt(Instant.now().toString());
        state.setSpotPrice(launchResult.recommendation.pricePerHour());
        state.setIsaFeature(isaFeature);
        state.setAmiId(launchResult.amiId);
        state.setSecurityGroupId(launchResult.securityGroupId);
        state.setKeyPairName(launchResult.keyPairName);
        state.setSessionId(launchResult.sessionId);
        state.save(Aws.CLOUD);

        // 5. Provision + SSH
        System.out.println("\n[5/5] Provisioning and connecting...");

        final var sshUser = BaseAmiResolver.SSH_USER;
        final var keyFile = Environment.sshKeyFile(Aws.CLOUD);
        final var sshSession = new SshSession(launchResult.publicIp, sshUser, keyFile);
        if (!sshSession.waitForSsh(300))
        {
            System.err.println("Error: SSH not reachable after 5 minutes.");
            System.err.println("Instance is running at " + launchResult.publicIp
                + ". Use 'ato aws connect' to retry.");
            return CommandResult.valueOf(1);
        }

        // Provision packages
        final var provisioner = new SshProvisioner(launchResult.publicIp, sshUser, keyFile);

        // Install Corretto 25 (not in default AL2023 repos)
        System.out.println("  Installing Amazon Corretto 25...");
        for (final var cmd : OsPackages.CORRETTO_25_INSTALL_COMMANDS)
        {
            final var rc = provisioner.run(cmd);
            if (rc != 0)
            {
                System.err.println("  Warning: Corretto 25 install failed (exit " + rc + ").");
                break;
            }
        }

        provisioner.installPackages(OsPackages.JDK_DEV_PACKAGES);

        // Connect
        final var exitCode = sshSession.connect();

        // Post-SSH: prompt to keep or destroy
        if (exitCode == 0)
        {
            promptKeepOrDestroy(launchResult.ec2, state);
        }

        return CommandResult.SUCCESS;
    }

    /**
     * Try launching a spot instance, retrying with the next-best candidate
     * on capacity failure. Cleans up resources (SG, key pair) from failed
     * attempts before moving to the next.
     */
    private LaunchResult launchWithRetry(
        final List<SpotRecommendation> recommendations
        , final AwsClientFactory factory
        , final BaseAmiResolver amiResolver
        , final String arch
    )
    {
        final int maxAttempts = Math.min(MAX_LAUNCH_ATTEMPTS, recommendations.size());

        for (int attempt = 0; attempt < maxAttempts; attempt++)
        {
            final var recommendation = recommendations.get(attempt);
            final var region = recommendation.region();

            if (attempt > 0)
            {
                System.out.println("\n  Trying next option: " + recommendation.rationale());
            }

            // 3. Resolve AMI in this region
            System.out.println("\n[3/5] Resolving base AMI in " + region + "...");
            final String amiId;
            try (final var ssm = factory.ssm(region))
            {
                amiId = amiResolver.resolve(ssm, arch);
            }
            catch (final Exception e)
            {
                System.err.println("  Warning: AMI resolution failed in " + region + ": " + e.getMessage());
                continue;
            }

            // 4. Launch spot instance
            System.out.println("\n[4/5] Launching spot instance in " + region + "...");
            final Ec2Client ec2 = factory.ec2(region);
            final var spotManager = new SpotManager(ec2);

            String sgId = null;
            String keyPairName = null;
            try
            {
                sgId = spotManager.createSecurityGroup();
                final var pubKey = SshKeyManager.publicKeyContent(Aws.CLOUD);
                keyPairName = spotManager.importKeyPair(pubKey);
                final var instanceId = spotManager.launchSpotInstance(
                    amiId
                    , recommendation.instanceType()
                    , sgId
                    , keyPairName
                );

                // Wait for running
                final var publicIp = spotManager.waitForRunning(instanceId, 300);

                return new LaunchResult(
                    instanceId
                    , publicIp
                    , amiId
                    , sgId
                    , keyPairName
                    , spotManager.sessionId()
                    , recommendation
                    , ec2
                );
            }
            catch (final Exception e)
            {
                final var msg = e.getMessage();
                final boolean isCapacityError = msg != null
                    && (msg.contains("no Spot capacity")
                    || msg.contains("InsufficientInstanceCapacity")
                    || msg.contains("SpotMaxPriceTooLow"));

                if (isCapacityError && attempt < maxAttempts - 1)
                {
                    System.out.println("  ⚠ No spot capacity in " + region + ". Cleaning up and trying next option...");
                    cleanupFailedAttempt(ec2, sgId, keyPairName);
                    ec2.close();
                }
                else
                {
                    System.err.println("Error launching instance in " + region + ": " + msg);
                    cleanupFailedAttempt(ec2, sgId, keyPairName);
                    ec2.close();

                    if (!isCapacityError)
                    {
                        // Non-capacity error — don't retry
                        return null;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Clean up SG and key pair from a failed launch attempt.
     */
    private void cleanupFailedAttempt(
        final Ec2Client ec2
        , final String sgId
        , final String keyPairName
    )
    {
        try
        {
            if (keyPairName != null)
            {
                ec2.deleteKeyPair(b -> b.keyName(keyPairName));
            }
        }
        catch (final Exception ignored)
        {
            // Best-effort cleanup
        }

        try
        {
            if (sgId != null)
            {
                ec2.deleteSecurityGroup(b -> b.groupId(sgId));
            }
        }
        catch (final Exception ignored)
        {
            // Best-effort cleanup
        }
    }

    /**
     * Prompt the user to keep or destroy the instance.
     * The caller is responsible for closing the Ec2Client.
     */
    private void promptKeepOrDestroy(
        final Ec2Client ec2
        , final InstanceState state
    )
    {
        final var console = System.console();
        if (console == null)
        {
            System.out.println("\nInstance is still running. Use 'ato aws destroy' to tear it down.");
            return;
        }

        System.out.print("\nKeep instance running? (y/N): ");
        final var answer = console.readLine().strip();

        if (answer.equalsIgnoreCase("y"))
        {
            System.out.println("Instance kept running. Reconnect with 'ato aws connect'.");
        }
        else
        {
            System.out.println("Destroying instance...");
            final var cleaner = new ResourceCleaner(ec2);
            final var errors = cleaner.cleanAll(state, Aws.CLOUD);

            if (errors.isEmpty())
            {
                System.out.println("Instance destroyed. Zero cost footprint.");
            }
            else
            {
                System.err.println("Cleanup completed with errors:");
                for (final var error : errors)
                {
                    System.err.println("  - " + error);
                }
                System.err.println("Run 'ato aws destroy' to retry.");
            }
        }
    }

    record LaunchResult(
        String instanceId
        , String publicIp
        , String amiId
        , String securityGroupId
        , String keyPairName
        , String sessionId
        , SpotRecommendation recommendation
        , Ec2Client ec2
    ) implements AutoCloseable
    {
        @Override
        public void close()
        {
            ec2.close();
        }
    }
}
