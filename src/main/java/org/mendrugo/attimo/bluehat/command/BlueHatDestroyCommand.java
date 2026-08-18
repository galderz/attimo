package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.config.InstanceState;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
    name = "destroy"
    , description = "Destroy the active Blue Hat VM"
    , generateHelp = true
)
public class BlueHatDestroyCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load(BlueHat.CLOUD);

        if (!state.hasActiveInstance())
        {
            System.out.println("No active VM to destroy.");
            return CommandResult.SUCCESS;
        }

        final var fqdn = state.getInstanceId();
        System.out.println("=== Destroying Blue Hat VM ===\n");
        System.out.println("FQDN:  " + fqdn);
        System.out.println("Size:  " + state.getInstanceType());
        System.out.println();

        final var config = AttimoConfig.load(BlueHat.CLOUD);
        final var hostName = config.getHostName();

        if (hostName.isBlank())
        {
            System.err.println("Error: no Blue Hat host configured. Run 'ato bh init' first.");
            return CommandResult.valueOf(1);
        }

        try
        {
            final var client = new BlueHatClient(hostName);
            final var response = client.destroyVm(fqdn);

            if ("success".equalsIgnoreCase(response.status()))
            {
                System.out.println("  " + response.details()
                    + " (request: " + response.requestId() + ")");
                InstanceState.clear(BlueHat.CLOUD);
                System.out.println("\nVM destroyed.");
                return CommandResult.SUCCESS;
            }
            else
            {
                System.err.println("  Unexpected response status: " + response.status());
                System.err.println("  Run 'ato bh destroy' again to retry.");
                return CommandResult.valueOf(1);
            }
        }
        catch (final Exception e)
        {
            System.err.println("  Destroy failed: " + e.getMessage());
            System.err.println("  Run 'ato bh destroy' again to retry.");
            return CommandResult.valueOf(1);
        }
    }
}
