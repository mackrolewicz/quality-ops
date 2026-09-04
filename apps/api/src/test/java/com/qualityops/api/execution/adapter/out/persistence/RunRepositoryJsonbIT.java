package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JPA adapter behind the {@code RunRepository} port against a real
 * PostgreSQL: the jsonb snapshot and named-enum status round-trip, the
 * conditional {@code transitionStatus} update is idempotent under Kafka
 * redelivery, and lookups are tenant-scoped. Lives in the adapter package so it
 * can see the package-private wiring while depending only on the public port.
 */
class RunRepositoryJsonbIT extends AbstractPostgresIT {

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate txTemplate;

    private UUID orgId;
    private UUID projectId;
    private UUID suiteId;
    private UUID environmentId;
    private UUID triggeredBy;
    private List<UUID> caseIds;
    private UUID lastExecutionId;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        environmentId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        triggeredBy = ItFixtures.insertUser(jdbc, orgId);
        caseIds = ItFixtures.insertCases(jdbc, orgId, suiteId, 3);
    }

    @Test
    void save_thenFindByIdAndOrgId_roundTripsSnapshotAndStatus() {
        var input = pendingRun();
        var saved = runRepository.save(input);

        var loaded = runRepository.findByIdAndOrgId(saved.id(), orgId).orElseThrow();

        assertThat(loaded.status()).isEqualTo(RunStatus.PENDING);
        assertThat(loaded.configSnapshot()).isEqualTo(input.configSnapshot());
    }

    @Test
    void save_persistsConfigSnapshot_asJsonbObject() {
        var saved = runRepository.save(pendingRun());

        String jsonType = jdbc.queryForObject(
            "SELECT jsonb_typeof(config_snapshot) FROM test_runs WHERE id = ?", String.class, saved.id());
        String pgType = jdbc.queryForObject(
            "SELECT pg_typeof(config_snapshot)::text FROM test_runs WHERE id = ?", String.class, saved.id());

        assertThat(jsonType).isEqualTo("object");
        assertThat(pgType).isEqualTo("jsonb");
    }

    @Test
    void save_persistsStatus_asRunStatusNamedEnum() {
        var saved = runRepository.save(pendingRun());

        String pgType = jdbc.queryForObject(
            "SELECT pg_typeof(status)::text FROM test_runs WHERE id = ?", String.class, saved.id());

        assertThat(pgType).isEqualTo("run_status");
    }

    @Test
    void transitionStatus_pendingToRunning_returnsTrueThenFalseOnRedelivery() {
        var runId = runRepository.save(pendingRun()).id();

        boolean first = transition(runId, RunStatus.PENDING, RunStatus.RUNNING, Instant.now());
        Instant startedAfterFirst = readStartedAt(runId);
        boolean second = transition(runId, RunStatus.PENDING, RunStatus.RUNNING, Instant.now().plusSeconds(5));
        Instant startedAfterSecond = readStartedAt(runId);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(countWithStatus(runId, "RUNNING")).isEqualTo(1);
        assertThat(startedAfterSecond).isEqualTo(startedAfterFirst);
    }

    @Test
    void transitionStatus_wrongFromStatus_returnsFalseAndLeavesRowUnchanged() {
        var runId = runRepository.save(pendingRun()).id();

        boolean moved = transition(runId, RunStatus.RUNNING, RunStatus.PASSED, Instant.now());

        assertThat(moved).isFalse();
        var loaded = runRepository.findByIdAndOrgId(runId, orgId).orElseThrow();
        assertThat(loaded.status()).isEqualTo(RunStatus.PENDING);
        assertThat(loaded.completedAt()).isNull();
    }

    @Test
    void transitionToTerminal_fromPendingOrRunning_returnsTrueThenFalseWhenAlreadyTerminal() {
        var runId = runRepository.save(pendingRun()).id();

        boolean first = terminal(runId, RunStatus.PASSED, Instant.now());
        boolean second = terminal(runId, RunStatus.FAILED, Instant.now().plusSeconds(5));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status())
            .isEqualTo(RunStatus.PASSED);
    }

    @Test
    void transitionToTerminal_setsCompletedAtAndCoalescesStartedAt() {
        // from PENDING: started_at was null -> coalesced to the terminal timestamp
        var fromPending = runRepository.save(pendingRun()).id();
        terminal(fromPending, RunStatus.FAILED, Instant.now());
        assertThat(readStartedAt(fromPending)).isNotNull();
        assertThat(readCompletedAt(fromPending)).isNotNull();

        // from RUNNING: original started_at is preserved (COALESCE keeps the non-null)
        var fromRunning = runRepository.save(pendingRun()).id();
        transition(fromRunning, RunStatus.PENDING, RunStatus.RUNNING, Instant.now());
        var originalStartedAt = readStartedAt(fromRunning);
        terminal(fromRunning, RunStatus.PASSED, Instant.now().plusSeconds(5));
        assertThat(readStartedAt(fromRunning)).isEqualTo(originalStartedAt);
        assertThat(readCompletedAt(fromRunning)).isNotNull();
    }

    @Test
    void transitionToTerminal_foreignOrgId_returnsFalseAndLeavesRunPending() {
        var runId = runRepository.save(pendingRun()).id();
        var foreignOrgId = ItFixtures.insertOrg(jdbc);

        boolean moved = terminal(runId, foreignOrgId, RunStatus.PASSED, Instant.now());

        assertThat(moved).isFalse();
        assertThat(runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status())
            .isEqualTo(RunStatus.PENDING);
    }

    @Test
    void transitionStatus_foreignOrgId_returnsFalseAndLeavesRunPending() {
        var runId = runRepository.save(pendingRun()).id();
        var foreignOrgId = ItFixtures.insertOrg(jdbc);

        boolean moved = transition(runId, foreignOrgId, RunStatus.PENDING, RunStatus.RUNNING, Instant.now());

        assertThat(moved).isFalse();
        assertThat(runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status())
            .isEqualTo(RunStatus.PENDING);
    }

    @Test
    void transitionToTerminal_wrongExecutionId_updatesZeroRows() {
        var runId = runRepository.save(pendingRun()).id();

        boolean moved = terminal(runId, orgId, UUID.randomUUID(), RunStatus.PASSED, Instant.now());

        assertThat(moved).isFalse();
        assertThat(runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status())
            .isEqualTo(RunStatus.PENDING);
    }

    @Test
    void transitionStatus_wrongExecutionId_updatesZeroRows() {
        var runId = runRepository.save(pendingRun()).id();

        boolean moved = transition(runId, orgId, UUID.randomUUID(),
            RunStatus.PENDING, RunStatus.RUNNING, Instant.now());

        assertThat(moved).isFalse();
        assertThat(runRepository.findByIdAndOrgId(runId, orgId).orElseThrow().status())
            .isEqualTo(RunStatus.PENDING);
    }

    @Test
    void findByIdAndOrgId_withForeignOrgId_returnsEmpty() {
        var runId = runRepository.save(pendingRun()).id();
        var foreignOrgId = ItFixtures.insertOrg(jdbc);

        assertThat(runRepository.findByIdAndOrgId(runId, foreignOrgId)).isEmpty();
    }

    private TestRun pendingRun() {
        var items = List.of(
            new TestCaseSnapshotItem(caseIds.get(0), "Case 0", 0),
            new TestCaseSnapshotItem(caseIds.get(1), "Case 1", 1),
            new TestCaseSnapshotItem(caseIds.get(2), "Case 2", 2));
        lastExecutionId = UUID.randomUUID();
        return new TestRun(
            UUID.randomUUID(), orgId, projectId, suiteId, environmentId,
            lastExecutionId, RunStatus.PENDING, triggeredBy, new RunConfigSnapshot(items),
            null, null, Instant.now());
    }

    // transitionStatus is a JPA @Modifying bulk update, so it needs an ambient
    // transaction. In production the @Transactional consumer supplies one; here
    // the TransactionTemplate stands in for that boundary (and commits between
    // calls so the second call genuinely sees the first's RUNNING row).
    private boolean transition(UUID runId, RunStatus from, RunStatus to, Instant at) {
        return transition(runId, orgId, lastExecutionId, from, to, at);
    }

    private boolean transition(UUID runId, UUID scopedOrgId, RunStatus from, RunStatus to, Instant at) {
        return transition(runId, scopedOrgId, lastExecutionId, from, to, at);
    }

    private boolean transition(UUID runId, UUID scopedOrgId, UUID executionId,
                               RunStatus from, RunStatus to, Instant at) {
        return Boolean.TRUE.equals(txTemplate.execute(status ->
            runRepository.transitionStatus(runId, scopedOrgId, executionId, from, to, at)));
    }

    private boolean terminal(UUID runId, RunStatus to, Instant at) {
        return terminal(runId, orgId, lastExecutionId, to, at);
    }

    private boolean terminal(UUID runId, UUID scopedOrgId, RunStatus to, Instant at) {
        return terminal(runId, scopedOrgId, lastExecutionId, to, at);
    }

    private boolean terminal(UUID runId, UUID scopedOrgId, UUID executionId, RunStatus to, Instant at) {
        return Boolean.TRUE.equals(txTemplate.execute(s ->
            runRepository.transitionToTerminal(runId, scopedOrgId, executionId, to, at)));
    }

    private Instant readStartedAt(UUID runId) {
        return jdbc.queryForObject("SELECT started_at FROM test_runs WHERE id = ?", Instant.class, runId);
    }

    private Instant readCompletedAt(UUID runId) {
        return jdbc.queryForObject("SELECT completed_at FROM test_runs WHERE id = ?", Instant.class, runId);
    }

    private int countWithStatus(UUID runId, String runStatus) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM test_runs WHERE id = ? AND status = ?::run_status",
            Integer.class, runId, runStatus);
        return count == null ? 0 : count;
    }
}
