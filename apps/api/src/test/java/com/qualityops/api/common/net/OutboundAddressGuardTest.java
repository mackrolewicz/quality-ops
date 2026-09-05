package com.qualityops.api.common.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundAddressGuardTest {

    private final OutboundAddressGuard guard = new OutboundAddressGuard();

    @Test
    void check_httpPrivateV4_whenPrivateDisallowed_throws() {
        assertThatThrownBy(() -> guard.check("http://10.0.0.5", true, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void check_metadataIp_always_throws() {
        // link-local (169.254.0.0/16, incl. the cloud metadata endpoint) is denied
        // unconditionally — allowPrivate only relaxes RFC1918 / CGNAT / ULA / 0.0.0.0/8.
        assertThatThrownBy(() -> guard.check("https://169.254.169.254", true, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void check_ipv6Loopback_throws() {
        assertThatThrownBy(() -> guard.check("http://[::1]", true, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void check_loopbackV4_throws() {
        assertThatThrownBy(() -> guard.check("http://127.0.0.1", true, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void check_publicHttps_returnsNormally() {
        // example.com is IANA-reserved and resolves to a public address.
        assertThatCode(() -> guard.check("https://example.com", false, false))
            .doesNotThrowAnyException();
    }

    @Test
    void check_httpScheme_whenAllowHttpFalse_throws() {
        assertThatThrownBy(() -> guard.check("http://example.com", false, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void check_privateV4_whenAllowPrivateTrue_returnsNormally() {
        assertThatCode(() -> guard.check("http://10.1.2.3", true, true))
            .doesNotThrowAnyException();
    }

    @Test
    void check_malformedUrl_throws() {
        assertThatThrownBy(() -> guard.check("not a url", true, true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
