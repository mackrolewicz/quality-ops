package com.qualityops.worker;

import com.qualityops.worker.execution.adapter.out.persistence.JdbcExecutionAttemptStore;
import com.qualityops.worker.support.AbstractWorkerPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the ADR-003 §"Amendment to ADR-002" boundary: the Worker's datasource
 * reaches exactly one table in its own {@code worker} schema and never any
 * API-owned {@code public} table.
 */
class WorkerPersistenceIsolationIT extends AbstractWorkerPostgresIT {

    private static final List<String> API_DOMAIN_TABLES = List.of(
        "test_runs", "test_results", "test_cases", "test_suites",
        "projects", "environments", "organizations", "users", "refresh_tokens");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void workerFlyway_createsOnlyTheWorkerSchema() {
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('worker.flyway_schema_history')", String.class)).isNotNull();
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('worker.execution_attempt')", String.class)).isNotNull();

        List<String> nonSystemSchemasWithTables = jdbc.queryForList(
            "SELECT DISTINCT table_schema FROM information_schema.tables "
                + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "ORDER BY table_schema", String.class);
        assertThat(nonSystemSchemasWithTables).containsExactly("worker");
    }

    @Test
    void workerDatasource_cannotSeeAnyApiDomainTable() {
        for (String table : API_DOMAIN_TABLES) {
            assertThat(jdbc.queryForObject("SELECT to_regclass('public." + table + "')", String.class))
                .as("public.%s must not exist in the Worker's database view", table)
                .isNull();
        }
    }

    @Test
    void jdbcExecutionAttemptStore_sqlTouchesOnlyTheWorkerSchema() throws Exception {
        for (Field f : JdbcExecutionAttemptStore.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            f.setAccessible(true);
            String sql = ((String) f.get(null)).toLowerCase(Locale.ROOT);
            assertThat(sql)
                .as("SQL constant %s targets worker.execution_attempt", f.getName())
                .contains("worker.execution_attempt");
            assertThat(sql)
                .as("SQL constant %s must not reference the public schema", f.getName())
                .doesNotContain("public.");
            for (String apiTable : API_DOMAIN_TABLES) {
                assertThat(sql)
                    .as("SQL constant %s must not reference API table %s", f.getName(), apiTable)
                    .doesNotContain(apiTable);
            }
        }
    }
}
