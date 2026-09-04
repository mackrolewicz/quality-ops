package com.qualityops.api.scm.application.service;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.exception.RepositoryHostNotAllowedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §4 — the SCM host gate. Denied hosts are rejected before any socket
 *  is opened (the allowlist performs no I/O at all). */
class ScmHostAllowlistTest {

    private static ScmHostAllowlist allowlist(String... hosts) {
        var scm = new RepoExecApiProperties.Scm(List.of(hosts), false, Duration.ofSeconds(5),
            Duration.ofSeconds(10), "P_", "", "", "");
        return new ScmHostAllowlist(new RepoExecApiProperties(true, null,
            Duration.ofMinutes(10), Duration.ofMinutes(30), null, scm, false));
    }

    @Test
    void check_allowedHost_passes() {
        assertThatCode(() -> allowlist("github.com", "gitlab.com").check("github.com"))
            .doesNotThrowAnyException();
    }

    @Test
    void check_allowedHostDifferentCase_passes() {
        assertThatCode(() -> allowlist("github.com").check("GitHub.com"))
            .doesNotThrowAnyException();
    }

    @Test
    void check_hostNotOnList_throwsRepositoryHostNotAllowed() {
        assertThatThrownBy(() -> allowlist("github.com").check("evil.example.com"))
            .isInstanceOf(RepositoryHostNotAllowedException.class);
    }

    @Test
    void check_nullHost_throwsRepositoryHostNotAllowed() {
        assertThatThrownBy(() -> allowlist("github.com").check(null))
            .isInstanceOf(RepositoryHostNotAllowedException.class);
    }

    @Test
    void check_emptyAllowlist_deniesEverything() {
        assertThatThrownBy(() -> allowlist().check("github.com"))
            .isInstanceOf(RepositoryHostNotAllowedException.class);
    }
}
