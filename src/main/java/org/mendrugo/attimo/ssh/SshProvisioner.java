package org.mendrugo.attimo.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.mendrugo.attimo.Environment;

public class SshProvisioner {
    private final String host;
    private final String user;
    private final Path privateKeyPath;

    public SshProvisioner(String host, String user, Path privateKeyPath) {
        this.host = host;
        this.user = user;
        this.privateKeyPath = privateKeyPath;
    }

    public SshProvisioner(String host, String cloud) {
        this(host, "ec2-user", Environment.sshKeyFile(cloud));
    }

    public int run(String command) {
        return run(command, 600);
    }

    public int run(String command, int timeoutSeconds) {
        final List<String> sshCmd = List.of(
                "ssh",
                "-i",
                privateKeyPath.toString(),
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                "-o",
                "LogLevel=ERROR",
                user + "@" + host,
                command);

        try {
            final ProcessBuilder pb = new ProcessBuilder(sshCmd);
            pb.inheritIO();
            final Process process = pb.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("  Command timed out after " + timeoutSeconds + "s: " + command);
                return 1;
            }

            return process.exitValue();
        } catch (IOException | InterruptedException e) {
            System.err.println("  SSH command failed: " + e.getMessage());
            return 1;
        }
    }

    public boolean installPackages(List<String> packages) {
        if (packages.isEmpty()) {
            return true;
        }

        System.out.println("  Installing " + packages.size() + " packages...");
        final String pkgList = String.join(" ", packages);
        final int exitCode = run("sudo dnf install -y " + pkgList, 900);

        if (exitCode != 0) {
            System.err.println("  Package installation failed (exit code " + exitCode + ")");
            return false;
        }

        System.out.println("  Packages installed successfully.");
        return true;
    }
}
