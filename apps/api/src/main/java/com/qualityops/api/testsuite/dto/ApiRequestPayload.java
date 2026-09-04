package com.qualityops.api.testsuite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.List;

public record ApiRequestPayload(
    @NotBlank @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS",
                       message = "must be a standard HTTP method") String method,
    @NotBlank @URL @Size(max = 2048) String url,
    @Size(max = 50) List<@Valid HeaderPayload> headers,
    @Size(max = 64000) String body,
    @Min(100) @Max(599) Integer expectedStatus,
    @Min(1) @Max(300000) Integer timeoutMillis,
    @Min(1) @Max(10485760) Long maxResponseBytes,
    @Size(max = 20) List<@Valid AssertionPayload> assertions
) {
    public record HeaderPayload(
        @NotBlank @Size(max = 256) String name,
        @Size(max = 8192) String value,
        @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "secretRef must match [A-Z0-9_]{1,64}") String secretRef) {

        /** Convenience — plaintext header. Keeps 2-arg call sites compiling. */
        public HeaderPayload(String name, String value) {
            this(name, value, null);
        }

        @AssertTrue(message = "a header must set exactly one of value or secretRef")
        boolean isExactlyOneOfValueOrSecretRef() {
            return (value != null) ^ (secretRef != null);
        }
    }

    public record AssertionPayload(
        @NotBlank @Pattern(regexp = "STATUS_EQUALS|BODY_CONTAINS|HEADER_EQUALS|JSON_PATH_EQUALS") String type,
        @Size(max = 512) String target,
        @NotNull @Size(max = 8192) String expected) {

        @AssertTrue(message = "target is required for HEADER_EQUALS and JSON_PATH_EQUALS assertions")
        boolean isTargetPresentWhenRequired() {
            if (!"HEADER_EQUALS".equals(type) && !"JSON_PATH_EQUALS".equals(type)) {
                return true;
            }
            return target != null && !target.isBlank();
        }
    }
}
