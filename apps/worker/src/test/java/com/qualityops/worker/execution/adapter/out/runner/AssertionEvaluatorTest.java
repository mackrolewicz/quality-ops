package com.qualityops.worker.execution.adapter.out.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ApiAssertion;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.config.WorkerExecutionProperties.Redaction;
import com.qualityops.worker.config.WorkerExecutionProperties.Ssrf;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionEvaluatorTest {

    private static WorkerExecutionProperties props(boolean persistBodySnippets) {
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            new Ssrf(false, List.of(), List.of()),
            new Redaction(List.of(), List.of("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")),
            persistBodySnippets);
    }

    private static final WorkerExecutionProperties PROPS = props(true);

    private final AssertionEvaluator evaluator =
        new AssertionEvaluator(new ObjectMapper(), new Redactor(PROPS), PROPS);

    private ApiAssertion a(ApiAssertion.Type type, String target, String expected) {
        return new ApiAssertion(type, target, expected);
    }

    @Test
    void statusEquals_matchingStatus_passes() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.STATUS_EQUALS, "", "200"), 200, "", Map.of())
            .passed()).isTrue();
    }

    @Test
    void statusEquals_differentStatus_fails() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.STATUS_EQUALS, "", "200"), 500, "", Map.of())
            .passed()).isFalse();
    }

    @Test
    void bodyContains_present_passes() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.BODY_CONTAINS, "", "ok"), 200, "all ok here", Map.of())
            .passed()).isTrue();
    }

    @Test
    void bodyContains_absent_fails() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.BODY_CONTAINS, "", "missing"), 200, "body", Map.of())
            .passed()).isFalse();
    }

    @Test
    void headerEquals_matchingValue_passes() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.HEADER_EQUALS, "content-type", "application/json"),
            200, "", Map.of("content-type", "application/json")).passed()).isTrue();
    }

    @Test
    void headerEquals_differentValue_fails() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.HEADER_EQUALS, "content-type", "text/plain"),
            200, "", Map.of("content-type", "application/json")).passed()).isFalse();
    }

    @Test
    void jsonPathEquals_matchingValue_passes() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.JSON_PATH_EQUALS, "data.user.id", "42"),
            200, "{\"data\":{\"user\":{\"id\":42}}}", Map.of()).passed()).isTrue();
    }

    @Test
    void jsonPathEquals_differentValue_fails() {
        assertThat(evaluator.evaluate(a(ApiAssertion.Type.JSON_PATH_EQUALS, "data.user.id", "99"),
            200, "{\"data\":{\"user\":{\"id\":42}}}", Map.of()).passed()).isFalse();
    }

    @Test
    void jsonPath_missingPath_actualEmpty() {
        var outcome = evaluator.evaluate(a(ApiAssertion.Type.JSON_PATH_EQUALS, "no.such.path", "x"),
            200, "{\"a\":1}", Map.of());
        assertThat(outcome.actual()).isEmpty();
        assertThat(outcome.passed()).isFalse();
    }

    @Test
    void headerEquals_nullTarget_failsWithoutThrowing() {
        var outcome = evaluator.evaluate(a(ApiAssertion.Type.HEADER_EQUALS, null, "application/json"),
            200, "", Map.of("content-type", "application/json"));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.actual()).isEqualTo("(no assertion target)");
    }

    @Test
    void jsonPathEquals_blankTarget_failsWithoutThrowing() {
        var outcome = evaluator.evaluate(a(ApiAssertion.Type.JSON_PATH_EQUALS, "  ", "42"),
            200, "{\"a\":42}", Map.of());
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.actual()).isEqualTo("(no assertion target)");
    }

    @Test
    void bodyContains_nullExpected_failsWithoutThrowing() {
        var outcome = evaluator.evaluate(a(ApiAssertion.Type.BODY_CONTAINS, "", null), 200, "body", Map.of());
        assertThat(outcome.passed()).isFalse();
    }

    @Test
    void statusEquals_nullExpected_failsWithoutThrowing() {
        var outcome = evaluator.evaluate(a(ApiAssertion.Type.STATUS_EQUALS, "", null), 200, "", Map.of());
        assertThat(outcome.passed()).isFalse();
    }

    @Test
    void outcome_expectedAndActual_areRedacted() {
        var outcome = evaluator.evaluate(
            a(ApiAssertion.Type.BODY_CONTAINS, "", "Bearer sk-abc123.def"), 200, "Bearer sk-abc123.def", Map.of());
        assertThat(outcome.expected()).contains("***REDACTED***").doesNotContain("sk-abc123");
        assertThat(outcome.actual()).contains("***REDACTED***");
    }

    @Test
    void bodyContains_persistBodySnippetsFalse_actualIsSuppressed() {
        var suppressed = new AssertionEvaluator(new ObjectMapper(), new Redactor(props(false)), props(false));
        var outcome = suppressed.evaluate(
            a(ApiAssertion.Type.BODY_CONTAINS, "", "ok"), 200, "all ok here", Map.of());
        assertThat(outcome.actual()).isEqualTo("(body snippet suppressed)");
    }

    @Test
    void bodyContains_persistBodySnippetsTrue_actualIsSnippet() {
        var outcome = evaluator.evaluate(
            a(ApiAssertion.Type.BODY_CONTAINS, "", "ok"), 200, "all ok here", Map.of());
        assertThat(outcome.actual()).isEqualTo("all ok here");
    }
}
