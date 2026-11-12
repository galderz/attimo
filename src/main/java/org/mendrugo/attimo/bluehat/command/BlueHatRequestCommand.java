package org.mendrugo.attimo.bluehat.command;

import java.io.Console;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.bluehat.BlueHatClient.DestroyResponse;
import org.mendrugo.attimo.bluehat.BlueHatClient.VmRequest;
import org.mendrugo.attimo.bluehat.BlueHatClient.VmResponse;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.bluehat.BlueHatInstanceSize;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.ImageDef;
import org.mendrugo.attimo.config.ImageDef.ResolvedTemplate;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.ssh.BootJdkProvisioner;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.mendrugo.attimo.ssh.SshProvisioner;
import org.mendrugo.attimo.ssh.SshSession;

@CommandDefinition(
        name = "request",
        description = "Request a Blue Hat VM for OpenJDK development",
        generateHelp = true)
public class BlueHatRequestCommand extends BaseCommand {
    @Option(
            name = "size",
            description = "Instance size: micro (1 CPU), small (8 CPUs)" + ", medium (16 CPUs, default), large (32 CPUs)",
            defaultValue = "medium")
    String size;

    @Option(
            name = "template",
            description = "Image template to use for provisioning (default: tpl-openjdk)",
            defaultValue = "tpl-openjdk")
    String template;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!BlueHatInitCommand.hasBeenInitialized()) {
            System.err.println("Error: Blue Hat has not been initialized. Run 'ato bh init' first.");
            return CommandResult.FAILURE;
        }

        ResolvedTemplate resolved = ImageDef.resolve(template);
        if (resolved == null) {
            return CommandResult.FAILURE;
        }

        InstanceState existingState = InstanceState.load(BlueHat.CLOUD);
        if (existingState.hasActiveInstance()) {
            System.err.println("Error: a VM is already active (" + existingState.instanceId + ").");
            System.err.println("Use 'ato bh connect' to reconnect or 'ato bh destroy' to tear it down first.");
            return CommandResult.FAILURE;
        }

        BlueHatConfig config = BlueHatConfig.load();
        if (!config.hasCloudTarget()) {
            System.err.println("Error: no Blue Hat cloud configured. Run 'ato bh init' first.");
            return CommandResult.FAILURE;
        }

        BlueHatInstanceSize instanceSize;
        try {
            instanceSize = BlueHatInstanceSize.fromLabel(size);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        String hostName = config.effectiveHostName();
        int port = BlueHat.API_PORT;

        Process localProcess = null;
        try {
            localProcess = BlueHatCloudRunner.ensureCloudRunning(config);
            return doRequest(hostName, port, instanceSize, resolved);
        } finally {
            BlueHatCloudRunner.stop(localProcess);
        }
    }

    private CommandResult doRequest(String hostName, int port, BlueHatInstanceSize instanceSize,
            ResolvedTemplate resolved) {
        System.out.println("=== Requesting Blue Hat VM ===\n");
        System.out.println("[1/4] Preparing request (size: "
                + instanceSize.label()
                + ", "
                + instanceSize.cpus()
                + " CPUs, "
                + instanceSize.memoryGb()
                + " GB)...");

        String pubKey = SshKeyManager.publicKeyContent(BlueHat.CLOUD);
        String description = generateDescription();
        VmRequest request = new VmRequest(String.valueOf(instanceSize.cpus()), String.valueOf(instanceSize.memoryGb()), BlueHat.DEFAULT_OS, description,
                pubKey);

        System.out.println("\n[2/4] Requesting VM from Blue Hat (" + hostName + ":" + port + ")...");
        BlueHatClient client = new BlueHatClient(hostName, port);
        VmResponse response = client.requestVm(request);
        String fqdn = response.fqdn();
        if (!fqdn.matches("[A-Za-z0-9._-]+")) {
            System.err.println("Error: FQDN validation failed: " + fqdn);
            return CommandResult.FAILURE;
        }
        System.out.println("  VM provisioned: " + fqdn);

        InstanceState state = new InstanceState();
        state.instanceId = fqdn;
        state.publicIp = fqdn;
        state.instanceType = instanceSize.label();
        state.launchedAt = Instant.now().toString();
        state.save(BlueHat.CLOUD);

        System.out.println("\n[3/4] Waiting for SSH...");
        Path keyFile = Environment.sshKeyFile(BlueHat.CLOUD);
        SshSession sshSession = new SshSession(fqdn, BlueHat.SSH_USER, keyFile);
        if (!sshSession.waitForSsh(300)) {
            System.err.println("Error: SSH not reachable after 5 minutes.");
            System.err.println("VM is provisioned at " + fqdn + ". Use 'ato bh connect' to retry.");
            return CommandResult.FAILURE;
        }

        System.out.println("\n[4/4] Provisioning and connecting...");
        SshProvisioner provisioner = new SshProvisioner(fqdn, BlueHat.SSH_USER, keyFile);
        List<String> allPackages = resolved.packages();
        BootJdkProvisioner.provision(allPackages, provisioner, true, "x86_64");

        int exitCode = sshSession.connect();

        if (exitCode == 0) {
            promptKeepOrDestroy(client, state);
        }

        return CommandResult.SUCCESS;
    }

    private String generateDescription() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        return "attimo VM created at " + formatter.format(Instant.now());
    }

    private void promptKeepOrDestroy(BlueHatClient client, InstanceState state) {
        Console console = System.console();
        if (console == null) {
            System.out.println("\nVM is still running. Use 'ato bh destroy' to tear it down.");
            return;
        }

        System.out.print("\nKeep VM running? (y/N): ");
        String answer = console.readLine().strip();

        if (answer.equalsIgnoreCase("y")) {
            System.out.println("VM kept running. Reconnect with 'ato bh connect'.");
        } else {
            System.out.println("Destroying VM...");
            try {
                String fqdn = state.instanceId;
                DestroyResponse response = client.destroyVm(fqdn);

                if ("success".equalsIgnoreCase(response.status())) {
                    System.out.println("  "
                            + response.details()
                            + " (request: "
                            + response.requestId()
                            + ")");
                    InstanceState.clear(BlueHat.CLOUD);
                    System.out.println("VM destroyed.");
                } else {
                    System.err.println("  Unexpected response: " + response.status());
                    System.err.println("  Run 'ato bh destroy' to retry.");
                }
            } catch (Exception e) {
                System.err.println("  Destroy failed: " + e.getMessage());
                System.err.println("  Run 'ato bh destroy' to retry.");
            }
        }
    }
}
