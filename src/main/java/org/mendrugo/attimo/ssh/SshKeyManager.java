package org.mendrugo.attimo.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.mendrugo.attimo.Environment;

public final class SshKeyManager {
    private SshKeyManager() {}

    public static boolean exists(String cloud) {
        return Files.exists(Environment.sshKeyFile(cloud)) && Files.exists(Environment.sshPubKeyFile(cloud));
    }

    public static void ensureKeyPairExists(String cloud) {
        if (exists(cloud)) {
            return;
        }

        try {
            Files.createDirectories(Environment.sshDir(cloud));

            ProcessBuilder pb = new ProcessBuilder(
                    "ssh-keygen",
                    "-t",
                    "ed25519",
                    "-f",
                    Environment.sshKeyFile(cloud).toString(),
                    "-N",
                    "",
                    "-C",
                    "attimo managed key");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("ssh-keygen timed out");
            }

            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                throw new RuntimeException("ssh-keygen failed: " + output);
            }

            Files.setPosixFilePermissions(Environment.sshKeyFile(cloud), PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(Environment.sshPubKeyFile(cloud), PosixFilePermissions.fromString("rw-r--r--"));

            System.out.println("  SSH key pair generated at " + Environment.sshDir(cloud));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to generate SSH key pair: " + e.getMessage(), e);
        }
    }

    public static String publicKeyContent(String cloud) {
        try {
            return Files.readString(Environment.sshPubKeyFile(cloud)).strip();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SSH public key: " + e.getMessage(), e);
        }
    }
}
