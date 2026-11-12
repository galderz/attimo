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
import org.mendrugo.attimo.aws.AwsConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.isa.IsaMapping;
import org.mendrugo.attimo.config.ImageDef;
import org.mendrugo.attimo.ssh.BootJdkProvisioner;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.mendrugo.attimo.ssh.SshProvisioner;
import org.mendrugo.attimo.ssh.SshSession;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@CommandDefinition(
    name = "request"
    , description = "Request a spot instance with specific CPU ISA features"
    , generateHelp = true
)
public class AwsRequestCommand extends BaseCommand
{

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

    @Option(
        name = "template"
        , description = "Image template to use for provisioning (default: tpl-openjdk)"
        , defaultValue = "tpl-openjdk"
    )
    String template;

    @Override
    protected CommandResult doExecute() throws Exception
    {

        if (!AwsInitCommand.hasBeenInitialized())
        {
            System.err.println("Error: AWS has not been initialized. Run 'ato aws init' first.");
            return CommandResult.FAILURE;
        }

        final var resolved = ImageDef.resolve(template);
        if (resolved == null)
        {
            return CommandResult.FAILURE;
        }

        final var existingState = InstanceState.load(Aws.CLOUD);
        if (existingState.hasActiveInstance())
        {
            System.err.println("Error: an instance is already active (" + existingState.getInstanceId() + ").");
            System.err.println("Use 'ato aws connect' to reconnect or 'ato aws destroy' to tear it down first.");
            return CommandResult.FAILURE;
        }

        final var config = AwsConfig.load();
        final var preferredRegion = config.getPreferredRegion();
        if (preferredRegion.isBlank())
        {
            System.err.println("Error: no preferred region configured. Run 'ato aws init' first.");
            return CommandResult.FAILURE;
        }

        System.out.println("=== Requesting spot instance ===\n");
        System.out.println("[1/4] Resolving ISA feature: " + isaFeature);

        final var isaMapping = new IsaMapping();
        final var feature = isaMapping.resolve(isaFeature);
        if (feature == null)
        {
            System.err.println("Error: unknown ISA feature '" + isaFeature + "'.");
            System.err.println("Available features: " + String.join(", ", isaMapping.allFeatureNames()));
            return CommandResult.FAILURE;
        }

        System.out.println("  " + feature.description() + " (" + feature.architecture() + ")");
        System.out.println("  Candidate families: " + String.join(", ", feature.families()));

        final InstanceSize instanceSize;
        try
        {
            instanceSize = InstanceSize.fromLabel(size);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        System.out.println("\n[2/4] Querying spot prices across continents"
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
            return CommandResult.FAILURE;
        }

        System.out.println("  Best option: " + recommendations.getFirst().rationale());
        if (recommendations.size() > 1)
        {
            System.out.println("  (" + (recommendations.size() - 1) + " fallback options available)");
        }

        System.out.println("\n[3/4] Launching spot instance...");

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
            return CommandResult.FAILURE;
        }

        final var rec = launchResult.recommendation;
        System.out.println("  " + rec.instanceType() + " in "
            + rec.availabilityZone() + " @ $"
            + String.format("%.4f", rec.pricePerHour()) + "/hr");
        System.out.println("  Instance: " + launchResult.instanceId
            + "  IP: " + launchResult.publicIp);
        System.out.println("  AMI: " + launchResult.amiId
            + "  SG: " + launchResult.securityGroupId
            + "  Key: " + launchResult.keyPairName);

        try (launchResult)
        {
            return provisionAndConnect(launchResult, resolved);
        }
    }

