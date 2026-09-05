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
import java.util.Locale;
import java.util.Set;

public record BrowserTestPayload(
    @NotBlank @URL @Size(max = 2048) String startUrl,
    @NotNull @Size(min = 1, max = 40) List<@Valid BrowserStepPayload> steps,
    @NotNull @Size(min = 1, max = 40) List<@Valid BrowserAssertionPayload> assertions,
    @Min(1000) @Max(180000) Integer testTimeoutMillis,
    @Min(100) @Max(60000) Integer stepTimeoutMillis,
    @Min(100) @Max(120000) Integer navigationTimeoutMillis
) {
    public record SelectorPayload(
        @NotBlank @Pattern(regexp = "ROLE|LABEL|TEST_ID|TEXT|CSS") String strategy,
        @Size(max = 1024) String value,
        @Size(max = 64) String roleName,
        @Size(max = 256) String accessibleName
    ) {
        @AssertTrue(message = "ROLE selector requires roleName; LABEL/TEST_ID/TEXT/CSS require value")
        boolean isSelectorConsistent() {
            if (strategy == null) {
                return true;
            }
            return switch (strategy) {
                case "ROLE" -> roleName != null && !roleName.isBlank();
                case "LABEL", "TEST_ID", "TEXT", "CSS" -> value != null && !value.isBlank();
                default -> true;
            };
        }
    }

    public record BrowserStepPayload(
        @NotBlank @Pattern(regexp = "NAVIGATE|CLICK|FILL|SELECT|PRESS_KEY") String action,
        @Valid SelectorPayload target,
        @Size(max = 8192) String value,
        @Size(max = 64) String key,
        @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "secretRef must match [A-Z0-9_]{1,64}") String secretRef
    ) {
        /** Convenience — no secret. Keeps 4-arg call sites compiling. */
        public BrowserStepPayload(String action, SelectorPayload target, String value, String key) {
            this(action, target, value, key, null);
        }

        @AssertTrue(message = "step fields are inconsistent with its action")
        boolean isStepConsistent() {
            if (action == null) {
                return true;
            }
            return switch (action) {
                case "NAVIGATE" -> target == null && value != null && !value.isBlank() && secretRef == null;
                case "CLICK" -> target != null && secretRef == null;
                case "FILL" -> target != null && ((value != null) ^ (secretRef != null));
                case "SELECT" -> target != null && value != null && secretRef == null;
                case "PRESS_KEY" -> key != null && !key.isBlank() && secretRef == null;
                default -> true;
            };
        }
    }

    public record BrowserAssertionPayload(
        @NotBlank @Pattern(regexp = "TEXT_EQUALS|TEXT_CONTAINS|URL_EQUALS|URL_CONTAINS|VISIBLE|ELEMENT_STATE")
        String type,
        @Valid SelectorPayload target,
        @Size(max = 8192) String expected
    ) {
        private static final Set<String> ELEMENT_STATES =
            Set.of("enabled", "disabled", "checked", "unchecked", "editable", "hidden");

        @AssertTrue(message = "assertion fields are inconsistent with its type")
        boolean isAssertionConsistent() {
            if (type == null) {
                return true;
            }
            return switch (type) {
                case "TEXT_EQUALS", "TEXT_CONTAINS", "VISIBLE" -> target != null;
                case "URL_EQUALS", "URL_CONTAINS" -> expected != null && !expected.isBlank();
                case "ELEMENT_STATE" -> target != null && expected != null
                    && ELEMENT_STATES.contains(expected.trim().toLowerCase(Locale.ROOT));
                default -> true;
            };
        }
    }
}
