package com.qualityops.events;

/** One assertion evaluated against the response of an API-test case.
 *  target semantics: HEADER_EQUALS -> header name; JSON_PATH_EQUALS -> json path;
 *  STATUS_EQUALS / BODY_CONTAINS -> target ignored, operand is {@code expected}. */
public record ApiAssertion(Type type, String target, String expected) {
    public enum Type { STATUS_EQUALS, BODY_CONTAINS, HEADER_EQUALS, JSON_PATH_EQUALS }
}
