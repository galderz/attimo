package org.mendrugo.attimo.command;

import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.BaseAmiResolver;
import org.mendrugo.attimo.aws.SpotAdvisor;
import org.mendrugo.attimo.aws.SpotManager;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.isa.IsaMapping;
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
public class RequestCommand extends BaseCommand
{
    @Option(
        name = "isa"
        , description = "CPU ISA feature (e.g. avx512, sve, aarch64)"
        , required = true
    )
    String isaFeature;

    // Packages to install on the instance (phase 1: hardcoded jdk-dev template)
    private static final List<String> JDK_DEV_PACKAGES = List.of(
        "gcc"
        , "gcc-c++"
        , "make"
        , "autoconf"
        , "java-25-openjdk-devel"
        , "java-25-openjdk-javadoc"
        , "java-25-openjdk-src"
        , "libcups-devel"
        , "libX11-devel"
        , "libXt-devel"
        , "libXrender-devel"
        , "libXrandr-devel"
        , "libXi-devel"
        , "libXtst-devel"
        , "alsa-lib-devel"
        , "fontconfig-devel"
        , "freetype-devel"
        , "capstone"
        , "capstone-devel"
        , "capstone-tool"
    );

    @Override
    protected CommandResult doExecute() throws Exception
    {
        // Validate init
        if (!InitCommand.hasBeenInitialized())
        {
            System.err.println("Error: attimo has not been initialized. Run 'ato init' first.");
            return CommandResult.valueOf(1);
        }

        // Check for existing active instance
        final var existingState = InstanceState.load();
        if (existingState.hasActiveInstance())
        {
            System.err.println("Error: an instance is already active (" + existingState.getInstanceId() + ").");
            System.err.println("Use 'ato connect' to reconnect or 'ato destroy' to tear it down first.");
            return CommandResult.valueOf(1);
        }

        final var config = AttimoConfig.load();
        final var preferredRegion = config.getPreferredRegion();
        if (preferredRegion.isBlank())
        {
            System.err.println("Error: no preferred region configured. Run 'ato init' first.");
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

        // 2. Find best spot option
        System.out.println("\n[2/5] Querying spot prices across region group...");

        final var factory = new AwsClientFactory();
        final var advisor = new SpotAdvisor(region -> factory.ec2(region));
        final var recommendation = advisor.recommend(feature, preferredRegion);

        if (recommendation == null)
        {
            System.err.println("Error: no spot instances available for " + isaFeature
                + " in the " + preferredRegion + " region group.");
            return CommandResult.valueOf(1);
        }

        System.out.println("  Best option: " + recommendation.rationale());

        // 3. Resolve base AMI
        System.out.println("\n[3/5] Resolving base AMI...");
        final var arch = "aarch64".equals(feature.architecture()) ? "arm64" : "x86_64";
        final var amiResolver = new BaseAmiResolver();

        final Ec2Client ec2 = factory.ec2(recommendation.region());
        final String amiId;
        try
        {
            amiId = amiResolver.resolve("fedora-44", ec2, arch);
        }
        catch (final Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            ec2.close();
            return CommandResult.valueOf(1);
        }

        // 4. Launch spot instance
        System.out.println("\n[4/5] Launching spot instance...");
        final var spotManager = new SpotManager(ec2);

        final String sgId;
        final String keyPairName;
        final String instanceId;
        try
        {
            sgId = spotManager.createSecurityGroup();
            final var pubKey = SshKeyManager.publicKeyContent();
            keyPairName = spotManager.importKeyPair(pubKey);
            instanceId = spotManager.launchSpotInstance(
                amiId
                , recommendation.instanceType()
                , sgId
                , keyPairName
            );
        }
        catch (final Exception e)
        {
            System.err.println("Error launching instance: " + e.getMessage());
            ec2.close();
            return CommandResult.valueOf(1);
        }

        // Wait for running
        final String publicIp;
        try
        {
            publicIp = spotManager.waitForRunning(instanceId, 300);
        }
        catch (final Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            ec2.close();
            return CommandResult.valueOf(1);
        }

        // Save state for reconnection
        final var state = new InstanceState();
        state.setInstanceId(instanceId);
        state.setRegion(recommendation.region());
        state.setAvailabilityZone(recommendation.availabilityZone());
        state.setInstanceType(recommendation.instanceType());
        state.setPublicIp(publicIp);
        state.setLaunchedAt(Instant.now().toString());
        state.setSpotPrice(recommendation.pricePerHour());
        state.setIsaFeature(isaFeature);
        state.setAmiId(amiId);
        state.setSecurityGroupId(sgId);
        state.setKeyPairName(keyPairName);
        state.setSessionId(spotManager.sessionId());
        state.save();

        // 5. Provision + SSH
        System.out.println("\n[5/5] Provisioning and connecting...");

        final var sshSession = new SshSession(publicIp);
        if (!sshSession.waitForSsh(300))
        {
            System.err.println("Error: SSH not reachable after 5 minutes.");
            System.err.println("Instance is running at " + publicIp + ". Use 'ato connect' to retry.");
            ec2.close();
            return CommandResult.valueOf(1);
        }

        // Provision packages (phase 1: hardcoded jdk-dev)
        final var provisioner = new SshProvisioner(publicIp);
        provisioner.installPackages(JDK_DEV_PACKAGES);

        // Connect
        final var exitCode = sshSession.connect();

        // Post-SSH: prompt to keep or destroy
        if (exitCode == 0)
        {
            promptKeepOrDestroy(ec2, state);
        }

        ec2.close();
        return CommandResult.SUCCESS;
    }

    private void promptKeepOrDestroy(
        final Ec2Client ec2
        , final InstanceState state
    )
    {
        final var console = System.console();
        if (console == null)
        {
            System.out.println("\nInstance is still running. Use 'ato destroy' to tear it down.");
            return;
        }

        System.out.print("\nKeep instance running? (y/N): ");
        final var answer = console.readLine().strip();

        if (answer.equalsIgnoreCase("y"))
        {
            System.out.println("Instance kept running. Reconnect with 'ato connect'.");
        }
        else
        {
            System.out.println("Destroying instance...");
            try
            {
                ec2.terminateInstances(
                    software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest.builder()
                        .instanceIds(state.getInstanceId())
                        .build()
                );
                // Wait briefly for termination before cleaning up SG
                Thread.sleep(5000);
                ec2.deleteKeyPair(
                    software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest.builder()
                        .keyName(state.getKeyPairName())
                        .build()
                );
                // SG deletion may fail if instance hasn't fully terminated
                try
                {
                    Thread.sleep(10000);
                    ec2.deleteSecurityGroup(
                        software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest.builder()
                            .groupId(state.getSecurityGroupId())
                            .build()
                    );
                }
                catch (final Exception e)
                {
                    System.err.println("  Warning: could not delete security group: " + e.getMessage());
                    System.err.println("  It will be cleaned up by 'ato destroy' later.");
                }
                InstanceState.clear();
                System.out.println("Instance destroyed.");
            }
            catch (final Exception e)
            {
                System.err.println("Error during cleanup: " + e.getMessage());
                System.err.println("Use 'ato destroy' to finish cleanup.");
            }
        }
    }
}
