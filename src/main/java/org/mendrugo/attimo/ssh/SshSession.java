package org.mendrugo.attimo.ssh;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.mendrugo.attimo.Environment;

public class SshSession {
    private static final int SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int RETRY_DELAY_MS = 5000;
    private static final String DEFAULT_USER = "ec2-user";

    private final String host;
    private final String user;
    private final Path privateKeyPath;

    public SshSession(String host, String user, Path privateKeyPath) {
        this.host = host;
        this.user = user;
        this.privateKeyPath = privateKeyPath;
    }

    public SshSession(String host, String cloud) {
        this(host, DEFAULT_USER, Environment.sshKeyFile(cloud));
    }

    public boolean waitForSsh(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        System.out.println("  Waiting for SSH on " + host + ":" + SSH_PORT + "...");

        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, SSH_PORT), CONNECT_TIMEOUT_MS);
                System.out.println("  SSH is reachable.");
                return true;
            } catch (IOException e) {
            }

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        System.err.println("  Timed out waiting for SSH after " + timeoutSeconds + " seconds.");
        return false;
    }

    public int connect() {
        List<String> command = buildSshCommand();

        try {
            System.out.println("  Connecting to " + user + "@" + host + "...\n");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("SSH connection failed: " + e.getMessage());
            return 1;
        }
    }

    List<String> buildSshCommand() {
        ArrayList<String> cmd = new ArrayList<String>();
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
