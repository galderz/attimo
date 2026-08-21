package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.nio.file.Files;

@CommandDefinition(
    name = "init"
    , description = "One-time setup: configure Blue Hat host and SSH key"
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

        setupHostName(console, config);
        setupSshKey(console, config);

        config.save();

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato bh request               # Request a VM");
        System.out.println("  ato bh status                # Check VM status");
        return CommandResult.SUCCESS;
    }

    private void setupHostName(
        final Console console
        , final BlueHatConfig config
    )
    {
        System.out.println("[1/2] Configuring Blue Hat host...");

        if (!config.getHostName().isBlank())
        {
            System.out.println("  Current host: " + config.getHostName());
            if (console != null)
            {
                System.out.print("  Keep this host? (Y/n): ");
                final var answer = console.readLine().strip();
                if (!answer.equalsIgnoreCase("n"))
                {
                    return;
                }
            }
            else
            {
                return;
            }
        }

        if (console == null)
        {
            System.err.println("  Error: no console available for interactive setup.");
            System.err.println("  Set the host-name manually in "
                + Environment.configFile(BlueHat.CLOUD));
            return;
        }

        System.out.print("  Blue Hat host name or IP address: ");
        final var hostName = console.readLine().strip();

        if (hostName.isBlank())
        {
            System.err.println("  Error: host name cannot be empty.");
            return;
        }

        config.setHostName(hostName);
        System.out.println("  Host set to: " + hostName);
    }

    private void setupSshKey(
        final Console console
        , final BlueHatConfig config
    )
    {
        System.out.println("\n[2/2] Configuring SSH key...");

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
}
