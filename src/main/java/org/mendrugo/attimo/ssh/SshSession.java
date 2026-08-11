package org.mendrugo.attimo.ssh;

import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages an SSH session to a spot instance via the system ssh command.
 * Waits for SSH to be reachable, then launches an interactive session.
 */
public class SshSession
{
    private static final int SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int RETRY_DELAY_MS = 5000;
    private static final String DEFAULT_USER = "ec2-user";

    private final String host;
    private final String user;
    private final Path privateKeyPath;

    public SshSession(
        final String host
        , final String user
        , final Path privateKeyPath
    )
    {
        this.host = host;
        this.user = user;
        this.privateKeyPath = privateKeyPath;
    }

    /**
     * Create an SSH session using the default user and the cloud provider's
     * managed SSH key.
     *
     * @param host the host to connect to
     * @param cloud the cloud provider identifier for SSH key path
     */
    public SshSession(
        final String host
        , final String cloud
    )
    {
        this(host, DEFAULT_USER, Environment.sshKeyFile(cloud));
    }

    /**
     * Wait until SSH is reachable on the remote host.
     *
     * @param timeoutSeconds maximum time to wait
     * @return true if SSH is reachable, false if timed out
     */
    public boolean waitForSsh(final int timeoutSeconds)
    {
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        System.out.println("  Waiting for SSH on " + host + ":" + SSH_PORT + "...");

        while (System.currentTimeMillis() < deadline)
        {
            try (final var socket = new Socket())
            {
                socket.connect(new InetSocketAddress(host, SSH_PORT), CONNECT_TIMEOUT_MS);
                System.out.println("  SSH is reachable.");
                return true;
            }
            catch (final IOException e)
            {
                // Not ready yet
            }

            try
            {
                Thread.sleep(RETRY_DELAY_MS);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        System.err.println("  Timed out waiting for SSH after " + timeoutSeconds + " seconds.");
        return false;
    }

    /**
     * Launch an interactive SSH session. Blocks until the session ends.
     *
     * @return the exit code of the ssh process
     */
    public int connect()
    {
        final var command = buildSshCommand();

        try
        {
            System.out.println("  Connecting to " + user + "@" + host + "...\n");
            final var pb = new ProcessBuilder(command);
            pb.inheritIO();
            final var process = pb.start();
            return process.waitFor();
        }
        catch (final IOException | InterruptedException e)
        {
            System.err.println("SSH connection failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Build the ssh command with appropriate options.
     * Visible for testing.
     */
    List<String> buildSshCommand()
    {
        final var cmd = new ArrayList<String>();
        cmd.add("ssh");
        cmd.add("-i");
        cmd.add(privateKeyPath.toString());
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o");
        cmd.add("LogLevel=ERROR");
        cmd.add("-o");
        cmd.add("ServerAliveInterval=30");
        cmd.add("-o");
        cmd.add("ServerAliveCountMax=3");
        cmd.add(user + "@" + host);
        return cmd;
    }
}
