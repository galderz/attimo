package org.mendrugo.attimo.bluehat.command;

import java.nio.file.Path;
import java.util.List;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.bluehat.BlueHatClient.VmDetails;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.ssh.SshSession;

@CommandDefinition(name = "connect", description = "SSH into the active Blue Hat VM", generateHelp = true)
public class BlueHatConnectCommand extends BaseCommand {
    @Override
    protected CommandResult doExecute() throws Exception {
        final InstanceState state = InstanceState.load(BlueHat.CLOUD);

        if (!state.hasActiveInstance()) {
            System.err.println("No active VM. Use 'ato bh request' to launch one.");
            return CommandResult.FAILURE;
        }

        final String fqdn = state.getInstanceId();
        final BlueHatConfig config = BlueHatConfig.load();
        final String hostName = config.effectiveHostName();
        final int port = BlueHat.API_PORT;

        Process localProcess = null;
        try {
            localProcess = BlueHatCloudRunner.ensureCloudRunning(config);
            return doConnect(fqdn, hostName, port);
        } finally {
            BlueHatCloudRunner.stop(localProcess);
        }
    }

    private CommandResult doConnect(String fqdn, String hostName, int port) {
        try {
            final BlueHatClient client = new BlueHatClient(hostName, port);
            final List<VmDetails> vms = client.listVms();
            final boolean isRunning = vms.stream().anyMatch(vm -> fqdn.equals(vm.fqdn()) && "running".equalsIgnoreCase(vm.state()));

            if (!isRunning) {
                System.err.println("VM " + fqdn + " is not running.");
                System.err.println("Use 'ato bh destroy' to clean up or 'ato bh request' for a new VM.");
                return CommandResult.FAILURE;
            }
        } catch (Exception e) {
            System.err.println("Warning: could not verify VM status: " + e.getMessage());
            System.err.println("Attempting to connect anyway...");
        }

        System.out.println("Connecting to " + fqdn + "...");

        final Path keyFile = Environment.sshKeyFile(BlueHat.CLOUD);
        final SshSession session = new SshSession(fqdn, BlueHat.SSH_USER, keyFile);
        final int exitCode = session.connect();

        return exitCode == 0 ? CommandResult.SUCCESS : CommandResult.valueOf(exitCode);
    }
}
