package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §8 — the per-execution {@code RedactionView}: resolved secret
 *  plaintexts and the checkout token are masked everywhere the existing
 *  {@link Redactor} rules already apply (stdout lines, assertion-style values,
 *  URLs, headers), on top of the singleton's regex-based rules. */
class RepoRedactionTest {

    private static final String MASK = "***REDACTED***";
    private static final String SECRET_PLAINTEXT = "s3cr3t-db-password";
    private static final String CHECKOUT_TOKEN = "ghp_abcdEFGH1234567890";

    private final Redactor redactor = new Redactor(com.qualityops.worker.support.TestProps.defaults(
        Mode.AUTO, Duration.ofMinutes(5),
        new Ssrf(false, List.of(), List.of()),
        new Redaction(List.of("authorization"), List.of("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")),
        false));

    private Redactor.RedactionView view() {
        return redactor.forExecution(Set.of(SECRET_PLAINTEXT, CHECKOUT_TOKEN));
    }

    @Test
    void line_containingResolvedSecretPlaintext_isMasked() {
        String out = view().line("connecting with password=" + SECRET_PLAINTEXT + " ...");

        assertThat(out).contains(MASK).doesNotContain(SECRET_PLAINTEXT);
    }

    @Test
    void line_containingCheckoutToken_isMasked() {
        String out = view().line("fatal: Authentication failed for token " + CHECKOUT_TOKEN);

        assertThat(out).contains(MASK).doesNotContain(CHECKOUT_TOKEN);
    }

    @Test
    void line_stillAppliesExistingRegexRules_onTopOfLiteralMasking() {
        String out = view().line("Authorization: Bearer some.jwt.value and secret " + SECRET_PLAINTEXT);

        assertThat(out).doesNotContain(SECRET_PLAINTEXT);
        assertThat(out).doesNotContain("some.jwt.value");
    }

    @Test
    void value_failureMessageContainingSecret_isMasked() {
        String failureMessage = "expected \"" + SECRET_PLAINTEXT + "\" but request failed";

        assertThat(view().value(failureMessage)).contains(MASK).doesNotContain(SECRET_PLAINTEXT);
    }

    @Test
    void headers_valueContainingSecret_isMaskedEvenUnderANonSensitiveName() {
        var out = view().headers(Map.of("X-Debug-Info", "db=" + SECRET_PLAINTEXT));

        assertThat(out.get("X-Debug-Info")).contains(MASK).doesNotContain(SECRET_PLAINTEXT);
    }

    @Test
    void noLiterals_behavesLikeThePlainRedactor() {
        var noLiterals = redactor.forExecution(Set.of());

        assertThat(noLiterals.line("plain log line")).isEqualTo("plain log line");
    }

    @Test
    void line_null_returnsNull() {
        assertThat(view().line(null)).isNull();
    }
}
