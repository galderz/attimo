package org.mendrugo.attimo.aws.command;

import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.ResourceCleaner;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
    name = "destroy"
    , description = "Tear down the active spot instance and all associated resources"
    , generateHelp = true
)
public class AwsDestroyCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load(Aws.CLOUD);

        if (state.hasActiveInstance())
        {
            return destroyActiveInstance(state);
        }

        // No active instance — scan for orphaned resources
        return scanForOrphans();
    }

    private CommandResult destroyActiveInstance(final InstanceState state)
    {
        System.out.println("=== Destroying spot instance ===\n");
        System.out.println("Instance: " + state.getInstanceId());
        System.out.println("Type:     " + state.getInstanceType());
        System.out.println("Region:   " + state.getRegion());
        System.out.println("IP:       " + state.getPublicIp());
        System.out.println();

        final var factory = new AwsClientFactory();
        try (final var ec2 = factory.ec2(state.getRegion()))
        {
            final var cleaner = new ResourceCleaner(ec2);
            final var errors = cleaner.cleanAll(state, Aws.CLOUD);

            if (errors.isEmpty())
            {
                System.out.println("\nAll resources cleaned up. Zero cost footprint.");
                return CommandResult.SUCCESS;
            }
            else
            {
                System.err.println("\nCleanup completed with errors:");
                for (final var error : errors)
                {
                    System.err.println("  - " + error);
                }
                System.err.println("Run 'ato aws destroy' again to retry.");
                return CommandResult.valueOf(1);
            }
        }
    }

    private CommandResult scanForOrphans()
    {
        System.out.println("No active instance in state file.");
        System.out.println("Scanning for orphaned attimo resources...\n");

        final var config = AttimoConfig.load(Aws.CLOUD);
        final var preferredRegion = config.getPreferredRegion();

        if (preferredRegion.isBlank())
        {
            System.err.println("Error: no preferred region configured. Run 'ato aws init' first.");
            return CommandResult.valueOf(1);
        }

        final var factory = new AwsClientFactory();

        // Scan the preferred region and its group
        final var regionGroup = org.mendrugo.attimo.config.RegionGroup.forRegion(preferredRegion);
        var foundAny = false;

        for (final String region : regionGroup.regions())
        {
            try (final var ec2 = factory.ec2(region))
            {
                System.out.println("Checking " + region + "...");
                final var cleaner = new ResourceCleaner(ec2);
                final var errors = cleaner.cleanOrphans();

                if (!errors.isEmpty())
                {
                    foundAny = true;
                    for (final var error : errors)
                    {
                        System.err.println("  - " + error);
                    }
                }
            }
            catch (final Exception e)
            {
                final var msg = e.getMessage();
                if (msg != null && (msg.contains("401") || msg.contains("AuthFailure")
                    || msg.contains("OptInRequired")))
                {
                    System.out.println("  Skipped (region not enabled in your account)");
                }
                else
                {
                    System.err.println("  Warning: could not check " + region + ": " + msg);
                }
            }
        }

        if (!foundAny)
        {
            System.out.println("\nNo orphaned attimo resources found. All clean.");
        }

        // Clear any stale state file
        InstanceState.clear(Aws.CLOUD);
        return CommandResult.SUCCESS;
    }
}
