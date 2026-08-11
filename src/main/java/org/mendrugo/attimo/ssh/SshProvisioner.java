package org.mendrugo.attimo.ssh;

import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs provisioning commands over SSH on a remote instance.
 * Used during AMI build and direct provisioning.
 */
public class SshProvisioner
{
    private final String host;
    private final String user;
    private final Path privateKeyPath;

    public SshProvisioner(
        final String host
        , final String user
        , final Path privateKeyPath
    )
    {
        this.host = host;
        this.user = user;
        this.privateKeyPath = privateKeyPath;
    }

    public SshProvisioner(
        final String host
        , final String cloud
    )
    {
        this(host, "ec2-user", Environment.sshKeyFile(cloud));
    }

    /**
     * Run a command over SSH. Streams output to stdout/stderr.
     *
     * @param command the command to run
     * @return the exit code
     */
    public int run(final String command)
    {
        return run(command, 600); // 10 minute default timeout
    }

    /**
     * Run a command over SSH with a timeout.
     *
     * @param command the command to run
     * @param timeoutSeconds maximum time to wait
     * @return the exit code
     */
    public int run(final String command, final int timeoutSeconds)
    {
        final var sshCmd = List.of(
            "ssh"
            , "-i", privateKeyPath.toString()
            , "-o", "StrictHostKeyChecking=no"
            , "-o", "UserKnownHostsFile=/dev/null"
            , "-o", "LogLevel=ERROR"
            , user + "@" + host
            , command
        );

        try
        {
            final var pb = new ProcessBuilder(sshCmd);
            pb.inheritIO();
            final var process = pb.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                System.err.println("  Command timed out after " + timeoutSeconds + "s: " + command);
                return 1;
            }

            return process.exitValue();
        }
        catch (final IOException | InterruptedException e)
        {
            System.err.println("  SSH command failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Install packages via dnf.
     *
     * @param packages list of package names
     * @return true if installation succeeded
     */
    public boolean installPackages(final List<String> packages)
    {
        if (packages.isEmpty())
        {
            return true;
        }

        System.out.println("  Installing " + packages.size() + " packages...");
        final var pkgList = String.join(" ", packages);
        final var exitCode = run("sudo dnf install -y " + pkgList, 900);

        if (exitCode != 0)
        {
            System.err.println("  Package installation failed (exit code " + exitCode + ")");
            return false;
        }

        System.out.println("  Packages installed successfully.");
        return true;
    }
}
