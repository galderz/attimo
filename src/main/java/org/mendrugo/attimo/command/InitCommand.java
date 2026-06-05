package org.mendrugo.attimo.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.config.AttimoConfig;
import org.mendrugo.attimo.config.RegionGroup;
import org.mendrugo.attimo.ssh.SshKeyManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandDefinition(
    name = "init"
    , description = "One-time setup: validate AWS auth, set region, configure SSH key"
    , generateHelp = true
)
public class InitCommand extends BaseCommand
{
    /**
     * Check if init has been run by looking for the config file.
     */
    public static boolean hasBeenInitialized()
    {
        return Files.exists(Environment.configFile());
    }

    @Override
    protected CommandResult doExecute() throws Exception
    {
        System.out.println("=== attimo init ===\n");

        final var config = AttimoConfig.load();
        final var console = System.console();

        if (!checkAwsCredentials(console, config))
        {
            return CommandResult.valueOf(1);
        }

        setupRegion(console, config);
        setupSshKey(console, config);

        config.save();

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato request --isa avx512    # Request a spot instance");
        System.out.println("  ato status                  # Check instance status");
        return CommandResult.SUCCESS;
    }

    private boolean checkAwsCredentials(
        final Console console
        , final AttimoConfig config
    )
    {
        System.out.println("[1/3] Checking AWS authentication...");
        final var factory = new AwsClientFactory();
        final var error = factory.validateCredentials();

        if (error == null)
        {
            System.out.println("  AWS credentials verified.");
            return true;
        }

        System.out.println("  " + error);

        // Detect common misconfiguration: 'aws login' creates a format the SDK can't use
        if (error.contains("login_session"))
        {
            System.out.println();
            System.out.println("  It looks like you used 'aws login' which creates a config format");
            System.out.println("  that the AWS Java SDK cannot read.");
            System.out.println();
            System.out.println("  To fix this, run one of:");
            System.out.println("    aws configure       # add access key credentials");
            System.out.println("    aws configure sso   # set up SSO with fields the SDK understands");
            System.out.println();
            System.out.println("  Your existing ~/.aws/config will be preserved.");
            System.out.println();
        }

        System.out.println();
        System.out.println("  No AWS credentials found. Choose an option:");
        System.out.println();
        System.out.println("  Option A: Install the AWS CLI and configure credentials");
        System.out.println("    Fedora:    sudo dnf install awscli2");
        System.out.println("    Ubuntu:    sudo apt install awscli");
        System.out.println("    macOS:     brew install awscli");
        System.out.println("    Nix:       nix-env -iA nixpkgs.awscli2");
        System.out.println("    Other:     https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html");
        System.out.println();
        System.out.println("    Then authenticate with one of:");
        System.out.println("      aws configure           # access key + secret (personal accounts)");
        System.out.println("      aws sso login            # SSO / Identity Center (organizations)");
        System.out.println();
        System.out.println("  Option B: Enter your AWS access key now");
        System.out.println("    (Get one from https://console.aws.amazon.com/iam → Security credentials → Access keys)");
        System.out.println();

        if (console == null)
        {
            System.err.println("  Error: no console available for interactive setup.");
            System.err.println("  Configure AWS credentials manually and re-run 'ato init'.");
            return false;
        }

        System.out.print("  Choose (a/B): ");
        final var choice = console.readLine().strip();

        if (choice.equalsIgnoreCase("a"))
        {
            System.out.println("  Install the AWS CLI, run 'aws configure', then re-run 'ato init'.");
            return false;
        }

        return enterAccessKeys(console);
    }

    private boolean enterAccessKeys(final Console console)
    {
        System.out.print("  AWS Access Key ID: ");
        final var accessKey = console.readLine().strip();
        if (accessKey.isBlank())
        {
            System.out.println("  Skipped. Configure AWS credentials and re-run 'ato init'.");
            return false;
        }

        System.out.print("  AWS Secret Access Key: ");
        final var secretKey = new String(console.readPassword()).strip();
        if (secretKey.isBlank())
        {
            System.out.println("  Skipped. Configure AWS credentials and re-run 'ato init'.");
            return false;
        }

        // Write to ~/.aws/credentials
        try
        {
            final Path awsDir = Path.of(System.getProperty("user.home"), ".aws");
            Files.createDirectories(awsDir);
            final Path credFile = awsDir.resolve("credentials");

            final var content = """
                [default]
                aws_access_key_id = %s
                aws_secret_access_key = %s
                """.formatted(accessKey, secretKey);

            Files.writeString(credFile, content);
            credFile.toFile().setReadable(false, false);
            credFile.toFile().setReadable(true, true);
            credFile.toFile().setWritable(false, false);
            credFile.toFile().setWritable(true, true);
            System.out.println("  Credentials saved to ~/.aws/credentials");

            // Verify
            final var factory = new AwsClientFactory();
            final var verifyError = factory.validateCredentials();
            if (verifyError != null)
            {
                System.out.println("  Warning: " + verifyError);
                System.out.println("  Credentials saved but could not be verified. Continuing...");
            }
            else
            {
                System.out.println("  AWS credentials verified.");
            }

            return true;
        }
        catch (final IOException e)
        {
            System.err.println("  Failed to write credentials: " + e.getMessage());
            return false;
        }
    }

    private void setupRegion(
        final Console console
        , final AttimoConfig config
    )
    {
        System.out.println("\n[2/3] Configuring preferred AWS region...");

        if (!config.getPreferredRegion().isBlank())
        {
            System.out.println("  Current region: " + config.getPreferredRegion());
            if (console != null)
            {
                System.out.print("  Keep this region? (Y/n): ");
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

        System.out.println("  Choose the AWS region closest to you.");
        System.out.println("  attimo will also check nearby regions for better spot prices.");
        System.out.println();
        System.out.println("  Common regions:");
        System.out.println("    eu-west-1      (Ireland)");
        System.out.println("    eu-central-1   (Frankfurt)");
        System.out.println("    us-east-1      (N. Virginia)");
        System.out.println("    us-west-2      (Oregon)");
        System.out.println("    ap-northeast-1 (Tokyo)");
        System.out.println();

        if (console != null)
        {
            System.out.print("  Region [us-east-1]: ");
            final var input = console.readLine().strip();
            final var region = input.isBlank() ? "us-east-1" : input;

            if (!RegionGroup.isKnown(region))
            {
                System.out.println("  Warning: '" + region + "' is not a recognized region. Saving anyway.");
            }

            config.setPreferredRegion(region);
            System.out.println("  Region set to: " + region);
        }
        else
        {
            config.setPreferredRegion("us-east-1");
            System.out.println("  Defaulting to us-east-1 (no console available).");
        }
    }

    private void setupSshKey(
        final Console console
        , final AttimoConfig config
    )
    {
        System.out.println("\n[3/3] Configuring SSH key...");

        // Generate managed key pair
        if (SshKeyManager.exists())
        {
            System.out.println("  Managed SSH key pair already exists.");
        }
        else
        {
            try
            {
                SshKeyManager.ensureKeyPairExists();
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
