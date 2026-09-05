package com.qualityops.api.scm.adapter.out.credential;

import com.qualityops.api.config.RepoExecApiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §4 — env-var-backed SCM credential resolution. */
class EnvScmCredentialResolverTest {

    private static RepoExecApiProperties props(String envPrefix) {
        var scm = new RepoExecApiProperties.Scm(java.util.List.of("github.com"), false,
            Duration.ofSeconds(5), Duration.ofSeconds(10), envPrefix, "", "", "");
        return new RepoExecApiProperties(true, null, Duration.ofMinutes(10), Duration.ofMinutes(30),
            null, scm, false);
    }

    private static EnvScmCredentialResolver resolver(String envPrefix, Map<String, String> env) {
        return EnvScmCredentialResolver.withEnv(props(envPrefix), env::get);
    }

    @Test
    void resolve_keyPresentInEnv_returnsPlaintext() {
        var r = resolver("QUALITYOPS_SCM_CREDENTIAL_", Map.of("QUALITYOPS_SCM_CREDENTIAL_GH_PAT", "ghp_secret"));

        assertThat(r.resolve("GH_PAT")).isEqualTo("ghp_secret");
    }

    @Test
    void resolve_keyAbsent_returnsNull() {
        var r = resolver("QUALITYOPS_SCM_CREDENTIAL_", Map.of());

        assertThat(r.resolve("GH_PAT")).isNull();
    }

    @Test
    void resolve_nullRef_returnsNullWithoutLookup() {
        var r = resolver("QUALITYOPS_SCM_CREDENTIAL_", Map.of("QUALITYOPS_SCM_CREDENTIAL_", "x"));

        assertThat(r.resolve(null)).isNull();
    }

    @Test
    void resolve_blankEnvValue_isTreatedAsMiss() {
        var r = resolver("QUALITYOPS_SCM_CREDENTIAL_", Map.of("QUALITYOPS_SCM_CREDENTIAL_GH_PAT", "   "));

        assertThat(r.resolve("GH_PAT")).isNull();
    }
}
