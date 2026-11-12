package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyManagerTest {
    private static final String TEST_CLOUD = "aws";

    @Test
    @EnabledOnOs(OS.LINUX)
    void ensureKeyPairCreatesKeys() {

        if (SshKeyManager.exists(TEST_CLOUD)) {

            final String pubKey = SshKeyManager.publicKeyContent(TEST_CLOUD);
            assertThat(pubKey).isNotBlank();
            assertThat(pubKey).contains("ssh-ed25519");
        }
    }
}
