package com.qualityops.worker.execution.adapter.out.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ApiAssertion;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.domain.AssertionOutcome;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class AssertionEvaluator {

    private final ObjectMapper objectMapper;
    private final Redactor redactor;
    private final WorkerExecutionProperties props;

    public AssertionEvaluator(ObjectMapper objectMapper, Redactor redactor, WorkerExecutionProperties props) {
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.props = props;
    }

    /** @param headers case-insensitive lookup of the RAW response headers (single value join).
     *  Never throws for a malformed assertion — a missing/blank {@code target} or
     *  {@code expected} yields a failed {@link AssertionOutcome}. */
    public AssertionOutcome evaluate(ApiAssertion a, int statusCode, String body, Map<String, String> headers) {
        String expected = a.expected() == null ? "" : a.expected();
        String target = a.target() == null ? "" : a.target();
        return switch (a.type()) {
            case STATUS_EQUALS -> outcome(a, String.valueOf(statusCode),
                String.valueOf(statusCode).equals(expected.trim()));
            case BODY_CONTAINS -> outcome(a,
                props.persistBodySnippets() ? snippet(body) : "(body snippet suppressed)",
                body != null && !expected.isEmpty() && body.contains(expected));
            case HEADER_EQUALS -> {
                if (target.isBlank()) {
                    yield outcome(a, "(no assertion target)", false);
                }
                String actual = headers.getOrDefault(target.toLowerCase(Locale.ROOT), "");
                yield outcome(a, actual, actual.equals(expected));
            }
            case JSON_PATH_EQUALS -> {
                if (target.isBlank()) {
                    yield outcome(a, "(no assertion target)", false);
                }
                String actual = jsonPath(body, target);
                yield outcome(a, actual, expected.equals(actual));
            }
        };
    }

    public List<AssertionOutcome> evaluateAll(List<ApiAssertion> assertions, int status,
                                              String body, Map<String, String> headers) {
        return assertions == null ? List.of()
            : assertions.stream().map(a -> evaluate(a, status, body, headers)).toList();
    }

    private AssertionOutcome outcome(ApiAssertion a, String actual, boolean passed) {
        return new AssertionOutcome(a.type(), a.target() == null ? "" : a.target(),
            redactor.value(a.expected()), redactor.value(actual), passed);
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 120 ? body : body.substring(0, 120);
    }

    /** Minimal dotted path over a JSON object tree, e.g. {@code data.user.id}.
     *  No array indexing in 2B1 (documented limitation). */
    private String jsonPath(String body, String path) {
        if (body == null || path == null || path.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            for (String seg : path.split("\\.")) {
                node = node == null ? null : node.get(seg);
            }
            return node == null || node.isNull() ? "" : node.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }
}
