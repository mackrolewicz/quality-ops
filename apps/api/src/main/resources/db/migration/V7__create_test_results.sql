DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'result_status') THEN
        CREATE TYPE result_status AS ENUM ('PASSED', 'FAILED', 'SKIPPED', 'FLAKY');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS test_results (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID         NOT NULL,
    run_id        UUID         NOT NULL REFERENCES test_runs (id),
    test_case_id  UUID         NOT NULL REFERENCES test_cases (id),
    status        result_status NOT NULL,
    duration_ms   INT          NOT NULL,
    error_message TEXT,
    retry_count   INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_test_results_run_case UNIQUE (run_id, test_case_id)
);
CREATE INDEX IF NOT EXISTS idx_test_results_run_id ON test_results (run_id);
CREATE INDEX IF NOT EXISTS idx_test_results_org_id ON test_results (org_id);
CREATE INDEX IF NOT EXISTS idx_test_results_test_case_id ON test_results (test_case_id);
