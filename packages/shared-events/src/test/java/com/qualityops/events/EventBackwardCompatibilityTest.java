package com.qualityops.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A v1 producer emits JSON with no {@code apiRequest} on snapshot items and no
 *  {@code caseResults} on RunCompletedEvent. v2 records must still deserialise it,
 *  yielding null for the new nested fields. */
class EventBackwardCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String V1_RUN_REQUESTED = """
        {"eventId":"11111111-1111-1111-1111-111111111111",
         "correlationId":"22222222-2222-2222-2222-222222222222",
         "orgId":"33333333-3333-3333-3333-333333333333",
         "runId":"44444444-4444-4444-4444-444444444444",
         "executionId":"55555555-5555-5555-5555-555555555555",
         "occurredAt":"2026-01-01T00:00:00Z","schemaVersion":1,
         "projectId":"66666666-6666-6666-6666-666666666666",
         "suiteId":"77777777-7777-7777-7777-777777777777",
         "environmentId":"88888888-8888-8888-8888-888888888888",
         "triggeredBy":"99999999-9999-9999-9999-999999999999",
         "testCases":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"login","orderIndex":0}]}
        """;

    private static final String V1_RUN_COMPLETED = """
        {"eventId":"11111111-1111-1111-1111-111111111111",
         "correlationId":"22222222-2222-2222-2222-222222222222",
         "orgId":"33333333-3333-3333-3333-333333333333",
         "runId":"44444444-4444-4444-4444-444444444444",
         "executionId":"55555555-5555-5555-5555-555555555555",
         "occurredAt":"2026-01-01T00:00:00Z","schemaVersion":1,
         "projectId":"66666666-6666-6666-6666-666666666666",
         "suiteId":"77777777-7777-7777-7777-777777777777",
         "outcome":"PASSED",
         "testCases":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"login","orderIndex":0}]}
        """;

    private static final String V2_RUN_REQUESTED_WITH_API = """
        {"eventId":"11111111-1111-1111-1111-111111111111",
         "correlationId":"22222222-2222-2222-2222-222222222222",
         "orgId":"33333333-3333-3333-3333-333333333333",
         "runId":"44444444-4444-4444-4444-444444444444",
         "executionId":"55555555-5555-5555-5555-555555555555",
         "occurredAt":"2026-01-01T00:00:00Z","schemaVersion":2,
         "projectId":"66666666-6666-6666-6666-666666666666",
         "suiteId":"77777777-7777-7777-7777-777777777777",
         "environmentId":"88888888-8888-8888-8888-888888888888",
         "triggeredBy":"99999999-9999-9999-9999-999999999999",
         "testCases":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"login","orderIndex":0,
           "apiRequest":{"method":"GET","url":"https://api.example.test/x","headers":[],"body":null,
             "expectedStatus":200,"timeoutMillis":null,"maxResponseBytes":null,"assertions":[]}}]}
        """;

    @Test
    void v1RunRequestedJson_deserialisesWithNullApiRequest() throws Exception {
        var event = mapper.readValue(V1_RUN_REQUESTED, RunRequestedEvent.class);
        assertThat(event.testCases()).hasSize(1);
        assertThat(event.testCases().get(0).apiRequest()).isNull();
    }

    @Test
    void v1RunCompletedJson_deserialisesWithNullCaseResults() throws Exception {
        var event = mapper.readValue(V1_RUN_COMPLETED, RunCompletedEvent.class);
        assertThat(event.caseResults()).isNull();
        assertThat(event.testCases()).hasSize(1);
    }

    @Test
    void v2RunRequestedJson_deserialisesWithNullBrowserTest_apiRequestStillPresent() throws Exception {
        var event = mapper.readValue(V2_RUN_REQUESTED_WITH_API, RunRequestedEvent.class);
        assertThat(event.testCases().get(0).browserTest()).isNull();
        assertThat(event.testCases().get(0).apiRequest()).isNotNull();
    }

    @Test
    void v2RunCompletedJson_deserialisesWithNullBrowserTest() throws Exception {
        var event = mapper.readValue(V1_RUN_COMPLETED.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            RunCompletedEvent.class);
        assertThat(event.testCases().get(0).browserTest()).isNull();
    }

    private static final String V3_RUN_REQUESTED_WITH_HEADER_AND_STEP = """
        {"eventId":"11111111-1111-1111-1111-111111111111",
         "correlationId":"22222222-2222-2222-2222-222222222222",
         "orgId":"33333333-3333-3333-3333-333333333333",
         "runId":"44444444-4444-4444-4444-444444444444",
         "executionId":"55555555-5555-5555-5555-555555555555",
         "occurredAt":"2026-01-01T00:00:00Z","schemaVersion":3,
         "projectId":"66666666-6666-6666-6666-666666666666",
         "suiteId":"77777777-7777-7777-7777-777777777777",
         "environmentId":"88888888-8888-8888-8888-888888888888",
         "triggeredBy":"99999999-9999-9999-9999-999999999999",
         "testCases":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"login","orderIndex":0,
           "apiRequest":{"method":"GET","url":"https://api.example.test/x",
             "headers":[{"name":"Accept","value":"application/json"}],"body":null,
             "expectedStatus":200,"timeoutMillis":null,"maxResponseBytes":null,"assertions":[]},
           "browserTest":{"startUrl":"https://app.example.test/","steps":[
             {"action":"FILL","target":{"strategy":"LABEL","value":"Email","roleName":null,"accessibleName":null},
              "value":"a@b.test","key":null}],
             "assertions":[],"testTimeoutMillis":60000,"navigationTimeoutMillis":15000,"stepTimeoutMillis":30000}}]}
        """;

    private static final String V3_RUN_COMPLETED_WITH_CASE_RESULTS = """
        {"eventId":"11111111-1111-1111-1111-111111111111",
         "correlationId":"22222222-2222-2222-2222-222222222222",
         "orgId":"33333333-3333-3333-3333-333333333333",
         "runId":"44444444-4444-4444-4444-444444444444",
         "executionId":"55555555-5555-5555-5555-555555555555",
         "occurredAt":"2026-01-01T00:00:00Z","schemaVersion":3,
         "projectId":"66666666-6666-6666-6666-666666666666",
         "suiteId":"77777777-7777-7777-7777-777777777777",
         "outcome":"FAILED",
         "testCases":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"login","orderIndex":0}],
         "caseResults":[{"testCaseId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","verdict":"FAILED",
           "durationMillis":123,"firstFailureReason":"boom"}]}
        """;

    @Test
    void v3RunRequestedJson_deserialisesWithNullSecretRefAndSecretValue() throws Exception {
        var event = mapper.readValue(V3_RUN_REQUESTED_WITH_HEADER_AND_STEP, RunRequestedEvent.class);
        var apiRequest = event.testCases().get(0).apiRequest();
        assertThat(apiRequest.headers().get(0).secretRef()).isNull();
        assertThat(apiRequest.headers().get(0).value()).isEqualTo("application/json");
        assertThat(event.testCases().get(0).browserTest().steps().get(0).secretValue()).isNull();
    }

    @Test
    void v3RunCompletedJson_deserialisesWithZeroEpochAndEmptyArtifacts() throws Exception {
        var event = mapper.readValue(V3_RUN_COMPLETED_WITH_CASE_RESULTS, RunCompletedEvent.class);
        var caseResult = event.caseResults().get(0);
        assertThat(caseResult.attemptEpoch()).isZero();
        assertThat(caseResult.artifacts()).isEmpty();
    }

    @Test
    void v4RunRequestedJson_deserialisesWithNullRepoTest() throws Exception {
        var v4 = V3_RUN_REQUESTED_WITH_HEADER_AND_STEP.replace("\"schemaVersion\":3", "\"schemaVersion\":4");
        var event = mapper.readValue(v4, RunRequestedEvent.class);
        assertThat(event.testCases().get(0).repoTest()).isNull();
    }

    @Test
    void v4RunCompletedJson_deserialisesWithEmptyRepositoryItems() throws Exception {
        var v4 = V3_RUN_COMPLETED_WITH_CASE_RESULTS.replace("\"schemaVersion\":3", "\"schemaVersion\":4");
        var event = mapper.readValue(v4, RunCompletedEvent.class);
        var caseResult = event.caseResults().get(0);
        assertThat(caseResult.repositoryItems()).isEmpty();
        assertThat(caseResult.repositoryProvenance()).isNull();
    }

    @Test
    void v1RunRequestedJson_stillDeserialisesUnderV5() throws Exception {
        var event = mapper.readValue(V1_RUN_REQUESTED, RunRequestedEvent.class);
        assertThat(event.testCases()).hasSize(1);
        assertThat(event.testCases().get(0).repoTest()).isNull();
    }
}
