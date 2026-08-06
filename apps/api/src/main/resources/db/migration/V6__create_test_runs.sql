DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'run_status') THEN
        CREATE TYPE run_status AS ENUM ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'CANCELLED');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS test_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID        NOT NULL,
    project_id      UUID        NOT NULL REFERENCES projects (id),
    suite_id        UUID        NOT NULL REFERENCES test_suites (id),
    environment_id  UUID        NOT NULL REFERENCES environments (id),
    status          run_status  NOT NULL DEFAULT 'PENDING',
    triggered_by    UUID        NOT NULL REFERENCES users (id),
    config_snapshot JSONB       NOT NULL,
    started_at      TIMESTAMPTZ NULL,
    completed_at    TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_test_runs_org_id ON test_runs (org_id);
CREATE INDEX IF NOT EXISTS idx_test_runs_project_id ON test_runs (project_id);
CREATE INDEX IF NOT EXISTS idx_test_runs_suite_id ON test_runs (suite_id);
CREATE INDEX IF NOT EXISTS idx_test_runs_environment_id ON test_runs (environment_id);
CREATE INDEX IF NOT EXISTS idx_test_runs_triggered_by ON test_runs (triggered_by);
CREATE INDEX IF NOT EXISTS idx_test_runs_status ON test_runs (status);
