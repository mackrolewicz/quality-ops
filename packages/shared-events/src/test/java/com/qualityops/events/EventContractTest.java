package com.qualityops.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Wire-format lock: a field rename/removal or a schemaVersion drift breaks the build. */
class EventContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Set<String> fieldNames(Object event) throws Exception {
        var tree = mapper.readTree(mapper.writeValueAsString(event));
        var names = new TreeSet<String>();
        tree.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private int schemaVersion(Object event) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(event)).get("schemaVersion").asInt();
    }

    private Set<String> firstTestCaseFieldNames(Object event) throws Exception {
        var firstCase = mapper.readTree(mapper.writeValueAsString(event)).get("testCases").get(0);
        var names = new TreeSet<String>();
        firstCase.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private TestCaseSnapshotItem item() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0);
    }

    private TestCaseSnapshotItem itemWithApiRequest() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0,
            new ApiRequestSnapshot("POST", "https://api.example.test/login",
                List.of(new HttpHeader("Accept", "application/json")),
                "{\"u\":1}", 200, 5000, 65536L,
                List.of(new ApiAssertion(ApiAssertion.Type.STATUS_EQUALS, "", "200"))));
    }

    private TestCaseSnapshotItem itemWithBrowserTest() {
        var sel = new Selector(Selector.Strategy.ROLE, null, "button", "Go");
        return new TestCaseSnapshotItem(UUID.randomUUID(), "browser case", 0, null,
            new BrowserTestSnapshot("https://app.example.test/login",
                List.of(new BrowserStep(BrowserStep.Action.NAVIGATE, null, "https://app.example.test/login", null),
                        new BrowserStep(BrowserStep.Action.CLICK, sel, null, null)),
                List.of(new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/home")),
                60000, 15000, 30000));
    }

    private Set<String> namesOf(com.fasterxml.jackson.databind.JsonNode n) {
        var names = new TreeSet<String>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void runRequestedEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(item()));

        assertThat(fieldNames(event)).containsExactly(
            "correlationId", "environmentId", "eventId", "executionId", "occurredAt",
            "orgId", "projectId", "runId", "schemaVersion", "suiteId", "testCases", "triggeredBy");
        assertThat(firstTestCaseFieldNames(event))
            .containsExactly("apiRequest", "browserTest", "name", "orderIndex", "repoTest", "testCaseId");
        assertThat(schemaVersion(event)).isEqualTo(5);
    }

    @Test
    void runRequestedEvent_withApiRequest_serialisesNestedFieldNames() throws Exception {
        var event = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(itemWithApiRequest()));

        var apiReq = mapper.readTree(mapper.writeValueAsString(event))
            .get("testCases").get(0).get("apiRequest");
        var names = new TreeSet<String>();
        apiReq.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly(
            "assertions", "body", "expectedStatus", "headers", "maxResponseBytes", "method",
            "timeoutMillis", "url");

        var header = apiReq.get("headers").get(0);
        var hNames = new TreeSet<String>();
        header.fieldNames().forEachRemaining(hNames::add);
        assertThat(hNames).containsExactly("name", "secretRef", "value");

        var assertion = apiReq.get("assertions").get(0);
        var aNames = new TreeSet<String>();
        assertion.fieldNames().forEachRemaining(aNames::add);
        assertThat(aNames).containsExactly("expected", "target", "type");
    }

    @Test
    void runStartedEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new RunStartedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunStartedEvent.SCHEMA_VERSION);

        assertThat(fieldNames(event)).containsExactly(
            "correlationId", "eventId", "executionId", "occurredAt", "orgId", "runId", "schemaVersion");
        assertThat(schemaVersion(event)).isEqualTo(1);
    }

    @Test
    void runCompletedEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), RunOutcome.PASSED, List.of(item()), null);

        assertThat(fieldNames(event)).containsExactly(
            "caseResults", "correlationId", "eventId", "executionId", "occurredAt", "orgId", "outcome",
            "projectId", "runId", "schemaVersion", "suiteId", "testCases");
        assertThat(firstTestCaseFieldNames(event))
            .containsExactly("apiRequest", "browserTest", "name", "orderIndex", "repoTest", "testCaseId");
        assertThat(schemaVersion(event)).isEqualTo(5);
    }

    @Test
    void runCompletedEvent_withCaseResults_serialisesRetryAndArtifactFields() throws Exception {
        var caseResult = new CaseResultSummary(UUID.randomUUID(), CaseResultSummary.Verdict.FAILED, 12L,
            "boom", 1,
            List.of(new ArtifactReference(ArtifactType.SCREENSHOT, "org/o/run/r/x.png",
                "image/png", 42L, ArtifactReference.Availability.AVAILABLE, null)));
        var event = new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), RunOutcome.FAILED, List.of(item()), List.of(caseResult));

        var cr = mapper.readTree(mapper.writeValueAsString(event)).get("caseResults").get(0);
        assertThat(namesOf(cr)).containsExactly(
            "artifacts", "attemptEpoch", "durationMillis", "firstFailureReason",
            "repositoryItems", "repositoryProvenance", "testCaseId", "verdict");
        assertThat(namesOf(cr.get("artifacts").get(0))).containsExactly(
            "artifactType", "contentType", "sizeBytes", "status", "storageKey", "unavailableReason");
    }

    @Test
    void resultChunkEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new ResultChunkEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), ResultChunkEvent.SCHEMA_VERSION,
            UUID.randomUUID(), 0, CaseResultSummary.Verdict.PASSED, 10L, null, List.of(), List.of(), null);

        assertThat(fieldNames(event)).containsExactly(
            "artifacts", "attemptEpoch", "correlationId", "durationMillis", "eventId", "executionId",
            "firstFailureReason", "occurredAt", "orgId", "repositoryItems", "repositoryProvenance",
            "runId", "schemaVersion", "testCaseId", "verdict");
        assertThat(schemaVersion(event)).isEqualTo(2);
    }

    @Test
    void runRequestedEvent_withBrowserTest_serialisesNestedFieldNames() throws Exception {
        var event = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(itemWithBrowserTest()));

        var bt = mapper.readTree(mapper.writeValueAsString(event))
            .get("testCases").get(0).get("browserTest");
        assertThat(namesOf(bt)).containsExactly(
            "assertions", "navigationTimeoutMillis", "startUrl", "stepTimeoutMillis", "steps", "testTimeoutMillis");
        assertThat(namesOf(bt.get("steps").get(0)))
            .containsExactly("action", "key", "secretValue", "target", "value");
        assertThat(namesOf(bt.get("steps").get(1).get("target")))
            .containsExactly("accessibleName", "roleName", "strategy", "value");
        assertThat(namesOf(bt.get("assertions").get(0))).containsExactly("expected", "target", "type");
    }

    @Test
    void runRequestedEvent_withRepoTest_serialisesNestedFieldNames() throws Exception {
        var repo = new RepoTestSnapshot(
            UUID.randomUUID(), RepositoryProvider.GITHUB, "github.com", "acme/widgets",
            "main", "0123456789012345678901234567890123456789", RepoRefType.BRANCH,
            FrameworkPreset.PYTEST, "python:3.12-slim@sha256:abc", "svc",
            List.of("pytest", "--junitxml=report.xml"), RepoReportFormat.JUNIT_XML,
            List.of("report.xml"), List.of("screenshots/**"),
            List.of(new EnvVar("BASE_URL", "https://example.test")),
            List.of(new SecretEnvVar("TOKEN", new SecretRef("REPO_TOKEN"))),
            "REPO_TOKEN", RepoResourceProfile.MEDIUM, RepoNetworkPolicy.ISOLATED, 600);
        var event = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, repo)));

        var repoNode = mapper.readTree(mapper.writeValueAsString(event))
            .get("testCases").get(0).get("repoTest");
        assertThat(namesOf(repoNode)).containsExactly(
            "artifactGlobs", "command", "commitSha", "credentialRef", "environmentVars",
            "framework", "networkPolicy", "provider", "refType", "repoHost", "repoPath",
            "reportFormat", "reportPaths", "repositoryConnectionId", "requestedRef",
            "resourceProfile", "runnerImageRef", "secretVars", "timeoutSeconds", "workingDir");
        assertThat(namesOf(repoNode.get("environmentVars").get(0))).containsExactly("name", "value");
        assertThat(namesOf(repoNode.get("secretVars").get(0))).containsExactly("name", "ref");
        assertThat(schemaVersion(event)).isEqualTo(5);
    }

    @Test
    void runFailedEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new RunFailedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunFailedEvent.SCHEMA_VERSION, "reason");

        assertThat(fieldNames(event)).containsExactly(
            "correlationId", "eventId", "executionId", "occurredAt", "orgId", "reason", "runId", "schemaVersion");
        assertThat(schemaVersion(event)).isEqualTo(1);
    }

    @Test
    void runCancelRequestedEvent_serialized_hasExpectedFieldNamesAndSchemaVersion() throws Exception {
        var event = new RunCancelRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunCancelRequestedEvent.SCHEMA_VERSION);

        assertThat(fieldNames(event)).containsExactly(
            "correlationId", "eventId", "executionId", "occurredAt", "orgId", "runId", "schemaVersion");
        assertThat(schemaVersion(event)).isEqualTo(1);
    }

    @Test
    void runEventSeal_after2C_stillPermitsExactlyTheFourLifecycleRecords() {
        assertThat(RunEvent.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
            RunRequestedEvent.class, RunStartedEvent.class, RunCompletedEvent.class, RunFailedEvent.class);
        assertThat(RunCancelRequestedEvent.class.getInterfaces()).isEmpty();
    }
}
