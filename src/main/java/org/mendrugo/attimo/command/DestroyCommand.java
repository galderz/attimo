package org.mendrugo.attimo.command;

import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.ResourceCleaner;
import org.mendrugo.attimo.config.InstanceState;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
    name = "destroy"
    , description = "Tear down the active spot instance and all associated resources"
    , generateHelp = true
)
public class DestroyCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load();

        if (!state.hasActiveInstance())
        {
            System.err.println("No active instance to destroy.");
            return CommandResult.valueOf(1);
        }

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
            final var errors = cleaner.cleanAll(state);

            if (errors.isEmpty())
            {
                System.out.println("\nAll resources cleaned up. Zero cost footprint.");
            }
            else
            {
                System.err.println("\nCleanup completed with errors:");
                for (final var error : errors)
                {
                    System.err.println("  - " + error);
                }
                System.err.println("Some resources may still exist. Check the AWS console.");
                return CommandResult.valueOf(1);
            }
        }

        return CommandResult.SUCCESS;
    }
}
