package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TargetValidatorTest {

    private TargetValidator validator(boolean allowPrivate, List<String> allowedHosts) {
        var ssrf = new Ssrf(allowPrivate, allowedHosts, List.of(80, 443, 8080));
        var props = com.qualityops.worker.support.TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            ssrf, new Redaction(List.of(), List.of()), false);
        return new TargetValidator(props);
    }

    private TargetValidator strict() {
        return validator(false, List.of());
    }

    @Test
    void validateUrl_ftpScheme_blocked() {
        assertThat(strict().validateUrl("ftp://example.com/x")).isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateUrl_withUserInfo_blocked() {
        assertThat(strict().validateUrl("https://user:pw@example.com"))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateUrl_missingHost_blocked() {
        assertThat(strict().validateUrl("https:///path")).isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateUrl_disallowedPort_blocked() {
        assertThat(strict().validateUrl("http://example.com:9999")).isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateUrl_httpsDefault_allowed() {
        assertThat(strict().validateUrl("https://example.com/x")).isInstanceOf(TargetValidator.Allowed.class);
    }

    @Test
    void validateResolved_loopback_blocked() throws Exception {
        assertThat(strict().validateResolved("localhost", List.of(InetAddress.getByName("127.0.0.1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
        assertThat(strict().validateResolved("localhost", List.of(InetAddress.getByName("::1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_linkLocalMetadata_blocked() throws Exception {
        assertThat(strict().validateResolved("meta", List.of(InetAddress.getByName("169.254.169.254"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_siteLocal_blocked() throws Exception {
        for (String ip : List.of("10.0.0.5", "192.168.1.1", "172.16.0.1")) {
            assertThat(strict().validateResolved("h", List.of(InetAddress.getByName(ip))))
                .as(ip)
                .isInstanceOf(TargetValidator.Blocked.class);
        }
    }

    @Test
    void validateResolved_ipv6Ula_blocked() throws Exception {
        assertThat(strict().validateResolved("h", List.of(InetAddress.getByName("fc00::1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_cgnat_blocked() throws Exception {
        assertThat(strict().validateResolved("h", List.of(InetAddress.getByName("100.64.0.1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_ipv4MappedLoopback_blocked() throws Exception {
        assertThat(strict().validateResolved("h", List.of(InetAddress.getByName("::ffff:127.0.0.1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_publicAddress_allowed() throws Exception {
        assertThat(strict().validateResolved("h", List.of(InetAddress.getByName("93.184.216.34"))))
            .isInstanceOf(TargetValidator.Allowed.class);
    }

    @Test
    void validateResolved_allowedPrivateHost_loopbackAllowed() throws Exception {
        assertThat(validator(true, List.of("localtest"))
            .validateResolved("localtest", List.of(InetAddress.getByName("127.0.0.1"))))
            .isInstanceOf(TargetValidator.Allowed.class);
    }

    @Test
    void validateResolved_allowedPrivateHost_metadataStillBlocked() throws Exception {
        assertThat(validator(true, List.of("localtest"))
            .validateResolved("localtest", List.of(InetAddress.getByName("169.254.169.254"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }

    @Test
    void validateResolved_privateHostNotInAllowList_stillBlocked() throws Exception {
        assertThat(validator(true, List.of("other"))
            .validateResolved("localtest", List.of(InetAddress.getByName("127.0.0.1"))))
            .isInstanceOf(TargetValidator.Blocked.class);
    }
}
