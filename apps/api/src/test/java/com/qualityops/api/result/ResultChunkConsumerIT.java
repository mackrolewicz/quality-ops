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
import com.qualityops.events.ResultChunkEvent;
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

class ResultChunkConsumerIT extends AbstractKafkaPostgresIT {

    private static final String CHUNK = "results.chunk";

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private RunRepository runRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID runId;
    private UUID executionId;
    private UUID caseId;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        var projectId = ItFixtures.insertProject(jdbc, orgId);
        var suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        var environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        var triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        caseId = ItFixtures.insertCases(jdbc, orgId, suiteId, 1).get(0);
        executionId = UUID.randomUUID();
        var run = new TestRun(UUID.randomUUID(), orgId, projectId, suiteId, environmentId,
            executionId, RunStatus.RUNNING, triggeredBy,
            new RunConfigSnapshot(List.of(
                new com.qualityops.api.execution.domain.TestCaseSnapshotItem(caseId, "Case 0", 0))),
            null, null, Instant.now());
        runId = runRepository.save(run).id();
    }

    @Test
    void duplicateChunk_yieldsExactlyOneResultRow() {
        var chunk = chunk(0, CaseResultSummary.Verdict.PASSED, List.of());
        kafkaTemplate.send(CHUNK, runId.toString(), chunk);
        kafkaTemplate.send(CHUNK, runId.toString(), chunk);

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(resultRows()).isEqualTo(1L));
        assertThat(resultStatus()).isEqualTo("PASSED");
    }

    @Test
    void lowerEpochAfterHigher_doesNotOverwriteResultOrArtifacts() {
        kafkaTemplate.send(CHUNK, runId.toString(), chunk(1, CaseResultSummary.Verdict.PASSED,
            List.of(ref(ArtifactType.SCREENSHOT, key(1)))));
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(attemptEpoch()).isEqualTo(1));

        kafkaTemplate.send(CHUNK, runId.toString(), chunk(0, CaseResultSummary.Verdict.FAILED,
            List.of(ref(ArtifactType.SCREENSHOT, key(0)))));

        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() -> {
            assertThat(attemptEpoch()).isEqualTo(1);
            assertThat(resultStatus()).isEqualTo("PASSED");
            assertThat(artifactKeys()).containsExactly(key(1));
        });
    }

    @Test
    void chunkWithArtifacts_writesArtifactRows() {
        kafkaTemplate.send(CHUNK, runId.toString(), chunk(0, CaseResultSummary.Verdict.FAILED, List.of(
            ref(ArtifactType.SCREENSHOT, key(0)),
            new ArtifactReference(ArtifactType.TRACE, null, null, null,
                ArtifactReference.Availability.UNAVAILABLE, "store-unreachable"))));

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(artifactRows()).isEqualTo(2L));
    }

    @Test
    void foreignOrgChunk_isSkipped() {
        var foreignOrg = ItFixtures.insertOrg(jdbc);
        var event = new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), foreignOrg, runId,
            executionId, Instant.now(), ResultChunkEvent.SCHEMA_VERSION,
            caseId, 0, CaseResultSummary.Verdict.PASSED, 10L, null, List.of());
        kafkaTemplate.send(CHUNK, runId.toString(), event);

        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() ->
            assertThat(rawResultRows()).isZero());
    }

    @Test
    void staleExecutionIdChunk_isSkipped() {
        var event = new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            UUID.randomUUID(), Instant.now(), ResultChunkEvent.SCHEMA_VERSION,
            caseId, 0, CaseResultSummary.Verdict.PASSED, 10L, null, List.of());
        kafkaTemplate.send(CHUNK, runId.toString(), event);

        await().during(3, SECONDS).atMost(9, SECONDS).untilAsserted(() ->
            assertThat(rawResultRows()).isZero());
    }

    private ResultChunkEvent chunk(int epoch, CaseResultSummary.Verdict verdict,
                                   List<ArtifactReference> artifacts) {
        return new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, epoch, verdict, 123L, null, artifacts);
    }

    private ArtifactReference ref(ArtifactType type, String key) {
        return new ArtifactReference(type, key, "image/png", 10L,
            ArtifactReference.Availability.AVAILABLE, null);
    }

    private String key(int epoch) {
        return "org/" + orgId + "/run/" + runId + "/case/" + caseId + "/attempt/" + epoch + "/SCREENSHOT/s.png";
    }

    private Long resultRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_results WHERE run_id=? AND org_id=?",
            Long.class, runId, orgId);
    }

    private Long rawResultRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_results WHERE run_id=?", Long.class, runId);
    }

    private Long artifactRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_result_artifacts WHERE run_id=?",
            Long.class, runId);
    }

    private String resultStatus() {
        return jdbc.queryForObject("SELECT status::text FROM test_results WHERE run_id=? AND test_case_id=?",
            String.class, runId, caseId);
    }

    private Integer attemptEpoch() {
        return jdbc.queryForObject(
            "SELECT attempt_epoch FROM test_results WHERE run_id=? AND test_case_id=?",
            Integer.class, runId, caseId);
    }

    private List<String> artifactKeys() {
        return jdbc.queryForList(
            "SELECT storage_key FROM test_result_artifacts WHERE run_id=? ORDER BY attempt_epoch",
            String.class, runId);
    }
}
