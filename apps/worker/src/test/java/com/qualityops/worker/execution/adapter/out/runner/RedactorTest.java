package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private static final String MASK = "***REDACTED***";

    private final Redactor redactor = new Redactor(com.qualityops.worker.support.TestProps.defaults(
        Mode.AUTO, Duration.ofMinutes(5),
        new Ssrf(false, List.of(), List.of()),
        new Redaction(
            List.of("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key"),
            List.of(
                "(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*",
                "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+",
                "AKIA[0-9A-Z]{16}",
                "-----BEGIN [A-Z ]*PRIVATE KEY-----",
                "(?i)\"?password\"?\\s*[:=]\\s*\"?[^\"\\s,}]+")),
        false));

    @Test
    void headers_authorizationAndCookie_masked() {
        var in = new LinkedHashMap<String, String>();
        in.put("Authorization", "Bearer abc");
        in.put("Cookie", "session=1");
        in.put("Accept", "application/json");

        var out = redactor.headers(in);

        assertThat(out).containsEntry("Authorization", MASK);
        assertThat(out).containsEntry("Cookie", MASK);
        assertThat(out).containsEntry("Accept", "application/json");
    }

    @Test
    void headers_customTokenHeader_maskedByNamePattern() {
        var out = redactor.headers(java.util.Map.of("X-Auth-Token", "zzz"));
        assertThat(out).containsEntry("X-Auth-Token", MASK);
    }

    @Test
    void url_stripsQueryAndUserInfo() {
        assertThat(redactor.url("https://user:pw@example.com:8443/a/b?secret=1"))
            .isEqualTo("https://example.com:8443/a/b");
    }

    @Test
    void body_bearerToken_masked() {
        assertThat(redactor.body("Authorization: Bearer abc123.def")).contains(MASK).doesNotContain("abc123");
    }

    @Test
    void body_jwt_masked() {
        assertThat(redactor.body("tok=eyJhbGciOiJIUzI1.eyJzdWIiOiIx.c2ln")).contains(MASK);
    }

    @Test
    void body_pemPrivateKey_masked() {
        assertThat(redactor.body("-----BEGIN RSA PRIVATE KEY-----")).contains(MASK);
    }

    @Test
    void body_passwordAssignment_masked() {
        assertThat(redactor.body("{\"password\":\"hunter2\"}")).contains(MASK).doesNotContain("hunter2");
    }

    @Test
    void body_null_returnsNull() {
        assertThat(redactor.body(null)).isNull();
    }
}
