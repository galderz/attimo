package org.mendrugo.attimo.bluehat;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mendrugo.attimo.bluehat.BlueHatClient.VmRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueHatIT {
    private BlueHatDummyServer server;
    private BlueHatClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new BlueHatDummyServer();
        server.start();
        client = new BlueHatClient("localhost", server.port());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void fullLifecycle_requestListDestroy() {

        final var request = new VmRequest("16", "32", "RedHat 10.2", "attimo integration test VM", "ssh-ed25519 AAAA... attimo managed key");

        final var createResponse = client.requestVm(request);
        assertThat(createResponse.fqdn())
                .isNotBlank()
                .contains("bluehat-vm-")
                .contains(".dev.acme.com");

        final var fqdn = createResponse.fqdn();

        final var vms = client.listVms();
        assertThat(vms).isNotEmpty();

        final var ourVm = vms.stream()
                             .filter(vm -> fqdn.equals(vm.fqdn()))
                             .findFirst();
        assertThat(ourVm).isPresent();
        assertThat(ourVm.get().state()).isEqualTo("running");
        assertThat(ourVm.get().vmId()).isNotBlank();
        assertThat(ourVm.get().description()).isEqualTo("attimo integration test VM");
        assertThat(ourVm.get().createdIso8601()).isNotBlank();

        final var destroyResponse = client.destroyVm(fqdn);
        assertThat(destroyResponse.status()).isEqualTo("success");
        assertThat(destroyResponse.details()).isEqualTo("vm deletion initiated");
        assertThat(destroyResponse.requestId()).isNotBlank();

        final var vmsAfterDestroy = client.listVms();
        final var deletedVm = vmsAfterDestroy.stream()
                .filter(vm -> fqdn.equals(vm.fqdn()))
                .findFirst();
        assertThat(deletedVm).isPresent();
        assertThat(deletedVm.get().state()).isEqualTo("deleted");
    }

    @Test
    void requestMultipleVms() {

        final var vm1 = client.requestVm(new VmRequest("1", "2", "RedHat 10.2", "micro vm", "key1"));
        final var vm2 = client.requestVm(new VmRequest("8", "16", "RedHat 10.2", "small vm", "key2"));
        final var vm3 = client.requestVm(new VmRequest("32", "64", "RedHat 10.2", "large vm", "key3"));

        assertThat(vm1.fqdn()).isNotEqualTo(vm2.fqdn());
        assertThat(vm2.fqdn()).isNotEqualTo(vm3.fqdn());
        assertThat(vm1.fqdn()).isNotEqualTo(vm3.fqdn());

        final var vms = client.listVms();
        assertThat(vms).hasSize(3);
        assertThat(vms.stream().allMatch(vm -> "running".equals(vm.state()))).isTrue();
    }

    @Test
    void destroyNonExistentVmThrows() {
        assertThatThrownBy(() -> client.destroyVm("nonexistent.dev.acme.com"))
                .isInstanceOf(BlueHatException.class)
                .hasMessageContaining("VM destroy failed (HTTP 404)");
    }

    @Test
    void listVmsWhenNoneExist() {
        final var vms = client.listVms();
        assertThat(vms).isEmpty();
    }

    @Test
    void requestVmPreservesDescription() {
        final var description = "attimo VM created at 2026-08-14 10:30:45";
        final var response = client.requestVm(new VmRequest("16", "32", "RedHat 10.2", description, "ssh-key"));

        final var vms = client.listVms();
        final var vm = vms.stream()
                          .filter(v -> response.fqdn().equals(v.fqdn()))
                          .findFirst();

        assertThat(vm).isPresent();
        assertThat(vm.get().description()).isEqualTo(description);
    }

    @Test
    void destroyVmDoesNotAffectOtherVms() {
        final var vm1 = client.requestVm(new VmRequest("1", "2", "RedHat 10.2", "vm1", "key"));
        final var vm2 = client.requestVm(new VmRequest("8", "16", "RedHat 10.2", "vm2", "key"));

        client.destroyVm(vm1.fqdn());

        final var vms = client.listVms();
        final var remainingVm = vms.stream()
                .filter(vm -> vm2.fqdn().equals(vm.fqdn()))
                .findFirst();

        assertThat(remainingVm).isPresent();
        assertThat(remainingVm.get().state()).isEqualTo("running");

        final var destroyedVm = vms.stream()
                .filter(vm -> vm1.fqdn().equals(vm.fqdn()))
                .findFirst();
        assertThat(destroyedVm).isPresent();
        assertThat(destroyedVm.get().state()).isEqualTo("deleted");
    }

    @Test
    void activeVmCountTracking() {
        assertThat(server.activeVmCount()).isZero();

        client.requestVm(new VmRequest("1", "2", "RedHat 10.2", "vm1", "key"));
        assertThat(server.activeVmCount()).isEqualTo(1);

        final var vm2 = client.requestVm(new VmRequest("8", "16", "RedHat 10.2", "vm2", "key"));
        assertThat(server.activeVmCount()).isEqualTo(2);

        client.destroyVm(vm2.fqdn());
        assertThat(server.activeVmCount()).isEqualTo(1);
    }
}
