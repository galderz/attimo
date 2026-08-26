package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.bluehat.BlueHatInstanceSize;
import org.mendrugo.attimo.bluehat.BlueHatSettings;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.ssh.OsPackages;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.mendrugo.attimo.ssh.SshProvisioner;
import org.mendrugo.attimo.ssh.SshSession;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@CommandDefinition(
    name = "request"
    , description = "Request a Blue Hat VM for OpenJDK development"
    , generateHelp = true
)
public class BlueHatRequestCommand extends BaseCommand
{
    @Option(
        name = "size"
        , description = "Instance size: micro (1 CPU), small (8 CPUs)"
            + ", medium (16 CPUs, default), large (32 CPUs)"
        , defaultValue = "medium"
    )
    String size;

    @Override
    protected CommandResult doExecute() throws Exception
    {
        // Validate init
        if (!BlueHatInitCommand.hasBeenInitialized())
        {
            System.err.println("Error: Blue Hat has not been initialized. Run 'ato bh init' first.");
            return CommandResult.FAILURE;
        }

        // Check for existing active instance
        final var existingState = InstanceState.load(BlueHat.CLOUD);
        if (existingState.hasActiveInstance())
        {
            System.err.println("Error: a VM is already active (" + existingState.getInstanceId() + ").");
            System.err.println("Use 'ato bh connect' to reconnect or 'ato bh destroy' to tear it down first.");
            return CommandResult.FAILURE;
        }

        // Parse instance size
        final BlueHatInstanceSize instanceSize;
        try
        {
            instanceSize = BlueHatInstanceSize.fromLabel(size);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        final var hostName = BlueHatSettings.hostName();
        final var port = BlueHat.API_PORT;

        // Start local cloud if needed
        Process localProcess = null;
        try
        {
            localProcess = BlueHatCloudRunner.ensureCloudRunning(hostName, port);
            return doRequest(hostName, port, instanceSize);
        }
        finally
        {
            BlueHatCloudRunner.stop(localProcess);
        }
    }

    private CommandResult doRequest(
        final String hostName
        , final int port
        , final BlueHatInstanceSize instanceSize
    )
    {
        System.out.println("=== Requesting Blue Hat VM ===\n");
        System.out.println("[1/4] Preparing request (size: " + instanceSize.label()
            + ", " + instanceSize.cpus() + " CPUs, " + instanceSize.memoryGb() + " GB)...");

        // Build the request
        final var pubKey = SshKeyManager.publicKeyContent(BlueHat.CLOUD);
        final var description = generateDescription();
        final var request = new BlueHatClient.VmRequest(
            String.valueOf(instanceSize.cpus())
            , String.valueOf(instanceSize.memoryGb())
            , BlueHat.DEFAULT_OS
            , description
            , pubKey
        );

        // Send the request
        System.out.println("\n[2/4] Requesting VM from Blue Hat (" + hostName + ":" + port + ")...");
        final var client = new BlueHatClient(hostName, port);
        final var response = client.requestVm(request);
        final var fqdn = response.fqdn();
        if (!fqdn.matches("[A-Za-z0-9._-]+"))
        {
            System.err.println("Error: FQDN validation failed: " + fqdn);
            return CommandResult.FAILURE;
        }
        System.out.println("  VM provisioned: " + fqdn);

        // Save state for reconnection
        final var state = new InstanceState();
        state.setInstanceId(fqdn);
        state.setPublicIp(fqdn);
        state.setInstanceType(instanceSize.label());
        state.setLaunchedAt(Instant.now().toString());
        state.save(BlueHat.CLOUD);

        // Wait for SSH and provision
        System.out.println("\n[3/4] Waiting for SSH...");
        final var keyFile = Environment.sshKeyFile(BlueHat.CLOUD);
        final var sshSession = new SshSession(fqdn, BlueHat.SSH_USER, keyFile);
        if (!sshSession.waitForSsh(300))
        {
            System.err.println("Error: SSH not reachable after 5 minutes.");
            System.err.println("VM is provisioned at " + fqdn
                + ". Use 'ato bh connect' to retry.");
            return CommandResult.FAILURE;
        }

        // Provision packages (same as AWS)
        System.out.println("\n[4/4] Provisioning and connecting...");
        final var provisioner = new SshProvisioner(fqdn, BlueHat.SSH_USER, keyFile);

        // Install Corretto 25
        System.out.println("  Installing Amazon Corretto 25...");
        for (final var cmd : OsPackages.CORRETTO_25_INSTALL_COMMANDS)
        {
            final var rc = provisioner.run(cmd);
            if (rc != 0)
            {
                System.err.println("  Warning: Corretto 25 install failed (exit " + rc + ").");
                break;
            }
        }

        // Any errors during provisioning are not treated as fatal.
        // Inspect the logs for any warning messages if issues encountered during provisioning.
        provisioner.installPackages(OsPackages.JDK_DEV_PACKAGES);

        // Connect via SSH
        final var exitCode = sshSession.connect();

        // Post-SSH: prompt to keep or destroy
        if (exitCode == 0)
        {
            promptKeepOrDestroy(client, state);
        }

        return CommandResult.SUCCESS;
    }

    private String generateDescription()
    {
        final var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
        return "attimo VM created at " + formatter.format(Instant.now());
    }

    private void promptKeepOrDestroy(
        final BlueHatClient client
        , final InstanceState state
    )
    {
        final var console = System.console();
        if (console == null)
        {
            System.out.println("\nVM is still running. Use 'ato bh destroy' to tear it down.");
            return;
        }

        System.out.print("\nKeep VM running? (y/N): ");
        final var answer = console.readLine().strip();

        if (answer.equalsIgnoreCase("y"))
        {
            System.out.println("VM kept running. Reconnect with 'ato bh connect'.");
        }
        else
        {
            System.out.println("Destroying VM...");
            try
            {
                final var fqdn = state.getInstanceId();
                final var response = client.destroyVm(fqdn);

                if ("success".equalsIgnoreCase(response.status()))
                {
                    System.out.println("  " + response.details()
                        + " (request: " + response.requestId() + ")");
                    InstanceState.clear(BlueHat.CLOUD);
                    System.out.println("VM destroyed.");
                }
                else
                {
                    System.err.println("  Unexpected response: " + response.status());
                    System.err.println("  Run 'ato bh destroy' to retry.");
                }
            }
            catch (final Exception e)
            {
                System.err.println("  Destroy failed: " + e.getMessage());
                System.err.println("  Run 'ato bh destroy' to retry.");
            }
        }
    }
}
