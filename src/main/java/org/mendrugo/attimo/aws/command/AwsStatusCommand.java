package org.mendrugo.attimo.aws.command;

import java.time.Duration;
import java.time.Instant;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.aws.SpotManager;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import software.amazon.awssdk.services.ec2.Ec2Client;

@CommandDefinition(
        name = "status",
        description = "Show the status of the active spot instance",
        generateHelp = true)
public class AwsStatusCommand extends BaseCommand {
    @Override
    protected CommandResult doExecute() throws Exception {
        InstanceState state = InstanceState.load(Aws.CLOUD);

        if (!state.hasActiveInstance()) {
            System.out.println("No active instance.");
            return CommandResult.SUCCESS;
        }

        System.out.println("=== Spot Instance Status ===\n");
        System.out.println("Instance:  " + state.instanceId);
        System.out.println("Type:      " + state.instanceType);
        System.out.println("Region:    " + state.region);
        System.out.println("AZ:        " + state.availabilityZone);
        System.out.println("IP:        " + state.publicIp);
        System.out.println("ISA:       " + state.isaFeature);
        System.out.println("AMI:       " + state.amiId);

        if (!state.launchedAt.isBlank()) {
            try {
                Instant launchedAt = Instant.parse(state.launchedAt);
                Duration uptime = Duration.between(launchedAt, Instant.now());
                double hours = uptime.toMinutes() / 60.0;
                double cost = hours * state.spotPrice;

                System.out.printf("Uptime:    %dh %dm%n", uptime.toHours(), uptime.toMinutes() % 60);
                System.out.printf("Cost:      $%.3f (@ $%.4f/hr)%n", cost, state.spotPrice);
            } catch (Exception e) {
                System.out.println("Uptime:    unknown");
            }
        }

        System.out.println();
        try {
            AwsClientFactory factory = new AwsClientFactory();
            try (Ec2Client ec2 = factory.ec2(state.region)) {
                SpotManager manager = new SpotManager(ec2, state.sessionId);
                if (manager.isRunning(state.instanceId)) {
                    System.out.println("Status:    \u001B[32m● Running\u001B[0m");
                } else {
                    System.out.println("Status:    \u001B[31m● Not running\u001B[0m (may have been terminated)");
                    System.out.println("Run 'ato aws destroy' to clean up resources.");
                }
            }
        } catch (Exception e) {
            System.out.println("Status:    unknown (could not reach AWS: " + e.getMessage() + ")");
        }

        return CommandResult.SUCCESS;
    }
}