    private CommandResult provisionAndConnect(
        final LaunchResult launchResult
        , final ImageDef.ResolvedTemplate resolved
    )
    {

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

        System.out.println("\n[4/4] Provisioning and connecting...");

        final var sshUser = BaseAmiResolver.SSH_USER;
        final var keyFile = Environment.sshKeyFile(Aws.CLOUD);
        final var sshSession = new SshSession(launchResult.publicIp, sshUser, keyFile);
        if (!sshSession.waitForSsh(300))
        {
            System.err.println("Error: SSH not reachable after 5 minutes.");
            System.err.println("Instance is running at " + launchResult.publicIp
                + ". Use 'ato aws connect' to retry.");
            return CommandResult.FAILURE;
        }

        final var provisioner = new SshProvisioner(launchResult.publicIp, sshUser, keyFile);
        final var allPackages = resolved.packages();

        final var isaMapping = new IsaMapping();
        final var resolvedFeature = isaMapping.resolve(isaFeature);
        final var templateArch = resolvedFeature != null
            && "aarch64".equals(resolvedFeature.architecture())
            ? "aarch64" : "x86_64";
        BootJdkProvisioner.provision(
            allPackages
            , provisioner
            , false
            , templateArch
        );

        final var exitCode = sshSession.connect();

        if (exitCode == 0)
        {
            promptKeepOrDestroy(launchResult.ec2, state);
        }

        return CommandResult.SUCCESS;
    }

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

            final String amiId;
            try (final var ssm = factory.ssm(region))
            {
                amiId = amiResolver.resolve(ssm, arch, region);
            }
            catch (final Exception e)
            {
                System.out.println("  ⚠ AMI resolution failed in " + region
                    + ". Trying next option...");
                continue;
            }

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
                final boolean isRetryableError = isRetryableLaunchError(e);

                cleanupFailedAttempt(ec2, sgId, keyPairName);
                ec2.close();

                if (!isRetryableError)
                {
                    System.err.println("Error launching instance in " + region
                        + ": " + e.getMessage());
                    return null;
                }

                final var reason = retryableReason(e);
                if (attempt < maxAttempts - 1)
                {
                    System.out.println("  ⚠ " + reason + " in " + region
                        + ". Cleaning up and trying next option...");
                }
                else
                {
                    System.out.println("  ⚠ " + reason + " in " + region + ".");
                }
            }
        }

        return null;
    }

    static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
        "InsufficientInstanceCapacity"
        , "SpotMaxPriceTooLow"
        , "MaxSpotInstanceCountExceeded"
        , "InvalidAMIID.NotFound"
    );

    static boolean isRetryableLaunchError(final Exception e)
    {

        if (e instanceof Ec2Exception ec2Ex
            && ec2Ex.awsErrorDetails() != null)
        {
            final var code = ec2Ex.awsErrorDetails().errorCode();
            if (code != null && RETRYABLE_ERROR_CODES.contains(code))
            {
                return true;
            }
        }

        final var msg = e.getMessage();
        if (msg == null)
        {
            return false;
        }

        return msg.contains("Insufficient capacity")
            || msg.contains("no Spot capacity")
            || msg.contains("Max spot instance count exceeded")
            || msg.contains("does not exist");
    }

    static String retryableReason(final Exception e)
    {
        if (e instanceof Ec2Exception ec2Ex
            && ec2Ex.awsErrorDetails() != null)
        {
            final var code = ec2Ex.awsErrorDetails().errorCode();
            if (code != null)
            {
                return switch (code)
                {
                    case "InsufficientInstanceCapacity" -> "No spot capacity";
                    case "SpotMaxPriceTooLow" -> "Spot price too low";
                    case "MaxSpotInstanceCountExceeded" -> "Spot instance limit reached";
                    case "InvalidAMIID.NotFound" -> "AMI not found (region-specific)";
                    default -> code;
                };
            }
        }

        final var msg = e.getMessage();
        if (msg != null)
        {
            if (msg.contains("Insufficient capacity") || msg.contains("no Spot capacity"))
            {
                return "No spot capacity";
            }
            if (msg.contains("Max spot instance count exceeded"))
            {
                return "Spot instance limit reached";
            }
            if (msg.contains("does not exist"))
            {
                return "AMI not found (region-specific)";
            }
        }

        return "Launch failed";
    }

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

        }
    }

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
