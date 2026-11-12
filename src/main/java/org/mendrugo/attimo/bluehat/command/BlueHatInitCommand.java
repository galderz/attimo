package org.mendrugo.attimo.bluehat.command;

import java.io.Console;
import java.nio.file.Files;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.ssh.SshKeyManager;

@CommandDefinition(
        name = "init",
        description = "One-time setup: choose cloud mode, configure SSH key",
        generateHelp = true)
public class BlueHatInitCommand extends BaseCommand {

    public static boolean hasBeenInitialized() {
        return Files.exists(Environment.configFile(BlueHat.CLOUD));
    }

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println("=== attimo bh init ===\n");

        BlueHatConfig config = BlueHatConfig.load();
        Console console = System.console();

        if (config.hasCloudTarget()) {
            System.out.println("Current configuration:");
            if (config.isLocal()) {
                System.out.println("  Mode:       local (git repository)");
                System.out.println("  Repository: " + config.repository);
            } else {
                System.out.println("  Mode:       remote");
                System.out.println("  Host:       " + config.hostName);
            }
            System.out.println();

            if (console != null) {
                System.out.print("  Reconfigure? (y/N): ");
                String answer = console.readLine().strip();
                if (!answer.equalsIgnoreCase("y")) {
                    setupSshKey(console, config);
                    config.save();
                    printNextSteps();
                    return CommandResult.SUCCESS;
                }
            }
        }

        setupCloudMode(console, config);
        setupSshKey(console, config);
        setupSearchPaths(console);

        config.save();

        if (config.isLocal()) {
            System.out.println("\nBuilding local Blue Hat cloud...");
            BlueHatCloudRunner.cloneAndBuild(config.repository);
        }

        printNextSteps();
        return CommandResult.SUCCESS;
    }

    private void setupCloudMode(Console console, BlueHatConfig config) {
        System.out.println("How will you connect to the Blue Hat cloud?\n");
        System.out.println("  1) Git repository — attimo clones, builds, and runs it locally");
        System.out.println("  2) Remote host    — connect to a Blue Hat cloud already running\n");

        if (console == null) {
            System.err.println("Error: no console available for interactive setup.");
            System.err.println("Set repository or host-name manually in " + Environment.configFile(BlueHat.CLOUD));
            return;
        }

        System.out.print("Choice [1/2]: ");
        String choice = console.readLine().strip();

        if ("1".equals(choice)) {
            System.out.print("  Git repository URL: ");
            String repo = console.readLine().strip();

            if (repo.isBlank()) {
                System.err.println("  Error: repository URL cannot be empty.");
                return;
            }

            System.out.println();
            System.out.println("  ⚠  The Blue Hat cloud requires configuration via environment");
            System.out.println("     variables (e.g. cloud credentials, API keys).");
            System.out.println("     Check the README in the repository for required variables.");
            System.out.println("     These must be set in your shell before running ato bh commands.");
            System.out.println();
            System.out.print("  I understand and have configured the required environment variables (y/N): ");
            String confirm = console.readLine().strip();

            if (!confirm.equalsIgnoreCase("y")) {
                System.out.println("  Aborting. Set the required environment variables and run 'ato bh init' again.");
                return;
            }

            config.repository = repo;
            config.hostName = "";
            System.out.println("  Mode set to: local (repository: " + repo + ")");
        } else if ("2".equals(choice)) {
            System.out.print("  Blue Hat host name or IP address: ");
            String host = console.readLine().strip();

            if (host.isBlank()) {
                System.err.println("  Error: host name cannot be empty.");
                return;
            }

            config.hostName = host;
            config.repository = "";
            System.out.println("  Mode set to: remote (host: " + host + ")");
        } else {
            System.err.println("  Invalid choice. Please enter 1 or 2.");
        }
    }

    private void setupSshKey(Console console, BlueHatConfig config) {
        System.out.println("\nConfiguring SSH key...");

        if (SshKeyManager.exists(BlueHat.CLOUD)) {
            System.out.println("  Managed SSH key pair already exists.");
        } else {
            try {
                SshKeyManager.ensureKeyPairExists(BlueHat.CLOUD);
            } catch (Exception e) {
                System.err.println("  Warning: SSH key generation failed: " + e.getMessage());
                System.err.println("  You can generate one manually later.");
            }
        }

        if (!config.sshPublicKey.isBlank()) {
            System.out.println("  Personal SSH public key: " + config.sshPublicKey);
        } else if (console != null) {
            String defaultKey = "~/.ssh/id_ed25519.pub";
            System.out.print("  Path to your SSH public key [" + defaultKey + "]: ");
            String input = console.readLine().strip();
            config.sshPublicKey = input.isBlank() ? defaultKey : input;
            System.out.println("  SSH public key set to: " + config.sshPublicKey);
        } else {
            config.sshPublicKey = "~/.ssh/id_ed25519.pub";
            System.out.println("  Defaulting to ~/.ssh/id_ed25519.pub");
        }
    }

    private void setupSearchPaths(Console console) {
        System.out.println("\nTemplate search paths...");
        AttimoConfig.setupSearchPaths(console);
    }

    private void printNextSteps() {
        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato bh request               # Request a VM");
        System.out.println("  ato bh status                # Check VM status");
    }
}
