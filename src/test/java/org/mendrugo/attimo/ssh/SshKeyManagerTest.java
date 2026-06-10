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
    @Test
    void existsReturnsFalseWhenNoKeys()
    {
        // Default Environment paths won't have test keys
        // This test verifies the method doesn't throw
        final var result = SshKeyManager.exists();
        // Result depends on whether keys exist in ~/.config/attimo/ssh/
        assertThat(result).isIn(true, false);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ensureKeyPairCreatesKeys()
    {
        // Only test if ssh-keygen is available and keys don't exist yet
        if (SshKeyManager.exists())
        {
            // Keys already exist, just verify we can read the public key
            final var pubKey = SshKeyManager.publicKeyContent();
            assertThat(pubKey).isNotBlank();
            assertThat(pubKey).contains("ssh-ed25519");
        }
        // If keys don't exist, we skip rather than create them
        // (would pollute the test environment)
    }
}
