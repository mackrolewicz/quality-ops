package com.qualityops.api.testsuite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** ADR-009 §11 — repository-execution spec authored on a test case, mutually
 *  exclusive with {@code apiRequest} / {@code browserTest}. No provider/host
 *  (from the connection) and no runner image (frozen from the API allowlist,
 *  ADR-009 §5). */
public record RepoTestPayload(
    @NotNull UUID repositoryConnectionId,
    @NotBlank @Size(max = 255) String requestedRef,
    @NotBlank @Pattern(regexp = "PLAYWRIGHT|JUNIT|PYTEST|CYPRESS|K6",
        message = "framework must be one of PLAYWRIGHT, JUNIT, PYTEST, CYPRESS, K6") String framework,
    @Size(max = 512) String workingDir,
    @NotEmpty @Size(max = 64) List<@NotBlank @Size(max = 1024) String> command,
    @NotBlank @Pattern(regexp = "JUNIT_XML|K6_SUMMARY_JSON",
        message = "reportFormat must be JUNIT_XML or K6_SUMMARY_JSON") String reportFormat,
    @Size(max = 20) List<@NotBlank @Size(max = 1024) String> reportPaths,
    @Size(max = 20) List<@NotBlank @Size(max = 1024) String> artifactGlobs,
    @Size(max = 50) List<@Valid EnvVarPayload> environmentVars,
    @Size(max = 50) List<@Valid SecretVarPayload> secretVars,
    @Pattern(regexp = "SMALL|MEDIUM|LARGE") String resourceProfile,
    @Pattern(regexp = "ISOLATED|EGRESS") String networkPolicy,
    @Min(30) @Max(1800) Integer timeoutSeconds
) {
    public record EnvVarPayload(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 8192) String value
    ) {}

    public record SecretVarPayload(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "secretRef must match [A-Z0-9_]{1,64}")
        String secretRef
    ) {}

    @AssertTrue(message = "reportFormat must be K6_SUMMARY_JSON for the K6 framework and JUNIT_XML otherwise")
    boolean isReportFormatConsistentWithFramework() {
        if (framework == null || reportFormat == null) {
            return true;
        }
        return "K6".equals(framework)
            ? "K6_SUMMARY_JSON".equals(reportFormat)
            : "JUNIT_XML".equals(reportFormat);
    }
}
