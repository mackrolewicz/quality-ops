package com.qualityops.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventSerializationRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private <T> T roundTrip(T event, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(event), type);
    }

    private TestCaseSnapshotItem item() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "Login works", 0);
    }

    @Test
    void runRequestedEvent_serializedThenDeserialized_equalsOriginal() throws Exception {
        var original = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(item()));

        assertThat(roundTrip(original, RunRequestedEvent.class)).isEqualTo(original);
    }

    @Test
    void runStartedEvent_serializedThenDeserialized_equalsOriginal() throws Exception {
        var original = new RunStartedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunStartedEvent.SCHEMA_VERSION);

        assertThat(roundTrip(original, RunStartedEvent.class)).isEqualTo(original);
    }

    @Test
    void runCompletedEvent_serializedThenDeserialized_equalsOriginal() throws Exception {
        var original = new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunCompletedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), RunOutcome.PASSED, List.of(item(), item()), null);

        assertThat(roundTrip(original, RunCompletedEvent.class)).isEqualTo(original);
    }

    @Test
    void runRequestedEvent_withApiRequestSnapshot_roundTrips() throws Exception {
        var apiRequest = new ApiRequestSnapshot("POST", "https://api.example.test/login",
            List.of(new HttpHeader("Accept", "application/json")),
            "{\"u\":1}", 200, 5000, 65536L,
            List.of(new ApiAssertion(ApiAssertion.Type.STATUS_EQUALS, "", "200")));
        var original = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "Login works", 0, apiRequest)));

        assertThat(roundTrip(original, RunRequestedEvent.class)).isEqualTo(original);
    }

    @Test
    void runRequestedEvent_withPopulatedBrowserTestSnapshot_roundTrips() throws Exception {
        var role = new Selector(Selector.Strategy.ROLE, null, "button", "Go");
        var label = new Selector(Selector.Strategy.LABEL, "Email", null, null);
        var testId = new Selector(Selector.Strategy.TEST_ID, "msg", null, null);
        var text = new Selector(Selector.Strategy.TEXT, "Welcome", null, null);
        var css = new Selector(Selector.Strategy.CSS, "#go", null, null);
        var steps = List.of(
            new BrowserStep(BrowserStep.Action.NAVIGATE, null, "https://app.example.test/", null),
            new BrowserStep(BrowserStep.Action.FILL, label, "a@b.test", null),
            new BrowserStep(BrowserStep.Action.SELECT, css, "opt1", null),
            new BrowserStep(BrowserStep.Action.CLICK, role, null, null),
            new BrowserStep(BrowserStep.Action.PRESS_KEY, null, null, "Enter"));
        var assertions = List.of(
            new BrowserAssertion(BrowserAssertion.Type.TEXT_EQUALS, testId, "Hi"),
            new BrowserAssertion(BrowserAssertion.Type.TEXT_CONTAINS, testId, "H"),
            new BrowserAssertion(BrowserAssertion.Type.URL_EQUALS, null, "https://app.example.test/home"),
            new BrowserAssertion(BrowserAssertion.Type.URL_CONTAINS, null, "/home"),
            new BrowserAssertion(BrowserAssertion.Type.VISIBLE, text, null),
            new BrowserAssertion(BrowserAssertion.Type.ELEMENT_STATE, role, "enabled"));
        var snap = new BrowserTestSnapshot("https://app.example.test/", steps, assertions, 60000, 15000, 30000);
        var original = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "browser", 0, null, snap)));

        assertThat(roundTrip(original, RunRequestedEvent.class)).isEqualTo(original);
    }

    @Test
    void runCompletedEvent_withCaseResults_roundTrips() throws Exception {
        var original = new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunCompletedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), RunOutcome.FAILED, List.of(item(), item()),
            List.of(new CaseResultSummary(UUID.randomUUID(), CaseResultSummary.Verdict.FAILED, 123L,
                "STATUS_EQUALS expected 200 got 500")));

        assertThat(roundTrip(original, RunCompletedEvent.class)).isEqualTo(original);
    }

    @Test
    void resultChunkEvent_withAvailableAndUnavailableArtifacts_roundTrips() throws Exception {
        var original = new ResultChunkEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), ResultChunkEvent.SCHEMA_VERSION,
            UUID.randomUUID(), 1, CaseResultSummary.Verdict.FAILED, 456L, "assertion failed",
            List.of(
                new ArtifactReference(ArtifactType.SCREENSHOT, "org/o/run/r/case/c/attempt/1/SCREENSHOT/f.png",
                    "image/png", 2048L, ArtifactReference.Availability.AVAILABLE, null),
                new ArtifactReference(ArtifactType.TRACE, null, null, null,
                    ArtifactReference.Availability.UNAVAILABLE, "store-unreachable")),
            List.of(), null);

        assertThat(roundTrip(original, ResultChunkEvent.class)).isEqualTo(original);
    }

    @Test
    void secretRef_serializedThenDeserialized_equalsOriginal() throws Exception {
        var original = new SecretRef("DEMO_PASSWORD");

        assertThat(roundTrip(original, SecretRef.class)).isEqualTo(original);
    }

    @Test
    void runRequestedEvent_withSecretRefHeaderAndFillStep_roundTrips() throws Exception {
        var apiRequest = new ApiRequestSnapshot("GET", "https://api.example.test/x",
            List.of(new HttpHeader("Authorization", null, new SecretRef("API_TOKEN"))),
            null, 200, 5000, 65536L, List.of());
        var label = new Selector(Selector.Strategy.LABEL, "Password", null, null);
        var snap = new BrowserTestSnapshot("https://app.example.test/",
            List.of(new BrowserStep(BrowserStep.Action.FILL, label, null, null, new SecretRef("DEMO_PASSWORD"))),
            List.of(), 60000, 15000, 30000);
        var original = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "secret case", 0, apiRequest, snap)));

        assertThat(roundTrip(original, RunRequestedEvent.class)).isEqualTo(original);
    }

    @Test
    void runFailedEvent_serializedThenDeserialized_equalsOriginal() throws Exception {
        var original = new RunFailedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunFailedEvent.SCHEMA_VERSION, "execution interrupted");

        assertThat(roundTrip(original, RunFailedEvent.class)).isEqualTo(original);
    }

    @Test
    void runCancelRequestedEvent_roundTrips_viaSharedObjectMapper() throws Exception {
        var original = new RunCancelRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunCancelRequestedEvent.SCHEMA_VERSION);

        assertThat(roundTrip(original, RunCancelRequestedEvent.class)).isEqualTo(original);
    }

    private RepoTestSnapshot repoTestSnapshot() {
        return new RepoTestSnapshot(
            UUID.randomUUID(), RepositoryProvider.GITLAB, "gitlab.com", "acme/widgets",
            "v2", "0123456789012345678901234567890123456789", RepoRefType.TAG,
            FrameworkPreset.PLAYWRIGHT, "mcr.microsoft.com/playwright:v1.55.0-jammy@sha256:pin", "e2e",
            List.of("npx", "playwright", "test", "--reporter=junit"), RepoReportFormat.JUNIT_XML,
            List.of("results/junit.xml"), List.of("test-results/**", "playwright-report/**"),
            List.of(new EnvVar("BASE_URL", "https://example.test"), new EnvVar("CI", "true")),
            List.of(new SecretEnvVar("LOGIN_PASSWORD", new SecretRef("DEMO_PASSWORD"))),
            "REPO_TOKEN", RepoResourceProfile.LARGE, RepoNetworkPolicy.EGRESS, 900);
    }

    @Test
    void repoTestSnapshot_roundTrips() throws Exception {
        var original = repoTestSnapshot();

        assertThat(roundTrip(original, RepoTestSnapshot.class)).isEqualTo(original);
    }

    @Test
    void runRequestedEvent_withRepoTestSnapshot_roundTrips() throws Exception {
        var original = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "repo case", 0, null, null, repoTestSnapshot())));

        assertThat(roundTrip(original, RunRequestedEvent.class)).isEqualTo(original);
    }

    @Test
    void caseResultSummary_withRepositoryItems_roundTrips() throws Exception {
        var provenance = new RepositoryRunProvenance(
            "sha256:deadbeef", 1, 3, 2, 1, 0,
            Instant.parse("2026-01-01T00:00:01Z"),
            Instant.parse("2026-01-01T00:00:02Z"),
            Instant.parse("2026-01-01T00:00:09Z"));
        var original = new CaseResultSummary(
            UUID.randomUUID(), CaseResultSummary.Verdict.FAILED, 8_000L, "1 of 3 tests failed; exit 1", 0,
            List.of(),
            List.of(
                new RepositoryTestItem("suite.Login", "logs in", RepositoryTestItem.RepoItemStatus.PASSED, 120L, null, null),
                new RepositoryTestItem("suite.Login", "rejects bad password",
                    RepositoryTestItem.RepoItemStatus.FAILED, 90L, "AssertionError", "expected 200 got 401")),
            provenance);

        assertThat(roundTrip(original, CaseResultSummary.class)).isEqualTo(original);
    }

    @Test
    void resultChunkEvent_v2_withRepositoryItemsAndProvenance_roundTrips() throws Exception {
        var provenance = new RepositoryRunProvenance(
            "sha256:deadbeef", 0, 2, 2, 0, 0,
            Instant.parse("2026-01-01T00:00:01Z"),
            Instant.parse("2026-01-01T00:00:02Z"),
            Instant.parse("2026-01-01T00:00:05Z"));
        var original = new ResultChunkEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"), ResultChunkEvent.SCHEMA_VERSION,
            UUID.randomUUID(), 0, CaseResultSummary.Verdict.PASSED, 5_000L, null,
            List.of(new ArtifactReference(ArtifactType.REPORT, "org/o/run/r/attempt/0/REPORT/junit.xml",
                "application/xml", 1024L, ArtifactReference.Availability.AVAILABLE, null)),
            List.of(new RepositoryTestItem("suite.Smoke", "boots", RepositoryTestItem.RepoItemStatus.PASSED, 42L, null, null)),
            provenance);

        assertThat(roundTrip(original, ResultChunkEvent.class)).isEqualTo(original);
    }
}
