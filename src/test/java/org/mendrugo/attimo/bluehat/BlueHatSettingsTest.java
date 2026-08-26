package org.mendrugo.attimo.bluehat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BlueHatSettings reading from Quarkus/MicroProfile Config.
 * These tests verify the default values from application.properties.
 */
class BlueHatSettingsTest
{
    @Test
    void defaultHostNameIsLocalhost()
    {
        // The default from application.properties is "localhost"
        assertThat(BlueHatSettings.hostName()).isEqualTo("localhost");
    }

    @Test
    void defaultIsLocal()
    {
        assertThat(BlueHatSettings.isLocal()).isTrue();
    }

    @Test
    void repositoryIsConfigured()
    {
        assertThat(BlueHatSettings.repository()).isNotBlank();
    }
}
