package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native-SQL analytics over {@code test_results} + {@code test_runs} (ADR-008 §1-2),
 * backed by the auto-configured {@link NamedParameterJdbcTemplate}. Every statement
 * filters {@code org_id} and (directly or transitively via the case set) {@code project_id}.
 */
@Repository
class AnalyticsRepositoryAdapter implements AnalyticsRepository {

    private static final String FLAKY_WINDOW = """
        WITH ranked AS (
          SELECT r.test_case_id, r.status, r.created_at,
                 ROW_NUMBER() OVER (PARTITION BY r.test_case_id ORDER BY r.created_at DESC) rn
          FROM test_results r
          JOIN test_runs run ON run.id = r.run_id
          WHERE r.org_id = :orgId AND run.project_id = :projectId
            AND r.status IN ('PASSED','FAILED')
        ),
        win AS (SELECT * FROM ranked WHERE rn <= :window),
        seq AS (
          SELECT test_case_id, status, created_at,
                 LAG(status) OVER (PARTITION BY test_case_id ORDER BY created_at) prev
          FROM win
        )
        SELECT test_case_id,
               COUNT(*)                                                    AS runs_analyzed,
               COUNT(*) FILTER (WHERE status = 'PASSED')                    AS pass_count,
               COUNT(*) FILTER (WHERE prev IS NOT NULL AND prev <> status) AS transitions,
               MAX(created_at)                                             AS last_run_at
        FROM seq
        GROUP BY test_case_id
        HAVING COUNT(*) >= :minRuns
        ORDER BY transitions DESC, runs_analyzed DESC
        """;

    private static final String LAST_STATUS_BY_CASE = """
        SELECT DISTINCT ON (test_case_id) test_case_id, status
        FROM test_results
        WHERE org_id = :orgId AND test_case_id IN (:ids)
        ORDER BY test_case_id, created_at DESC
        """;

    private static final String CASE_NAMES =
        "SELECT id, name FROM test_cases WHERE org_id = :orgId AND id IN (:ids)";

    private static final String RUN_COUNTS_BY_DAY = """
        SELECT date_trunc('day', created_at)::date AS d,
               COUNT(*) AS total,
               COUNT(*) FILTER (WHERE status = 'PASSED') AS passed,
               COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
        FROM test_runs
        WHERE org_id = :orgId AND project_id = :projectId AND created_at >= :since
        GROUP BY 1 ORDER BY 1
        """;

    private static final String DURATION_BY_RUN_DAY = """
        SELECT date_trunc('day', run.created_at)::date AS d,
               AVG(r.duration_ms) AS avg_ms,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) AS p95_ms
        FROM test_results r JOIN test_runs run ON run.id = r.run_id
        WHERE r.org_id = :orgId AND run.project_id = :projectId AND run.created_at >= :since
        GROUP BY 1 ORDER BY 1
        """;

    private static final String SLOW_BY_P95 = """
        SELECT r.test_case_id AS tc, COUNT(*) AS samples,
               AVG(r.duration_ms) AS avg_ms,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) AS p95_ms,
               MAX(r.duration_ms) AS max_ms
        FROM test_results r JOIN test_runs run ON run.id = r.run_id
        WHERE r.org_id = :orgId AND run.project_id = :projectId AND run.created_at >= :since
        GROUP BY r.test_case_id HAVING COUNT(*) >= :minSamples
        ORDER BY p95_ms DESC LIMIT :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    AnalyticsRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FlakyRow> flakyWindow(UUID orgId, UUID projectId, int window, int minRuns) {
        var params = new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("projectId", projectId)
            .addValue("window", window)
            .addValue("minRuns", minRuns);
        return jdbc.query(FLAKY_WINDOW, params, (RowMapper<FlakyRow>) (rs, i) -> new FlakyRow(
            rs.getObject("test_case_id", UUID.class),
            rs.getInt("runs_analyzed"),
            rs.getInt("pass_count"),
            rs.getInt("transitions"),
            rs.getTimestamp("last_run_at").toInstant()));
    }

    @Override
    public Map<UUID, String> lastStatusByCase(UUID orgId, UUID projectId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        var params = new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("ids", caseIds);
        return toMap(jdbc.query(LAST_STATUS_BY_CASE, params,
            (RowMapper<Map.Entry<UUID, String>>) (rs, i) -> Map.entry(
                rs.getObject("test_case_id", UUID.class), rs.getString("status"))));
    }

    @Override
    public Map<UUID, String> caseNames(UUID orgId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        var params = new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("ids", caseIds);
        return toMap(jdbc.query(CASE_NAMES, params,
            (RowMapper<Map.Entry<UUID, String>>) (rs, i) -> Map.entry(
                rs.getObject("id", UUID.class), rs.getString("name"))));
    }

    private static Map<UUID, String> toMap(List<Map.Entry<UUID, String>> entries) {
        Map<UUID, String> out = new HashMap<>();
        for (Map.Entry<UUID, String> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    @Override
    public List<DayRunRow> runCountsByDay(UUID orgId, UUID projectId, Instant since) {
        return jdbc.query(RUN_COUNTS_BY_DAY, dayParams(orgId, projectId, since),
            (RowMapper<DayRunRow>) (rs, i) -> new DayRunRow(
                rs.getObject("d", LocalDate.class),
                rs.getLong("total"),
                rs.getLong("passed"),
                rs.getLong("failed")));
    }

    @Override
    public List<DayDurationRow> durationByRunDay(UUID orgId, UUID projectId, Instant since) {
        return jdbc.query(DURATION_BY_RUN_DAY, dayParams(orgId, projectId, since),
            (RowMapper<DayDurationRow>) (rs, i) -> new DayDurationRow(
                rs.getObject("d", LocalDate.class),
                nullableDouble(rs, "avg_ms"),
                nullableDouble(rs, "p95_ms")));
    }

    @Override
    public List<SlowRow> slowByP95(UUID orgId, UUID projectId, Instant since, int limit, int minSamples) {
        var params = dayParams(orgId, projectId, since)
            .addValue("limit", limit)
            .addValue("minSamples", minSamples);
        return jdbc.query(SLOW_BY_P95, params, (RowMapper<SlowRow>) (rs, i) -> new SlowRow(
            rs.getObject("tc", UUID.class),
            rs.getLong("samples"),
            orZero(nullableDouble(rs, "avg_ms")),
            orZero(nullableDouble(rs, "p95_ms")),
            rs.getDouble("max_ms")));
    }

    private static MapSqlParameterSource dayParams(UUID orgId, UUID projectId, Instant since) {
        return new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("projectId", projectId)
            .addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
