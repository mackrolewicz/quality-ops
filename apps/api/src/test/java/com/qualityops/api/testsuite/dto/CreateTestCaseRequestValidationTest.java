package com.qualityops.api.testsuite.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §11 — three-way mutual exclusion + {@code RepoTestPayload} bean
 *  validation on the authoring request (no MockMvc infra in this module, so the
 *  constraint graph is exercised directly). */
class CreateTestCaseRequestValidationTest {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    private static RepoTestPayload validRepoPayload() {
        return new RepoTestPayload(UUID.randomUUID(), "main", "PYTEST", null,
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML",
            List.of("report.xml"), List.of(), List.of(), List.of(), null, null, null);
    }

    @Test
    void validate_repoTestAlone_passes() {
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, validRepoPayload());

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void validate_repoTestWithApiRequest_failsMutualExclusion() {
        var api = new ApiRequestPayload("GET", "https://api.example.test/x",
            List.of(), null, 200, null, null, List.of());
        var request = new CreateTestCaseRequest("Mixed", "desc", 0, api, null, validRepoPayload());

        assertThat(VALIDATOR.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("specMutuallyExclusive"));
    }

    @Test
    void validate_repoTestWithBadFramework_failsOnFrameworkField() {
        var payload = new RepoTestPayload(UUID.randomUUID(), "main", "GRADLE", null,
            List.of("gradle", "test"), "JUNIT_XML", List.of(), List.of(), List.of(), List.of(), null, null, null);
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, payload);

        assertThat(VALIDATOR.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("repoTest.framework"));
    }

    @Test
    void validate_repoTestWithEmptyCommand_failsOnCommandField() {
        var payload = new RepoTestPayload(UUID.randomUUID(), "main", "PYTEST", null,
            List.of(), "JUNIT_XML", List.of(), List.of(), List.of(), List.of(), null, null, null);
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, payload);

        assertThat(VALIDATOR.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("repoTest.command"));
    }

    @Test
    void validate_k6FrameworkWithJunitXmlReport_failsConsistencyCheck() {
        var payload = new RepoTestPayload(UUID.randomUUID(), "main", "K6", null,
            List.of("k6", "run", "script.js"), "JUNIT_XML", List.of(), List.of(), List.of(), List.of(),
            null, null, null);
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, payload);

        assertThat(VALIDATOR.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().equals("repoTest.reportFormatConsistentWithFramework"));
    }

    @Test
    void validate_repoTestWithBadSecretRef_failsOnSecretRefField() {
        var payload = new RepoTestPayload(UUID.randomUUID(), "main", "PYTEST", null,
            List.of("pytest"), "JUNIT_XML", List.of(), List.of(), List.of(),
            List.of(new RepoTestPayload.SecretVarPayload("TOKEN", "lower-case")), null, null, null);
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, payload);

        assertThat(VALIDATOR.validate(request))
            .anyMatch(v -> v.getPropertyPath().toString().startsWith("repoTest.secretVars"));
    }
}
