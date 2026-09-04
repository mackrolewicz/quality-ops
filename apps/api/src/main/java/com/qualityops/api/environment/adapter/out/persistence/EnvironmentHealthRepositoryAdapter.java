package com.qualityops.api.environment.adapter.out.persistence;

import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository;
import com.qualityops.api.environment.domain.EnvironmentHealthStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.environment.dto.EnvironmentHealthCheckView;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-008 §3 — the environment-health read/write path. Native SQL over
 * {@code environments} via {@link NamedParameterJdbcTemplate} (the health columns
 * are intentionally not mapped on {@code EnvironmentEntity}); history rows go
 * through {@link EnvironmentHealthCheckJpaRepository}. Every write is
 * {@code org_id}-guarded; {@link #selectDueBatch} / {@link #countActiveByHealthStatus}
 * are read-only platform scans.
 */
@Repository
class EnvironmentHealthRepositoryAdapter implements EnvironmentHealthRepository {

    private static final String SELECT_DUE_BATCH = """
        SELECT id, org_id, project_id, base_url, type::text AS type
        FROM environments
        WHERE status = 'ACTIVE' AND deleted_at IS NULL AND type IN ('STAGING','PRODUCTION')
          AND (last_probe_at IS NULL OR last_probe_at < now() - make_interval(secs => :secs))
        ORDER BY last_probe_at NULLS FIRST
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """;

    private static final String UPDATE_ENVIRONMENT = """
        UPDATE environments
        SET health_status = :status,
            last_probe_at = :checkedAt,
            last_healthy_at = COALESCE(:lastHealthyAt, last_healthy_at),
            consecutive_failures = :consecutiveFailures
        WHERE id = :envId AND org_id = :orgId
        """;

    private static final String SELECT_STATE = """
        SELECT health_status, last_probe_at, last_healthy_at, consecutive_failures
        FROM environments
        WHERE id = :envId AND org_id = :orgId
        """;

    private static final String COUNT_ACTIVE_BY_STATUS = """
        SELECT health_status, COUNT(*) AS cnt
        FROM environments
        WHERE status = 'ACTIVE' AND deleted_at IS NULL AND type IN ('STAGING','PRODUCTION')
        GROUP BY health_status
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final EnvironmentHealthCheckJpaRepository checkJpa;

    EnvironmentHealthRepositoryAdapter(NamedParameterJdbcTemplate jdbc,
                                      EnvironmentHealthCheckJpaRepository checkJpa) {
        this.jdbc = jdbc;
        this.checkJpa = checkJpa;
    }

    @Override
    @Transactional
    public List<Candidate> selectDueBatch(int batchSize, Duration probeInterval) {
        var params = new MapSqlParameterSource()
            .addValue("secs", probeInterval.toSeconds())
            .addValue("batch", batchSize);
        return jdbc.query(SELECT_DUE_BATCH, params, (RowMapper<Candidate>) (rs, i) -> new Candidate(
            rs.getObject("id", UUID.class),
            rs.getObject("org_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("base_url"),
            EnvironmentType.valueOf(rs.getString("type"))));
    }

    @Override
    @Transactional
    public void recordProbe(RecordProbeCommand cmd) {
        var params = new MapSqlParameterSource()
            .addValue("status", cmd.status().name())
            .addValue("checkedAt", offset(cmd.checkedAt()))
            .addValue("lastHealthyAt", offset(cmd.lastHealthyAt()))
            .addValue("consecutiveFailures", cmd.consecutiveFailures())
            .addValue("envId", cmd.envId())
            .addValue("orgId", cmd.orgId());
        int updated = jdbc.update(UPDATE_ENVIRONMENT, params);
        if (updated == 0) {
            // org_id guard rejected the row (foreign org, or env deleted mid-sweep):
            // do not leave an orphan history row.
            return;
        }
        checkJpa.save(new EnvironmentHealthCheckEntity(
            cmd.orgId(), cmd.envId(), cmd.projectId(), cmd.checkedAt(), cmd.status().name(),
            cmd.httpStatus(), cmd.latencyMs(), cmd.errorDetail()));
    }

    @Override
    public Optional<EnvironmentHealthView> getView(UUID envId, UUID orgId) {
        return currentRow(envId, orgId).map(row -> new EnvironmentHealthView(
            row.status(),
            row.lastProbeAt(),
            row.lastHealthyAt(),
            row.consecutiveFailures(),
            checkJpa.findTop20ByEnvironmentIdAndOrgIdOrderByCheckedAtDesc(envId, orgId).stream()
                .map(EnvironmentHealthRepositoryAdapter::toCheckView)
                .toList()));
    }

    @Override
    public Optional<CurrentState> currentState(UUID envId, UUID orgId) {
        return currentRow(envId, orgId)
            .map(row -> new CurrentState(row.status(), row.consecutiveFailures()));
    }

    @Override
    @Transactional
    public int deleteChecksOlderThan(Instant cutoff) {
        return checkJpa.deleteOlderThan(cutoff);
    }

    @Override
    public Map<EnvironmentHealthStatus, Long> countActiveByHealthStatus() {
        Map<EnvironmentHealthStatus, Long> out = new EnumMap<>(EnvironmentHealthStatus.class);
        jdbc.query(COUNT_ACTIVE_BY_STATUS, new MapSqlParameterSource(), rs -> {
            out.put(EnvironmentHealthStatus.valueOf(rs.getString("health_status")), rs.getLong("cnt"));
        });
        return out;
    }

    private Optional<Row> currentRow(UUID envId, UUID orgId) {
        var params = new MapSqlParameterSource()
            .addValue("envId", envId)
            .addValue("orgId", orgId);
        return jdbc.query(SELECT_STATE, params, (RowMapper<Row>) (rs, i) -> new Row(
            EnvironmentHealthStatus.valueOf(rs.getString("health_status")),
            instant(rs, "last_probe_at"),
            instant(rs, "last_healthy_at"),
            rs.getInt("consecutive_failures"))).stream().findFirst();
    }

    private static EnvironmentHealthCheckView toCheckView(EnvironmentHealthCheckEntity e) {
        return new EnvironmentHealthCheckView(
            e.getCheckedAt(),
            EnvironmentHealthStatus.valueOf(e.getHealthStatus()),
            e.getHttpStatus(),
            e.getLatencyMs(),
            e.getErrorDetail());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private record Row(EnvironmentHealthStatus status, Instant lastProbeAt, Instant lastHealthyAt,
                       int consecutiveFailures) {}
}
