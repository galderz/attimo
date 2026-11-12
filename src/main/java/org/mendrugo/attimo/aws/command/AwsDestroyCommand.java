package org.mendrugo.attimo.aws.command;

import java.util.List;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.AwsConfig;
import org.mendrugo.attimo.aws.Continent;
import org.mendrugo.attimo.aws.ResourceCleaner;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import software.amazon.awssdk.services.ec2.Ec2Client;

@CommandDefinition(
        name = "destroy",
        description = "Tear down the active spot instance and all associated resources",
        generateHelp = true)
public class AwsDestroyCommand extends BaseCommand {
    @Option(
            name = "scan-all-regions",
            description = "Scan all regions in the continent for orphaned resources" + " (default: scan 3 representative regions only)",
            hasValue = false,
            defaultValue = "false")
    boolean scanAllRegions;

    @Override
    protected CommandResult doExecute() throws Exception {
        InstanceState state = InstanceState.load(Aws.CLOUD);

        if (state.hasActiveInstance()) {
            return destroyActiveInstance(state);
        }

        return scanForOrphans();
    }

    private CommandResult destroyActiveInstance(InstanceState state) {
        System.out.println("=== Destroying spot instance ===\n");
        System.out.println("Instance: " + state.instanceId);
        System.out.println("Type:     " + state.instanceType);
        System.out.println("Region:   " + state.region);
        System.out.println("IP:       " + state.publicIp);
        System.out.println();

        AwsClientFactory factory = new AwsClientFactory();
        try (Ec2Client ec2 = factory.ec2(state.region)) {
            ResourceCleaner cleaner = new ResourceCleaner(ec2);
            List<String> errors = cleaner.cleanAll(state, Aws.CLOUD);

            if (errors.isEmpty()) {
                System.out.println("\nAll resources cleaned up. Zero cost footprint.");
                return CommandResult.SUCCESS;
            } else {
                System.err.println("\nCleanup completed with errors:");
                for (String error : errors) {
                    System.err.println("  - " + error);
                }
                System.err.println("Run 'ato aws destroy' again to retry.");
                return CommandResult.FAILURE;
            }
        }
    }

    private CommandResult scanForOrphans() {
        System.out.println("No active instance in state file.");
        System.out.println("Scanning for orphaned attimo resources...\n");

        AwsConfig config = AwsConfig.load();
        String preferredRegion = config.preferredRegion;

        if (preferredRegion.isBlank()) {
            System.err.println("Error: no preferred region configured. Run 'ato aws init' first.");
            return CommandResult.FAILURE;
        }

        AwsClientFactory factory = new AwsClientFactory();

        Continent continent = Continent.forRegion(preferredRegion);
        List<String> regionsToScan = scanAllRegions ? continent.regionCodes() : continent.representativeCodes();

        System.out.println("Scanning "
                + regionsToScan.size()
                + " regions in "
                + continent.displayName()
                + (scanAllRegions ? " (all regions)" : " (representatives only)")
                + "...\n");

        boolean foundAny = false;

        for (String region : regionsToScan) {
            try (Ec2Client ec2 = factory.ec2(region)) {
                System.out.println("Checking " + region + "...");
                ResourceCleaner cleaner = new ResourceCleaner(ec2);
                List<String> errors = cleaner.cleanOrphans();

                if (!errors.isEmpty()) {
                    foundAny = true;
                    for (String error : errors) {
                        System.err.println("  - " + error);
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null
                        && (msg.contains("401") || msg.contains("AuthFailure") || msg.contains("OptInRequired"))) {
                    System.out.println("  Skipped (region not enabled in your account)");
                } else {
                    System.err.println("  Warning: could not check " + region + ": " + msg);
                }
            }
        }

        if (!foundAny) {
            System.out.println("\nNo orphaned attimo resources found. All clean.");
        }

        InstanceState.clear(Aws.CLOUD);
        return CommandResult.SUCCESS;
    }
}
