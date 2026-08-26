package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.nio.file.Files;

@CommandDefinition(
    name = "init"
    , description = "One-time setup: choose cloud mode, configure SSH key"
    , generateHelp = true
)
public class BlueHatInitCommand extends BaseCommand
{
    /**
     * Check if init has been run by looking for the config file.
     */
    public static boolean hasBeenInitialized()
    {
        return Files.exists(Environment.configFile(BlueHat.CLOUD));
    }

    @Override
    protected CommandResult doExecute() throws Exception
    {
        System.out.println("=== attimo bh init ===\n");

        final var config = BlueHatConfig.load();
        final var console = System.console();

        if (config.hasCloudTarget())
        {
            System.out.println("Current configuration:");
            if (config.isLocal())
            {
                System.out.println("  Mode:       local (git repository)");
                System.out.println("  Repository: " + config.getRepository());
            }
            else
            {
                System.out.println("  Mode:       remote");
                System.out.println("  Host:       " + config.getHostName());
            }
            System.out.println();

            if (console != null)
            {
                System.out.print("  Reconfigure? (y/N): ");
                final var answer = console.readLine().strip();
                if (!answer.equalsIgnoreCase("y"))
                {
                    setupSshKey(console, config);
                    config.save();
                    printNextSteps();
                    return CommandResult.SUCCESS;
                }
            }
        }

        setupCloudMode(console, config);
        setupSshKey(console, config);

        config.save();

        if (config.isLocal())
        {
            System.out.println("\nBuilding local Blue Hat cloud...");
            BlueHatCloudRunner.cloneAndBuild(config.getRepository());
        }

        printNextSteps();
        return CommandResult.SUCCESS;
    }

    private void setupCloudMode(
        final Console console
        , final BlueHatConfig config
    )
    {
        System.out.println("How will you connect to the Blue Hat cloud?\n");
        System.out.println("  1) Git repository — attimo clones, builds, and runs it locally");
        System.out.println("  2) Remote host    — connect to a Blue Hat cloud already running\n");

        if (console == null)
        {
            System.err.println("Error: no console available for interactive setup.");
            System.err.println("Set repository or host-name manually in "
                + Environment.configFile(BlueHat.CLOUD));
            return;
        }

        System.out.print("Choice [1/2]: ");
        final var choice = console.readLine().strip();

        if ("1".equals(choice))
        {
            System.out.print("  Git repository URL: ");
            final var repo = console.readLine().strip();

            if (repo.isBlank())
            {
                System.err.println("  Error: repository URL cannot be empty.");
                return;
            }

            config.setRepository(repo);
            config.setHostName("");
            System.out.println("  Mode set to: local (repository: " + repo + ")");
        }
        else if ("2".equals(choice))
        {
            System.out.print("  Blue Hat host name or IP address: ");
            final var host = console.readLine().strip();

            if (host.isBlank())
            {
                System.err.println("  Error: host name cannot be empty.");
                return;
            }

            config.setHostName(host);
            config.setRepository("");
            System.out.println("  Mode set to: remote (host: " + host + ")");
        }
        else
        {
            System.err.println("  Invalid choice. Please enter 1 or 2.");
        }
    }

    private void setupSshKey(
        final Console console
        , final BlueHatConfig config
    )
    {
        System.out.println("\nConfiguring SSH key...");

        // Generate managed key pair
        if (SshKeyManager.exists(BlueHat.CLOUD))
        {
            System.out.println("  Managed SSH key pair already exists.");
        }
        else
        {
            try
            {
                SshKeyManager.ensureKeyPairExists(BlueHat.CLOUD);
            }
            catch (final Exception e)
            {
                System.err.println("  Warning: SSH key generation failed: " + e.getMessage());
                System.err.println("  You can generate one manually later.");
            }
        }

        // Ask for personal SSH public key path
        if (!config.getSshPublicKey().isBlank())
        {
            System.out.println("  Personal SSH public key: " + config.getSshPublicKey());
        }
        else if (console != null)
        {
            final var defaultKey = "~/.ssh/id_ed25519.pub";
            System.out.print("  Path to your SSH public key [" + defaultKey + "]: ");
            final var input = console.readLine().strip();
            config.setSshPublicKey(input.isBlank() ? defaultKey : input);
            System.out.println("  SSH public key set to: " + config.getSshPublicKey());
        }
        else
        {
            config.setSshPublicKey("~/.ssh/id_ed25519.pub");
            System.out.println("  Defaulting to ~/.ssh/id_ed25519.pub");
        }
    }

    private void printNextSteps()
    {
        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato bh request               # Request a VM");
        System.out.println("  ato bh status                # Check VM status");
    }
}
