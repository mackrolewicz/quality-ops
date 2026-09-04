package com.qualityops.api.persistence;

import com.qualityops.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the shape the Flyway migrations (V1–V25) must produce: the context
 * only starts because {@code ddl-auto=validate} accepts the migrated schema, and
 * these assertions pin the details Hibernate does not check (enum labels, the
 * jsonb column type, the results uniqueness constraint, the Phase 2C queue /
 * schedule / shedlock structure, the Phase 2E analytics indexes + environment
 * health + audit_log tables, the Phase 2F repository_connection / repository_run
 * / repository_test_item tables).
 */
class SchemaMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void context_withFlywayMigratedSchema_startsWithHibernateValidate() {
        // The load-bearing assertion is that the Spring context started at all:
        // that only happens if Flyway applied V1-V7 and Hibernate's
        // ddl-auto=validate then accepted the resulting schema. The query below
        // just confirms the wired DataSource reaches the migrated database.
        assertThat(dataSource).isNotNull();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void flywayHistory_afterMigration_containsVersions1Through25() {
        List<String> versions = jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
            String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25");
    }

    @Test
    void runQueue_afterMigration_hasRetryColumnsAndIndexes() {
        String retryOfNullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='retry_of'", String.class);
        String retryCountNullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='retry_count'", String.class);
        String retryCountDefault = jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='retry_count'", String.class);
        assertThat(retryOfNullable).isEqualTo("YES");
        assertThat(retryCountNullable).isEqualTo("NO");
        assertThat(retryCountDefault).contains("0");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'run_queue'", String.class);
        assertThat(indexes).contains("idx_run_queue_retry_of", "idx_run_queue_retry_window");

        Integer retryFk = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON kcu.constraint_name = tc.constraint_name "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON ccu.constraint_name = tc.constraint_name "
                + "WHERE tc.table_name = 'run_queue' AND tc.constraint_type = 'FOREIGN KEY' "
                + "  AND kcu.column_name = 'retry_of' AND ccu.table_name = 'run_queue'",
            Integer.class);
        assertThat(retryFk).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ciIdempotencyKey_afterMigration_hasUniqueOrgKeyAndRunFk() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'ci_idempotency_key'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        String orgNullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='ci_idempotency_key' AND column_name='org_id'", String.class);
        assertThat(orgNullable).isEqualTo("NO");

