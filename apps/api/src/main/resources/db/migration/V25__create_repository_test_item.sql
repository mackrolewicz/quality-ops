-- V25 (ADR-009 §3): normalized per-test results for a repository run.
-- Kept SEPARATE from test_results so uq_test_results_run_case, the test_case_id
-- FK, and the ADR-008 flaky/trends/slow queries stay untouched.
-- item_key = encode(sha256(coalesce(suite,'') || '\x00' || name), 'hex') — a
-- stable natural key driving the epoch-guarded ON CONFLICT (run_id, item_key)
-- upsert. status is VARCHAR + CHECK. failure_message is redacted + truncated
-- and NULL when persist-report-snippets = false.
CREATE TABLE IF NOT EXISTS repository_test_item (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID          NOT NULL,
    run_id          UUID          NOT NULL REFERENCES test_runs (id),
    item_key        VARCHAR(64)   NOT NULL,
    suite           VARCHAR(1024),
    name            VARCHAR(1024) NOT NULL,
    status          VARCHAR(16)   NOT NULL
                        CHECK (status IN ('PASSED', 'FAILED', 'SKIPPED', 'ERROR')),
    duration_ms     INT,
    failure_type    VARCHAR(255),
    failure_message VARCHAR(8192),
    attempt_epoch   INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, item_key)
);

CREATE INDEX IF NOT EXISTS idx_repo_item_run ON repository_test_item (run_id);
CREATE INDEX IF NOT EXISTS idx_repo_item_org ON repository_test_item (org_id);
