package org.mendrugo.attimo.ssh;

import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/**
 * Manages a dedicated SSH key pair for spot instance access.
 * Key pair at ~/.config/attimo/ssh/id_ed25519.
 * Borrowed from incus-spawn's SshKeyManager pattern.
 */
public final class SshKeyManager
{
    private SshKeyManager() {}

    public static boolean exists()
    {
        return Files.exists(Environment.sshKeyFile())
            && Files.exists(Environment.sshPubKeyFile());
    }

    /**
     * Generate an ed25519 key pair if one does not already exist.
     */
    public static void ensureKeyPairExists()
    {
        if (exists())
        {
            return;
        }

        try
        {
            Files.createDirectories(Environment.sshDir());

            final var pb = new ProcessBuilder(
                "ssh-keygen"
                , "-t", "ed25519"
                , "-f", Environment.sshKeyFile().toString()
                , "-N", ""
                , "-C", "attimo managed key"
            );
            pb.redirectErrorStream(true);
            final var process = pb.start();

            if (!process.waitFor(30, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                throw new RuntimeException("ssh-keygen timed out");
            }

            final var output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0)
            {
                throw new RuntimeException("ssh-keygen failed: " + output);
            }

            Files.setPosixFilePermissions(
                Environment.sshKeyFile()
                , PosixFilePermissions.fromString("rw-------")
            );
            Files.setPosixFilePermissions(
                Environment.sshPubKeyFile()
                , PosixFilePermissions.fromString("rw-r--r--")
            );

            System.out.println("  SSH key pair generated at " + Environment.sshDir());
        }
        catch (final IOException | InterruptedException e)
        {
            throw new RuntimeException("Failed to generate SSH key pair: " + e.getMessage(), e);
        }
    }

    /**
     * Read the managed public key content.
     */
    public static String publicKeyContent()
    {
        try
        {
            return Files.readString(Environment.sshPubKeyFile()).strip();
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to read SSH public key: " + e.getMessage(), e);
        }
    }
}
