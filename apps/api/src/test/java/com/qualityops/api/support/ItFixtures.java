package com.qualityops.api.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal row factories for integration tests. {@code test_runs} references
 * {@code projects}, {@code test_suites}, {@code environments} and {@code users};
 * {@code test_results} additionally references {@code test_cases}. Every helper
 * inserts {@code org_id} explicitly and returns the generated id so tests can
 * wire the graph together and assert on tenant isolation.
 */
public final class ItFixtures {

    private ItFixtures() {
    }

    public static UUID insertOrg(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
            "INSERT INTO organizations (name, slug) VALUES (?, ?) RETURNING id",
            UUID.class, "Org " + suffix(), "org-" + suffix());
    }

    public static UUID insertUser(JdbcTemplate jdbc, UUID orgId) {
        return jdbc.queryForObject(
            "INSERT INTO users (org_id, email, hashed_password, role) "
                + "VALUES (?, ?, ?, ?::user_role) RETURNING id",
            UUID.class, orgId, "user-" + suffix() + "@example.test", "not-a-real-hash", "ADMIN");
    }

    public static UUID insertProject(JdbcTemplate jdbc, UUID orgId) {
        return jdbc.queryForObject(
            "INSERT INTO projects (org_id, name, slug) VALUES (?, ?, ?) RETURNING id",
            UUID.class, orgId, "Project " + suffix(), "project-" + suffix());
    }

    public static UUID insertSuite(JdbcTemplate jdbc, UUID orgId, UUID projectId) {
        return jdbc.queryForObject(
            "INSERT INTO test_suites (project_id, org_id, name, type) "
                + "VALUES (?, ?, ?, ?::suite_type) RETURNING id",
            UUID.class, projectId, orgId, "Suite " + suffix(), "API");
    }

    public static UUID insertEnvironment(JdbcTemplate jdbc, UUID orgId, UUID projectId) {
        return insertEnvironment(jdbc, orgId, projectId, "DEV", "https://example.test");
    }

    public static UUID insertEnvironment(JdbcTemplate jdbc, UUID orgId, UUID projectId,
                                         String type, String baseUrl) {
        return jdbc.queryForObject(
            "INSERT INTO environments (project_id, org_id, name, base_url, type, status) "
                + "VALUES (?, ?, ?, ?, ?::environment_type, ?::environment_status) RETURNING id",
            UUID.class, projectId, orgId, "Env " + suffix(), baseUrl, type, "ACTIVE");
    }

    /** A run row with an explicit {@code status} ({@code run_status} label) and
     *  {@code created_at}, an empty frozen config snapshot and a random execution id. */
    public static UUID insertRun(JdbcTemplate jdbc, UUID orgId, UUID projectId, UUID suiteId,
                                 UUID environmentId, UUID triggeredBy, String status, Instant createdAt) {
        return jdbc.queryForObject(
            "INSERT INTO test_runs (org_id, project_id, suite_id, environment_id, status, triggered_by, "
                + "config_snapshot, execution_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?::run_status, ?, ?::jsonb, ?, ?) RETURNING id",
            UUID.class, orgId, projectId, suiteId, environmentId, status, triggeredBy,
            "{\"cases\":[]}", UUID.randomUUID(), Timestamp.from(createdAt));
    }

    /** One {@code test_results} row with an explicit {@code result_status} label,
     *  {@code duration_ms} and {@code created_at}. One result per (run, case). */
    public static UUID insertResult(JdbcTemplate jdbc, UUID orgId, UUID runId, UUID testCaseId,
                                    String status, int durationMs, Instant createdAt) {
        return jdbc.queryForObject(
            "INSERT INTO test_results (org_id, run_id, test_case_id, status, duration_ms, created_at) "
                + "VALUES (?, ?, ?, ?::result_status, ?, ?) RETURNING id",
            UUID.class, orgId, runId, testCaseId, status, durationMs, Timestamp.from(createdAt));
    }

    public static List<UUID> insertCases(JdbcTemplate jdbc, UUID orgId, UUID suiteId, int n) {
        var ids = new ArrayList<UUID>(n);
        for (int i = 0; i < n; i++) {
            ids.add(jdbc.queryForObject(
                "INSERT INTO test_cases (suite_id, org_id, name, order_index) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, suiteId, orgId, "Case " + i + " " + suffix(), i));
        }
        return List.copyOf(ids);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
