package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatSettings;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.ssh.SshSession;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
    name = "connect"
    , description = "SSH into the active Blue Hat VM"
    , generateHelp = true
)
public class BlueHatConnectCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load(BlueHat.CLOUD);

        if (!state.hasActiveInstance())
        {
            System.err.println("No active VM. Use 'ato bh request' to launch one.");
            return CommandResult.FAILURE;
        }

        final var fqdn = state.getInstanceId();
        final var hostName = BlueHatSettings.hostName();
        final var port = BlueHatSettings.apiPort();

        // Start local cloud if needed
        Process localProcess = null;
        try
        {
            localProcess = BlueHatRequestCommand.ensureCloudRunning(hostName, port);
            return doConnect(fqdn, hostName, port);
        }
        finally
        {
            BlueHatCloudRunner.stop(localProcess);
        }
    }

    private CommandResult doConnect(
        final String fqdn
        , final String hostName
        , final int port
    )
    {
        // Verify the VM is still running
        try
        {
            final var client = new BlueHatClient(hostName, port);
            final var vms = client.listVms();
            final var isRunning = vms.stream()
                .anyMatch(vm -> fqdn.equals(vm.fqdn())
                    && "running".equalsIgnoreCase(vm.state()));

            if (!isRunning)
            {
                System.err.println("VM " + fqdn + " is not running.");
                System.err.println("Use 'ato bh destroy' to clean up or 'ato bh request' for a new VM.");
                return CommandResult.FAILURE;
            }
        }
        catch (final Exception e)
        {
            System.err.println("Warning: could not verify VM status: " + e.getMessage());
            System.err.println("Attempting to connect anyway...");
        }

        System.out.println("Connecting to " + fqdn + "...");

        final var keyFile = Environment.sshKeyFile(BlueHat.CLOUD);
        final var session = new SshSession(fqdn, BlueHat.SSH_USER, keyFile);
        final var exitCode = session.connect();

        return exitCode == 0 ? CommandResult.SUCCESS : CommandResult.valueOf(exitCode);
    }
}
