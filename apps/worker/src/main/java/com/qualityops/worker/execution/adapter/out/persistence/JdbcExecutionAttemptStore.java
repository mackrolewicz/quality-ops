package com.qualityops.worker.execution.adapter.out.persistence;

import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcExecutionAttemptStore implements ExecutionAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionAttemptStore.class);

    private static final String INSERT_CLAIM = """
        INSERT INTO worker.execution_attempt (execution_id, run_id, org_id, status, runner_kind)
        VALUES (?, ?, ?, 'RUNNING', ?)
        ON CONFLICT (execution_id) DO NOTHING
        """;
    private static final String SELECT_ROW = """
        SELECT status, attempt_epoch, heartbeat_at, terminal_topic, terminal_event_json
        FROM worker.execution_attempt WHERE execution_id = ?
        """;
    private static final String STEAL = """
        UPDATE worker.execution_attempt
        SET attempt_epoch = attempt_epoch + 1, claimed_at = now(), heartbeat_at = now()
        WHERE execution_id = ? AND status = 'RUNNING'
          AND heartbeat_at < now() - make_interval(secs => ?)
        RETURNING attempt_epoch
        """;
    private static final String HEARTBEAT = """
        UPDATE worker.execution_attempt SET heartbeat_at = now()
        WHERE execution_id = ? AND attempt_epoch = ? AND status = 'RUNNING'
        """;
    private static final String MARK_COMPLETED = """
        UPDATE worker.execution_attempt
        SET status = 'COMPLETED', completed_at = now(), heartbeat_at = now(),
            terminal_topic = ?, terminal_event_json = ?::jsonb
        WHERE execution_id = ? AND attempt_epoch = ?
        """;
    private static final String DELETE_OLD =
        "DELETE FROM worker.execution_attempt WHERE created_at < ?";
    private static final String IS_COMPLETED =
        "SELECT 1 FROM worker.execution_attempt WHERE execution_id = ? AND status = 'COMPLETED'";

    private final JdbcTemplate jdbc;

    public JdbcExecutionAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ClaimResult claim(UUID executionId, UUID runId, UUID orgId, RunnerKind kind) {
        int inserted = jdbc.update(INSERT_CLAIM, executionId, runId, orgId, kind.name());
        if (inserted == 1) {
            log.info("Claimed attempt {} for run {}", executionId, runId);
            return new Claimed(0);
        }
        return jdbc.queryForObject(SELECT_ROW, (rs, n) -> {
            String status = rs.getString("status");
            int epoch = rs.getInt("attempt_epoch");
            if ("COMPLETED".equals(status)) {
                return new AlreadyCompleted(rs.getString("terminal_topic"),
                                            rs.getString("terminal_event_json"));
            }
            return new AlreadyRunning(epoch, rs.getTimestamp("heartbeat_at").toInstant());
        }, executionId);
    }

    @Override
    public void heartbeat(UUID executionId, int epoch) {
        jdbc.update(HEARTBEAT, executionId, epoch);
    }

    @Override
    public boolean markCompleted(UUID executionId, int epoch, String terminalTopic, String terminalEventJson) {
        return jdbc.update(MARK_COMPLETED, terminalTopic, terminalEventJson, executionId, epoch) == 1;
    }

    @Override
    public OptionalInt steal(UUID executionId, Duration lease) {
        var epochs = jdbc.query(STEAL, (rs, n) -> rs.getInt(1), executionId, (double) lease.toSeconds());
        return epochs.isEmpty() ? OptionalInt.empty() : OptionalInt.of(epochs.get(0));
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update(DELETE_OLD, Timestamp.from(cutoff));
    }

    @Override
    public boolean isAttemptCompleted(UUID executionId) {
        return !jdbc.queryForList(IS_COMPLETED, Integer.class, executionId).isEmpty();
    }

    @Override
    public Set<UUID> completedExecutionIds(Collection<UUID> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = List.copyOf(executionIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT execution_id FROM worker.execution_attempt "
            + "WHERE status = 'COMPLETED' AND execution_id IN (" + placeholders + ")";
        List<UUID> rows = jdbc.query(sql,
            (rs, n) -> (UUID) rs.getObject("execution_id"), ids.toArray());
        return new HashSet<>(rows);
    }
}
