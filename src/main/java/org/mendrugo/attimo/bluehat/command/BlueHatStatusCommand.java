package org.mendrugo.attimo.bluehat.command;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatClient;
import org.mendrugo.attimo.bluehat.BlueHatClient.VmDetails;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;

@CommandDefinition(
        name = "status",
        description = "Show the status of the active Blue Hat VM",
        generateHelp = true)
public class BlueHatStatusCommand extends BaseCommand {
    @Override
    protected CommandResult doExecute() throws Exception {
        final InstanceState state = InstanceState.load(BlueHat.CLOUD);

        if (!state.hasActiveInstance()) {
            System.out.println("No active VM.");
            return CommandResult.SUCCESS;
        }

        final String fqdn = state.getInstanceId();
        final BlueHatConfig config = BlueHatConfig.load();
        final String hostName = config.effectiveHostName();
        final int port = BlueHat.API_PORT;

        Process localProcess = null;
        try {
            localProcess = BlueHatCloudRunner.ensureCloudRunning(config);
            return doStatus(fqdn, hostName, port, state);
        } finally {
            BlueHatCloudRunner.stop(localProcess);
        }
    }

    private CommandResult doStatus(String fqdn, String hostName, int port,
            final InstanceState state) {
        System.out.println("=== Blue Hat VM Status ===\n");
        System.out.println("FQDN:      " + fqdn);
        System.out.println("Size:      " + state.getInstanceType());

        try {
            final BlueHatClient client = new BlueHatClient(hostName, port);
            final List<VmDetails> vms = client.listVms();

            final Optional<VmDetails> vmOpt = vms.stream()
                    .filter(vm -> fqdn.equals(vm.fqdn()))
                    .findFirst();

            if (vmOpt.isPresent()) {
                final VmDetails vm = vmOpt.get();
                System.out.println("VM ID:     " + vm.vmId());
                System.out.println("State:     " + formatState(vm.state()));

                if (vm.createdIso8601() != null && !vm.createdIso8601().isBlank()) {
                    try {
                        final Instant created = OffsetDateTime.parse(vm.createdIso8601()).toInstant();
                        final Duration uptime = Duration.between(created, Instant.now());
                        System.out.printf("Uptime:    %dh %dm%n", uptime.toHours(), uptime.toMinutes() % 60);
                    } catch (Exception e) {
                        System.out.println("Created:   " + vm.created());
                    }
                } else if (vm.created() != null && !vm.created().isBlank()) {
                    System.out.println("Created:   " + vm.created());
                }

                if (vm.description() != null && !vm.description().isBlank()) {
                    System.out.println("Desc:      " + vm.description());
                }
            } else {
                System.out.println("Status:    \u001B[31m● Not found\u001B[0m" + " (VM may have been terminated)");
                System.out.println("Run 'ato bh destroy' to clean up state.");
            }
        } catch (Exception e) {
            System.out.println("Status:    unknown (could not reach Blue Hat: " + e.getMessage() + ")");
        }

        return CommandResult.SUCCESS;
    }

    private String formatState(String state) {
        if ("running".equalsIgnoreCase(state)) {
            return "\u001B[32m● Running\u001B[0m";
        } else if (state != null) {
            return "\u001B[31m● " + state + "\u001B[0m";
        }

        return "unknown";
    }
}
