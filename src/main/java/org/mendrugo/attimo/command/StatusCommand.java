package org.mendrugo.attimo.command;

import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.SpotManager;
import org.mendrugo.attimo.config.InstanceState;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.time.Duration;
import java.time.Instant;

@CommandDefinition(
    name = "status"
    , description = "Show the status of the active spot instance"
    , generateHelp = true
)
public class StatusCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load();

        if (!state.hasActiveInstance())
        {
            System.out.println("No active instance.");
            return CommandResult.SUCCESS;
        }

        System.out.println("=== Spot Instance Status ===\n");
        System.out.println("Instance:  " + state.getInstanceId());
        System.out.println("Type:      " + state.getInstanceType());
        System.out.println("Region:    " + state.getRegion());
        System.out.println("AZ:        " + state.getAvailabilityZone());
        System.out.println("IP:        " + state.getPublicIp());
        System.out.println("ISA:       " + state.getIsaFeature());
        System.out.println("AMI:       " + state.getAmiId());

        // Calculate uptime and cost
        if (!state.getLaunchedAt().isBlank())
        {
            try
            {
                final var launchedAt = Instant.parse(state.getLaunchedAt());
                final var uptime = Duration.between(launchedAt, Instant.now());
                final var hours = uptime.toMinutes() / 60.0;
                final var cost = hours * state.getSpotPrice();

                System.out.printf("Uptime:    %dh %dm%n"
                    , uptime.toHours()
                    , uptime.toMinutes() % 60
                );
                System.out.printf("Cost:      $%.3f (@ $%.4f/hr)%n"
                    , cost
                    , state.getSpotPrice()
                );
            }
            catch (final Exception e)
            {
                System.out.println("Uptime:    unknown");
            }
        }

        // Verify instance is actually running
        System.out.println();
        try
        {
            final var factory = new AwsClientFactory();
            try (final var ec2 = factory.ec2(state.getRegion()))
            {
                final var manager = new SpotManager(ec2, state.getSessionId());
                if (manager.isRunning(state.getInstanceId()))
                {
                    System.out.println("Status:    \u001B[32m● Running\u001B[0m");
                }
                else
                {
                    System.out.println("Status:    \u001B[31m● Not running\u001B[0m (may have been terminated)");
                    System.out.println("Run 'ato destroy' to clean up resources.");
                }
            }
        }
        catch (final Exception e)
        {
            System.out.println("Status:    unknown (could not reach AWS: " + e.getMessage() + ")");
        }

        return CommandResult.SUCCESS;
    }
}
