package org.mendrugo.attimo.ssh;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SshSessionTest {
    @Test
    void buildsSshCommandWithCorrectOptions() {
        SshSession session = new SshSession("3.10.45.67", "fedora", Path.of("/home/user/.config/attimo/ssh/id_ed25519"));

        List<String> cmd = session.buildSshCommand();

        assertThat(cmd).contains("ssh");
        assertThat(cmd).contains("-i", "/home/user/.config/attimo/ssh/id_ed25519");
        assertThat(cmd).contains("StrictHostKeyChecking=no");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
        assertThat(cmd).contains("ServerAliveInterval=30");
        assertThat(cmd).contains("fedora@3.10.45.67");
    }

    @Test
    void defaultUserIsEc2User() {
        SshSession session = new SshSession("1.2.3.4", "aws");
        List<String> cmd = session.buildSshCommand();

        assertThat(cmd.getLast()).startsWith("ec2-user@");
    }

    @Test
    void suppressesHostKeyWarnings() {
        SshSession session = new SshSession("1.2.3.4", "aws");
        List<String> cmd = session.buildSshCommand();

        assertThat(cmd).contains("LogLevel=ERROR");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
    }

    @Test
    void setsServerAliveForConnectionMonitoring() {
        SshSession session = new SshSession("1.2.3.4", "aws");
        List<String> cmd = session.buildSshCommand();

        assertThat(cmd).contains("ServerAliveInterval=30");
        assertThat(cmd).contains("ServerAliveCountMax=3");
    }
}
