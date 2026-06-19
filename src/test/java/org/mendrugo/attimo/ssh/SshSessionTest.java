package org.mendrugo.attimo.ssh;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SshSessionTest
{
    @Test
    void buildsSshCommandWithCorrectOptions()
    {
        final var session = new SshSession(
            "3.10.45.67"
            , "fedora"
            , Path.of("/home/user/.config/attimo/ssh/id_ed25519")
        );

        final var cmd = session.buildSshCommand();

        assertThat(cmd).contains("ssh");
        assertThat(cmd).contains("-i", "/home/user/.config/attimo/ssh/id_ed25519");
        assertThat(cmd).contains("StrictHostKeyChecking=no");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
        assertThat(cmd).contains("ServerAliveInterval=30");
        assertThat(cmd).contains("fedora@3.10.45.67");
    }

    @Test
    void defaultUserIsEc2User()
    {
        final var session = new SshSession("1.2.3.4");
        final var cmd = session.buildSshCommand();

        assertThat(cmd.getLast()).startsWith("ec2-user@");
    }

    @Test
    void suppressesHostKeyWarnings()
    {
        final var session = new SshSession("1.2.3.4");
        final var cmd = session.buildSshCommand();

        assertThat(cmd).contains("LogLevel=ERROR");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
    }

    @Test
    void setsServerAliveForConnectionMonitoring()
    {
        final var session = new SshSession("1.2.3.4");
        final var cmd = session.buildSshCommand();

        assertThat(cmd).contains("ServerAliveInterval=30");
        assertThat(cmd).contains("ServerAliveCountMax=3");
    }
}
