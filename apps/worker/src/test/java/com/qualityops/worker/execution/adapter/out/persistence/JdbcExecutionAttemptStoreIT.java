package com.qualityops.worker.execution.adapter.out.persistence;

import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyCompleted;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyRunning;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.Claimed;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.support.AbstractWorkerPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExecutionAttemptStoreIT extends AbstractWorkerPostgresIT {

    @Autowired
    private ExecutionAttemptStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE worker.execution_attempt");
    }

    @Test
    void claim_firstCall_returnsClaimedEpochZero() {
        var result = store.claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);

        assertThat(result).isInstanceOf(Claimed.class);
        assertThat(((Claimed) result).epoch()).isZero();
    }

    @Test
    void claim_secondCallSameExecutionId_returnsAlreadyRunning() {
        var executionId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.SIMULATED);

        var again = store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.SIMULATED);

        assertThat(again).isInstanceOf(AlreadyRunning.class);
        assertThat(((AlreadyRunning) again).epoch()).isZero();
    }

    @Test
    void markCompleted_thenClaim_returnsAlreadyCompletedWithStoredJson() {
        var executionId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);
        store.markCompleted(executionId, 0, "runs.completed", "{\"x\":1}");

        var result = store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);

        assertThat(result).isInstanceOf(AlreadyCompleted.class);
        var ac = (AlreadyCompleted) result;
        assertThat(ac.terminalTopic()).isEqualTo("runs.completed");
        assertThat(ac.terminalEventJson().replaceAll("\\s", "")).isEqualTo("{\"x\":1}");
    }

    @Test
    void markCompleted_withStaleEpoch_returnsFalse() {
        var executionId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);

        boolean ok = store.markCompleted(executionId, 1, "runs.completed", "{}");

        assertThat(ok).isFalse();
    }

    @Test
    void steal_beforeLeaseElapsed_returnsEmpty() {
        var executionId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);

        assertThat(store.steal(executionId, Duration.ofMinutes(2))).isEmpty();
    }

    @Test
    void steal_afterLeaseElapsed_incrementsEpoch() {
        var executionId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);
        jdbc.update("UPDATE worker.execution_attempt SET heartbeat_at = now() - interval '10 minutes' "
            + "WHERE execution_id = ?", executionId);

        var stolen = store.steal(executionId, Duration.ofMinutes(2));

        assertThat(stolen).hasValue(1);
    }

    @Test
    void deleteOlderThan_removesOldRowsOnly() {
        var old = UUID.randomUUID();
        var fresh = UUID.randomUUID();
        store.claim(old, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);
        store.claim(fresh, UUID.randomUUID(), UUID.randomUUID(), RunnerKind.API);
        jdbc.update("UPDATE worker.execution_attempt SET created_at = now() - interval '30 days' "
            + "WHERE execution_id = ?", old);

        int deleted = store.deleteOlderThan(Instant.now().minus(Duration.ofDays(1)));

        assertThat(deleted).isEqualTo(1);
        Long remaining = jdbc.queryForObject(
            "SELECT count(*) FROM worker.execution_attempt", Long.class);
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    void claim_recordsOrgId_forTenantScopedSweeps() {
        var executionId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        store.claim(executionId, UUID.randomUUID(), orgId, RunnerKind.API);

        UUID stored = jdbc.queryForObject(
            "SELECT org_id FROM worker.execution_attempt WHERE execution_id = ?", UUID.class, executionId);

        assertThat(stored).isEqualTo(orgId);
    }
}
