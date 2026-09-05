package com.qualityops.api.result;

import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.ArtifactReference;
import com.qualityops.events.ArtifactType;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.TestCaseSnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ADR-005 §2.5: losing every {@code results.chunk} must cost nothing — the v4
 *  terminal alone reconciles {@code test_results} AND {@code test_result_artifacts}. */
class ReconciliationIT extends AbstractKafkaPostgresIT {

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private RunRepository runRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID runId;
    private UUID executionId;
    private UUID projectId;
    private UUID suiteId;
    private List<UUID> caseIds;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        var environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        var triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        caseIds = ItFixtures.insertCases(jdbc, orgId, suiteId, 2);
        executionId = UUID.randomUUID();
        var snapshot = new RunConfigSnapshot(List.of(
            new com.qualityops.api.execution.domain.TestCaseSnapshotItem(caseIds.get(0), "Case 0", 0),
            new com.qualityops.api.execution.domain.TestCaseSnapshotItem(caseIds.get(1), "Case 1", 1)));
        runId = runRepository.save(new TestRun(UUID.randomUUID(), orgId, projectId, suiteId, environmentId,
            executionId, RunStatus.RUNNING, triggeredBy, snapshot, null, null, Instant.now())).id();
    }

    @Test
    void terminalAlone_yieldsCorrectResultsAndArtifactRows_noChunksSent() {
        var c0 = caseIds.get(0);
        var c1 = caseIds.get(1);
        var summaries = List.of(
            new CaseResultSummary(c0, CaseResultSummary.Verdict.FAILED, 500L, "assertion failed", 1,
                List.of(new ArtifactReference(ArtifactType.SCREENSHOT,
                    "org/" + orgId + "/run/" + runId + "/case/" + c0 + "/attempt/1/SCREENSHOT/s.png",
                    "image/png", 42L, ArtifactReference.Availability.AVAILABLE, null))),
            new CaseResultSummary(c1, CaseResultSummary.Verdict.PASSED, 120L, null, 0, List.of()));
        var terminal = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectId, suiteId, RunOutcome.FAILED,
            wireSnapshot(), summaries);

        kafkaTemplate.send("runs.completed", runId.toString(), terminal);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            assertThat(resultRows()).isEqualTo(2L);
            assertThat(artifactRows()).isEqualTo(1L);
        });
        assertThat(jdbc.queryForObject(
            "SELECT attempt_epoch FROM test_results WHERE run_id=? AND test_case_id=?",
            Integer.class, runId, c0)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT status::text FROM test_results WHERE run_id=? AND test_case_id=?",
            String.class, runId, c1)).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject(
            "SELECT storage_key FROM test_result_artifacts WHERE run_id=? AND test_case_id=?",
            String.class, runId, c0)).contains("/attempt/1/SCREENSHOT/");
    }

    private List<TestCaseSnapshotItem> wireSnapshot() {
        return List.of(
            new TestCaseSnapshotItem(caseIds.get(0), "Case 0", 0),
            new TestCaseSnapshotItem(caseIds.get(1), "Case 1", 1));
    }

    private Long resultRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_results WHERE run_id=?", Long.class, runId);
    }

    private Long artifactRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_result_artifacts WHERE run_id=?",
            Long.class, runId);
    }
}
