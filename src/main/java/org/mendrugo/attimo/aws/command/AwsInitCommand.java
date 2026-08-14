package org.mendrugo.attimo.aws.command;

import org.mendrugo.attimo.Environment;
import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.aws.AwsClientFactory;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.aws.Continent;
import org.mendrugo.attimo.aws.Region;
import org.mendrugo.attimo.config.AttimoConfig;
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
public class AwsInitCommand extends BaseCommand
{
    /**
     * Check if init has been run by looking for the config file.
     */
    public static boolean hasBeenInitialized()
    {
        return Files.exists(Environment.configFile(Aws.CLOUD));
    }

    @Override
    protected CommandResult doExecute() throws Exception
    {
        System.out.println("=== attimo aws init ===\n");

        final var config = AttimoConfig.load(Aws.CLOUD);
        final var console = System.console();

        if (!checkAwsCredentials(console, config))
        {
            return CommandResult.valueOf(1);
        }

        setupRegion(console, config);
        setupSshKey(console, config);

        config.save(Aws.CLOUD);

        System.out.println("\n=== Init complete! ===");
        System.out.println("Next steps:");
        System.out.println("  ato aws request --isa avx512    # Request a spot instance");
        System.out.println("  ato aws status                  # Check instance status");
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

        // Detect login_session issue — may indicate expired session or missing signin module
        if (error.contains("login_session"))
        {
            System.out.println();
            System.out.println("  Your profile uses 'login_session' (from 'aws login').");
            System.out.println("  This may mean your session has expired. Try:");
            System.out.println("    aws login           # refresh your session");
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
            System.err.println("  Configure AWS credentials manually and re-run 'ato aws init'.");
            return false;
        }

        System.out.print("  Choose (a/B): ");
        final var choice = console.readLine().strip();

        if (choice.equalsIgnoreCase("a"))
        {
            System.out.println("  Install the AWS CLI, run 'aws configure', then re-run 'ato aws init'.");
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
            System.out.println("  Skipped. Configure AWS credentials and re-run 'ato aws init'.");
            return false;
        }

        System.out.print("  AWS Secret Access Key: ");
        final var secretKey = new String(console.readPassword()).strip();
        if (secretKey.isBlank())
        {
            System.out.println("  Skipped. Configure AWS credentials and re-run 'ato aws init'.");
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

        // Step 1: Select continent
        final var continent = selectContinent(console);
        config.setContinent(continent.name());

        // Step 2: Select region within continent
        final var region = selectRegion(console, continent);
        config.setPreferredRegion(region);
    }

    private Continent selectContinent(final Console console)
    {
        System.out.println("  Select your continent:");
        System.out.println("    1. " + Continent.EMEA.displayName());
        System.out.println("    2. " + Continent.AMERICAS.displayName());
        System.out.println("    3. " + Continent.ASIA_PACIFIC.displayName());
        System.out.println();

        if (console == null)
        {
            System.out.println("  Defaulting to EMEA (no console available).");
            return Continent.EMEA;
        }

        System.out.print("  Continent [1]: ");
        final var input = console.readLine().strip();

        return switch (input)
        {
            case "2" -> Continent.AMERICAS;
            case "3" -> Continent.ASIA_PACIFIC;
            default -> Continent.EMEA;
        };
    }

    private String selectRegion(
        final Console console
        , final Continent continent
    )
    {
        System.out.println();
        System.out.println("  Regions in " + continent.displayName() + ":");

        final var regions = continent.regions();
        for (final Region r : regions)
        {
            final var padding = " ".repeat(Math.max(1, 17 - r.code().length()));
            System.out.println("    " + r.code() + padding + "(" + r.description() + ")");
        }

        System.out.println();

        final var defaultRegion = regions.getFirst().code();

        if (console == null)
        {
            System.out.println("  Defaulting to " + defaultRegion + " (no console available).");
            return defaultRegion;
        }

        System.out.print("  Region [" + defaultRegion + "]: ");
        final var input = console.readLine().strip();
        final var region = input.isBlank() ? defaultRegion : input;

        if (!Region.isKnown(region))
        {
            System.out.println("  Warning: '" + region + "' is not a recognized region. Saving anyway.");
        }
        else
        {
            final var actualContinent = Continent.forRegion(region);
            if (actualContinent != continent)
            {
                System.out.println("  Note: '" + region + "' is in " + actualContinent.displayName()
                    + ", not " + continent.displayName() + ". Using " + actualContinent.displayName()
                    + " as your continent.");
            }
        }

        System.out.println("  Region set to: " + region);
        return region;
    }

    private void setupSshKey(
        final Console console
        , final AttimoConfig config
    )
    {
        System.out.println("\n[3/3] Configuring SSH key...");

        // Generate managed key pair
        if (SshKeyManager.exists(Aws.CLOUD))
        {
            System.out.println("  Managed SSH key pair already exists.");
        }
        else
        {
            try
            {
                SshKeyManager.ensureKeyPairExists(Aws.CLOUD);
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