        List<String> uniqueCols = jdbc.queryForList(
            "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE c.relname = 'ci_idempotency_key' AND i.indisunique AND NOT i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
        assertThat(uniqueCols).containsExactly("org_id", "idempotency_key");

        Integer fk = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON ccu.constraint_name = tc.constraint_name "
                + "WHERE tc.table_name = 'ci_idempotency_key' AND tc.constraint_type = 'FOREIGN KEY' "
                + "  AND ccu.table_name = 'test_runs'",
            Integer.class);
        assertThat(fk).isGreaterThanOrEqualTo(1);
    }

    @Test
    void webhookTables_afterMigration_haveOrgIdStateVarcharAndDueIndex() {
        Integer endpoint = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'webhook_endpoint'",
            Integer.class);
        Integer delivery = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'webhook_delivery'",
            Integer.class);
        assertThat(endpoint).isEqualTo(1);
        assertThat(delivery).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='webhook_endpoint' AND column_name='org_id'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='webhook_delivery' AND column_name='org_id'", String.class))
            .isEqualTo("NO");

        assertThat(jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='webhook_delivery' AND column_name='state'", String.class))
            .isEqualTo("character varying");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'webhook_delivery'", String.class);
        assertThat(indexes).contains("idx_webhook_delivery_due");
    }

    @Test
    void shedlock_afterMigration_exists() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'shedlock'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void runQueue_afterMigration_hasCheckConstraintsAndPartialIndexes() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'run_queue'", Integer.class);
        assertThat(table).isEqualTo(1);

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'run_queue'", String.class);
        assertThat(indexes).contains("idx_run_queue_dispatch", "idx_run_queue_active");

        String priorityType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='priority'", String.class);
        String queueStateType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='queue_state'", String.class);
        assertThat(priorityType).isEqualTo("character varying");
        assertThat(queueStateType).isEqualTo("character varying");

        String jsonNullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='run_queue' AND column_name='requested_event_json'", String.class);
        assertThat(jsonNullable).isEqualTo("YES");
    }

    @Test
    void schedule_afterMigration_hasDueIndex() {
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'schedule'", String.class);
        assertThat(indexes).contains("idx_schedule_due");
    }

    @Test
    void scheduleFire_afterMigration_hasUniqueScheduleSlot() {
        List<String> uniqueCols = jdbc.queryForList(
            "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE c.relname = 'schedule_fire' AND i.indisunique AND NOT i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
        assertThat(uniqueCols).containsExactly("schedule_id", "fire_slot");
    }

    @Test
    void orgRunConcurrency_afterMigration_hasPositiveCheck() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'org_run_concurrency'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        Integer check = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.check_constraints cc "
                + "JOIN information_schema.constraint_column_usage ccu "
                + "  ON ccu.constraint_name = cc.constraint_name "
                + "WHERE ccu.table_name = 'org_run_concurrency' AND ccu.column_name = 'max_active_runs'",
            Integer.class);
        assertThat(check).isGreaterThanOrEqualTo(1);
    }

    @Test
    void queueEnums_afterMigration_areNotPgEnumTypes() {
        Integer types = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_type WHERE typname IN ('queue_state', 'schedule_kind', "
                + "'run_priority', 'webhook_delivery_state', 'environment_health_status', "
                + "'environment_health_check_status', 'audit_outcome', "
                + "'repository_provider', 'repo_ref_type', 'framework_preset', 'repo_report_format', "
                + "'repo_resource_profile', 'repo_network_policy', 'repository_run_state', "
                + "'repository_test_item_status')",
            Integer.class);
        assertThat(types).isZero();
    }

    @Test
    void analyticsIndexes_afterMigration_existOnTestResults() {
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'test_results'", String.class);
        assertThat(indexes).contains(
            "idx_test_results_case_created",
            "idx_test_results_run_created",
            "idx_test_results_org_created");
    }

    @Test
    void environments_afterMigration_hasHealthColumns() {
        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='environments' AND column_name='health_status'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='environments' AND column_name='health_status'", String.class))
            .isEqualTo("character varying");
        assertThat(jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns "
                + "WHERE table_name='environments' AND column_name='health_status'", String.class))
            .contains("UNKNOWN");

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='environments' AND column_name='consecutive_failures'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns "
                + "WHERE table_name='environments' AND column_name='consecutive_failures'", String.class))
            .contains("0");
    }

    @Test
    void environmentHealthCheck_afterMigration_hasOrgIdAndIndexes() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'environment_health_check'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='environment_health_check' AND column_name='org_id'", String.class))
            .isEqualTo("NO");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'environment_health_check'", String.class);
        assertThat(indexes).contains("idx_env_health_check_env", "idx_env_health_check_org");
    }

    @Test
    void auditLog_afterMigration_hasOrgIdOutcomeVarcharAndIndexes() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'audit_log'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='audit_log' AND column_name='org_id'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='audit_log' AND column_name='outcome'", String.class))
            .isEqualTo("character varying");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_log'", String.class);
        assertThat(indexes).contains("idx_audit_log_org_created", "idx_audit_log_action");
    }

    @Test
    void testResultArtifacts_afterMigration_hasKeyUniquenessConstraintAndIndexes() {
        List<String> uniqueCols = jdbc.queryForList(
            "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE c.relname = 'uq_test_result_artifacts_key' AND i.indisunique "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
        assertThat(uniqueCols).containsExactly("run_id", "test_case_id", "attempt_epoch", "artifact_type");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'test_result_artifacts'", String.class);
        assertThat(indexes).contains(
            "idx_test_result_artifacts_run_id",
            "idx_test_result_artifacts_org_id",
            "idx_test_result_artifacts_case");
    }

    @Test
    void testResults_afterMigration_hasAttemptEpochColumnNotNullDefaultZero() {
        String nullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='test_results' AND column_name='attempt_epoch'", String.class);
        String columnDefault = jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns "
                + "WHERE table_name='test_results' AND column_name='attempt_epoch'", String.class);
        assertThat(nullable).isEqualTo("NO");
        assertThat(columnDefault).contains("0");
    }

    @Test
    void testRuns_afterMigration_hasNotNullUniqueExecutionId() {
        String nullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='test_runs' AND column_name='execution_id'", String.class);
        assertThat(nullable).isEqualTo("NO");

        List<String> cols = jdbc.queryForList(
            "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name=tc.constraint_name "
                + "WHERE tc.constraint_name='uq_test_runs_execution_id' AND tc.constraint_type='UNIQUE'",
            String.class);
        assertThat(cols).containsExactly("execution_id");
    }

    @Test
    void testCases_afterMigration_hasJsonbApiRequestColumn() {
        String dataType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='test_cases' AND column_name='api_request'", String.class);
        assertThat(dataType).isEqualTo("jsonb");
    }

    @Test
    void testCases_afterMigration_hasJsonbBrowserTestColumn() {
        String dataType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='test_cases' AND column_name='browser_test'", String.class);
        String nullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='test_cases' AND column_name='browser_test'", String.class);
        assertThat(dataType).isEqualTo("jsonb");
        assertThat(nullable).isEqualTo("YES");
    }

    @Test
    void namedEnums_afterMigration_haveExpectedLabels() {
        assertThat(enumLabels("user_role"))
            .containsExactly("OWNER", "ADMIN", "MEMBER", "VIEWER");
        assertThat(enumLabels("environment_type"))
            .containsExactly("DEV", "STAGING", "PRODUCTION");
        assertThat(enumLabels("environment_status"))
            .containsExactly("ACTIVE", "INACTIVE");
        assertThat(enumLabels("suite_type"))
            .containsExactly("API", "UI", "PERFORMANCE");
        assertThat(enumLabels("run_status"))
            .containsExactly("PENDING", "RUNNING", "PASSED", "FAILED", "CANCELLED");
        assertThat(enumLabels("result_status"))
            .containsExactly("PASSED", "FAILED", "SKIPPED", "FLAKY");
    }

    @Test
    void testRunsConfigSnapshot_afterMigration_isJsonbColumn() {
        String dataType = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'test_runs' AND column_name = 'config_snapshot'",
            String.class);

        assertThat(dataType).isEqualTo("jsonb");
    }

    @Test
    void testResults_afterMigration_hasUniqueRunCaseConstraint() {
        List<String> columns = jdbc.queryForList(
            "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON kcu.constraint_name = tc.constraint_name "
                + "WHERE tc.constraint_name = 'uq_test_results_run_case' "
                + "  AND tc.constraint_type = 'UNIQUE' "
                + "ORDER BY kcu.ordinal_position",
            String.class);

        assertThat(columns).containsExactly("run_id", "test_case_id");
    }

    @Test
    void repositoryConnection_afterMigration_hasOrgIdVarcharProviderAndPartialUnique() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'repository_connection'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='repository_connection' AND column_name='org_id'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='repository_connection' AND column_name='provider'", String.class))
            .isEqualTo("character varying");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'repository_connection'", String.class);
        assertThat(indexes).contains("ux_repo_conn_identity", "idx_repo_conn_org", "idx_repo_conn_project");
    }

    @Test
    void repositoryRun_afterMigration_hasOrgIdRunIdUniqueAndVarcharEnums() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'repository_run'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='repository_run' AND column_name='org_id'", String.class))
            .isEqualTo("NO");

        List<String> uniqueCols = jdbc.queryForList(
            "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE c.relname = 'repository_run' AND i.indisunique AND NOT i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
        assertThat(uniqueCols).containsExactly("run_id");

        List<String> enumTypes = jdbc.queryForList(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='repository_run' AND column_name IN "
                + "('state','provider','ref_type','framework_preset','report_format',"
                + "'resource_profile','network_policy')",
            String.class);
        assertThat(enumTypes).hasSize(7).containsOnly("character varying");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'repository_run'", String.class);
        assertThat(indexes).contains("idx_repository_run_org", "idx_repository_run_conn");
    }

    @Test
    void repositoryTestItem_afterMigration_hasOrgIdUniqueRunItemKeyAndStatusVarchar() {
        Integer table = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'repository_test_item'",
            Integer.class);
        assertThat(table).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='repository_test_item' AND column_name='org_id'", String.class))
            .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name='repository_test_item' AND column_name='status'", String.class))
            .isEqualTo("character varying");

        List<String> uniqueCols = jdbc.queryForList(
            "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE c.relname = 'repository_test_item' AND i.indisunique AND NOT i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class);
        assertThat(uniqueCols).containsExactly("run_id", "item_key");

        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'repository_test_item'", String.class);
        assertThat(indexes).contains("idx_repo_item_run", "idx_repo_item_org");
    }

    private List<String> enumLabels(String typeName) {
        return jdbc.queryForList(
            "SELECT e.enumlabel FROM pg_type t "
                + "JOIN pg_enum e ON e.enumtypid = t.oid "
                + "WHERE t.typname = ? ORDER BY e.enumsortorder",
            String.class, typeName);
    }
}
