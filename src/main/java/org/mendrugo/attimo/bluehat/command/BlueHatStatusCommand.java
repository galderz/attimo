package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

@CommandDefinition(
    name = "status"
    , description = "Show the status of the active Blue Hat VM"
    , generateHelp = true
)
public class BlueHatStatusCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load(BlueHat.CLOUD);

        if (!state.hasActiveInstance())
        {
            System.out.println("No active VM.");
            return CommandResult.SUCCESS;
        }

        final var fqdn = state.getInstanceId();
        System.out.println("=== Blue Hat VM Status ===\n");
        System.out.println("FQDN:      " + fqdn);
        System.out.println("Size:      " + state.getInstanceType());

        // Query Blue Hat API for live status
        final var config = BlueHatConfig.load();
        final var hostName = config.getHostName();

        if (hostName.isBlank())
        {
            System.out.println("Status:    unknown (no host configured)");
            return CommandResult.SUCCESS;
        }

        try
        {
            final var client = new BlueHatClient(hostName);
            final var vms = client.listVms();

            final var vmOpt = vms.stream()
                .filter(vm -> fqdn.equals(vm.fqdn()))
                .findFirst();

            if (vmOpt.isPresent())
            {
                final var vm = vmOpt.get();
                System.out.println("VM ID:     " + vm.vmId());
                System.out.println("State:     " + formatState(vm.state()));

                // Calculate uptime from created timestamp
                if (vm.createdIso8601() != null && !vm.createdIso8601().isBlank())
                {
                    try
                    {
                        final var created = OffsetDateTime.parse(vm.createdIso8601()).toInstant();
                        final var uptime = Duration.between(created, Instant.now());
                        System.out.printf("Uptime:    %dh %dm%n"
                            , uptime.toHours()
                            , uptime.toMinutes() % 60
                        );
                    }
                    catch (final Exception e)
                    {
                        System.out.println("Created:   " + vm.created());
                    }
                }
                else if (vm.created() != null && !vm.created().isBlank())
                {
                    System.out.println("Created:   " + vm.created());
                }

                if (vm.description() != null && !vm.description().isBlank())
                {
                    System.out.println("Desc:      " + vm.description());
                }
            }
            else
            {
                System.out.println("Status:    \u001B[31m● Not found\u001B[0m"
                    + " (VM may have been terminated)");
                System.out.println("Run 'ato bh destroy' to clean up state.");
            }
        }
        catch (final Exception e)
        {
            System.out.println("Status:    unknown (could not reach Blue Hat: "
                + e.getMessage() + ")");
        }

        return CommandResult.SUCCESS;
    }

    private String formatState(final String state)
    {
        if ("running".equalsIgnoreCase(state))
        {
            return "\u001B[32m● Running\u001B[0m";
        }
        else if (state != null)
        {
            return "\u001B[31m● " + state + "\u001B[0m";
        }

        return "unknown";
    }
}
