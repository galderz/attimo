package org.mendrugo.attimo.bluehat.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.bluehat.BlueHat;
import org.mendrugo.attimo.bluehat.BlueHatCloudRunner;
import org.mendrugo.attimo.bluehat.BlueHatConfig;
import org.mendrugo.attimo.bluehat.BlueHatSettings;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.nio.file.Files;

@CommandDefinition(
    name = "init"
    , description = "One-time setup: configure SSH key and build local cloud (if localhost)"
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
        final var hostName = BlueHatSettings.hostName();

        System.out.println("Blue Hat host: " + hostName
            + (BlueHatSettings.isLocal() ? " (local mode)" : " (remote mode)"));
        System.out.println();

        final int totalSteps = BlueHatSettings.isLocal() ? 2 : 1;
        int step = 1;

        setupSshKey(console, config, step, totalSteps);
        step++;

        if (BlueHatSettings.isLocal())
        {
            buildLocalCloud(step, totalSteps);
        }

        config.save();

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato bh request               # Request a VM");
        System.out.println("  ato bh status                # Check VM status");
        return CommandResult.SUCCESS;
    }

    private void setupSshKey(
        final Console console
        , final BlueHatConfig config
        , final int step
        , final int totalSteps
    )
    {
        System.out.println("[" + step + "/" + totalSteps + "] Configuring SSH key...");

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

    private void buildLocalCloud(final int step, final int totalSteps)
    {
        System.out.println("\n[" + step + "/" + totalSteps
            + "] Building local Blue Hat cloud...");
        BlueHatCloudRunner.cloneAndBuild();
    }
}
