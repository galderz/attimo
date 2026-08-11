package org.mendrugo.attimo.ssh;

import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/**
 * Manages a dedicated SSH key pair for cloud instance access.
 * Key pair at ~/.config/attimo/{cloud}/ssh/id_ed25519.
 * Borrowed from incus-spawn's SshKeyManager pattern.
 */
public final class SshKeyManager
{
    private SshKeyManager() {}

    public static boolean exists(final String cloud)
    {
        return Files.exists(Environment.sshKeyFile(cloud))
            && Files.exists(Environment.sshPubKeyFile(cloud));
    }

    /**
     * Generate an ed25519 key pair if one does not already exist.
     */
    public static void ensureKeyPairExists(final String cloud)
    {
        if (exists(cloud))
        {
            return;
        }

        try
        {
            Files.createDirectories(Environment.sshDir(cloud));

            final var pb = new ProcessBuilder(
                "ssh-keygen"
                , "-t", "ed25519"
                , "-f", Environment.sshKeyFile(cloud).toString()
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
                Environment.sshKeyFile(cloud)
                , PosixFilePermissions.fromString("rw-------")
            );
            Files.setPosixFilePermissions(
                Environment.sshPubKeyFile(cloud)
                , PosixFilePermissions.fromString("rw-r--r--")
            );

            System.out.println("  SSH key pair generated at " + Environment.sshDir(cloud));
        }
        catch (final IOException | InterruptedException e)
        {
            throw new RuntimeException("Failed to generate SSH key pair: " + e.getMessage(), e);
        }
    }

    /**
     * Read the managed public key content.
     */
    public static String publicKeyContent(final String cloud)
    {
        try
        {
            return Files.readString(Environment.sshPubKeyFile(cloud)).strip();
        }
        catch (final IOException e)
        {
            throw new RuntimeException("Failed to read SSH public key: " + e.getMessage(), e);
        }
    }
}
