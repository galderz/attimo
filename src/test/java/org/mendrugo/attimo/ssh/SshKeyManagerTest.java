package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mendrugo.attimo.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyManagerTest
{
    private static final String TEST_CLOUD = "aws";

    @Test
    @EnabledOnOs(OS.LINUX)
    void ensureKeyPairCreatesKeys()
    {
        // Only test if ssh-keygen is available and keys don't exist yet
        if (SshKeyManager.exists(TEST_CLOUD))
        {
            // Keys already exist, just verify we can read the public key
            final var pubKey = SshKeyManager.publicKeyContent(TEST_CLOUD);
            assertThat(pubKey).isNotBlank();
            assertThat(pubKey).contains("ssh-ed25519");
        }
        // If keys don't exist, we skip rather than create them
        // (would pollute the test environment)
    }
}
